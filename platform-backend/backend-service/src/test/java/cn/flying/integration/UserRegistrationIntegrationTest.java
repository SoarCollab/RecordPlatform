package cn.flying.integration;

import cn.flying.common.util.Const;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.auth.ChangePasswordVO;
import cn.flying.dao.vo.auth.EmailRegisterVO;
import cn.flying.dao.vo.auth.EmailResetVO;
import cn.flying.service.AccountService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 用户注册全流程集成测试
 * 测试从邮箱验证到账户创建的完整流程
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class UserRegistrationIntegrationTest {

    @Autowired
    private AccountService accountService;

    @Resource
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Resource
    private AmqpTemplate rabbitTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @Test
    void testCompleteRegistrationFlow() {
        // 测试完整的用户注册流程
        String email = "newuser@test.com";
        String username = "newuser";
        String password = "SecurePass123";
        String verifyCode = "123456";

        // Step 1: 发送验证码
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String sendResult = accountService.registerEmailVerifyCode("register", email, "127.0.0.1");
        assertNull(sendResult);

        // 验证邮件已发送
        verify(rabbitTemplate).convertAndSend(eq(Const.MQ_MAIL), argThat((Map<String, Object> map) ->
                map.get("email").equals(email) && map.get("type").equals("register")
        ));

        // 验证验证码已存储
        verify(valueOperations).set(
                eq(Const.VERIFY_EMAIL_DATA + email),
                anyString(),
                eq(3L),
                eq(TimeUnit.MINUTES)
        );

        // Step 2: 使用验证码注册
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setEmail(email);
        registerVO.setCode(verifyCode);
        registerVO.setUsername(username);
        registerVO.setPassword(password);

        // Mock验证码验证
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + email)).thenReturn(verifyCode);
        when(stringRedisTemplate.delete(Const.VERIFY_EMAIL_DATA + email)).thenReturn(true);

        String registerResult = accountService.registerEmailAccount(registerVO);
        assertNull(registerResult);

        // Step 3: 验证账户已创建
        Account createdAccount = accountMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Account>()
                        .eq("email", email)
        );

        assertNotNull(createdAccount);
        assertThat(createdAccount.getUsername()).isEqualTo(username);
        assertThat(createdAccount.getEmail()).isEqualTo(email);
        assertTrue(passwordEncoder.matches(password, createdAccount.getPassword()));

        // Step 4: 验证用户可以登录
        var userDetails = accountService.loadUserByUsername(username);
        assertNotNull(userDetails);
        assertThat(userDetails.getUsername()).isEqualTo(username);
    }

    @Test
    void testPasswordResetFlow() {
        // 测试密码重置流程
        String email = "existing@test.com";
        String oldPassword = "OldPass123";
        String newPassword = "NewPass456";
        String verifyCode = "654321";

        // 准备已存在的用户
        Account existingAccount = new Account();
        existingAccount.setId(1L);
        existingAccount.setEmail(email);
        existingAccount.setUsername("existinguser");
        existingAccount.setPassword(passwordEncoder.encode(oldPassword));
        accountMapper.insert(existingAccount);

        // Step 1: 发送重置验证码
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        String sendResult = accountService.registerEmailVerifyCode("reset", email, "127.0.0.1");
        assertNull(sendResult);

        // Step 2: 使用验证码重置密码
        EmailResetVO resetVO = new EmailResetVO();
        resetVO.setEmail(email);
        resetVO.setCode(verifyCode);
        resetVO.setPassword(newPassword);

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + email)).thenReturn(verifyCode);
        when(stringRedisTemplate.delete(Const.VERIFY_EMAIL_DATA + email)).thenReturn(true);

        String resetResult = accountService.resetEmailAccountPassword(resetVO);
        assertNull(resetResult);

        // Step 3: 验证密码已更新
        Account updatedAccount = accountMapper.selectById(existingAccount.getId());
        assertTrue(passwordEncoder.matches(newPassword, updatedAccount.getPassword()));
        assertFalse(passwordEncoder.matches(oldPassword, updatedAccount.getPassword()));
    }

    @Test
    void testDuplicateRegistrationPrevention() {
        // 测试防止重复注册
        String email = "duplicate@test.com";
        String username = "duplicateuser";

        // 创建已存在的用户
        Account existingAccount = new Account();
        existingAccount.setId(1L);
        existingAccount.setEmail(email);
        existingAccount.setUsername(username);
        existingAccount.setPassword(passwordEncoder.encode("password"));
        accountMapper.insert(existingAccount);

        // 尝试使用相同邮箱注册
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setEmail(email);
        registerVO.setCode("123456");
        registerVO.setUsername("newusername");
        registerVO.setPassword("password");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + email)).thenReturn("123456");

        String result = accountService.registerEmailAccount(registerVO);
        assertThat(result).isEqualTo("用户名或邮箱已被注册");

        // 尝试使用相同用户名注册
        registerVO.setEmail("different@test.com");
        registerVO.setUsername(username);

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "different@test.com")).thenReturn("123456");

        result = accountService.registerEmailAccount(registerVO);
        assertThat(result).isEqualTo("用户名或邮箱已被注册");
    }

    @Test
    void testConcurrentRegistration() throws InterruptedException {
        // 测试并发注册场景
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount;
        AtomicInteger failCount;
        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount)) {
            successCount = new AtomicInteger(0);
            failCount = new AtomicInteger(0);

            String baseEmail = "concurrent";
            String domain = "@test.com";

            when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        EmailRegisterVO vo = new EmailRegisterVO();
                        vo.setEmail(baseEmail + index + domain);
                        vo.setCode("123456");
                        vo.setUsername("user" + index);
                        vo.setPassword("password");

                        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + vo.getEmail()))
                                .thenReturn("123456");

                        String result = accountService.registerEmailAccount(vo);
                        if (result == null) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            executor.shutdown();
        }

        // 验证所有不同邮箱的注册都应该成功
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failCount.get()).isEqualTo(0);
    }

    @Test
    void testChangePasswordFlow() {
        // 测试修改密码流程
        String username = "changepassuser";
        String oldPassword = "OldPassword123";
        String newPassword = "NewPassword456";

        // 创建用户
        Account account = new Account();
        account.setId(100L);
        account.setUsername(username);
        account.setEmail("change@test.com");
        account.setPassword(passwordEncoder.encode(oldPassword));
        accountMapper.insert(account);

        // 修改密码
        ChangePasswordVO changeVO = new ChangePasswordVO();
        changeVO.setPassword(oldPassword);
        changeVO.setNew_password(newPassword);

        String result = accountService.changePassword(String.valueOf(account.getId()), changeVO);
        assertNull(result);

        // 验证密码已更新
        Account updatedAccount = accountMapper.selectById(account.getId());
        assertTrue(passwordEncoder.matches(newPassword, updatedAccount.getPassword()));

        // 验证旧密码不再有效
        assertFalse(passwordEncoder.matches(oldPassword, updatedAccount.getPassword()));
    }

    @Test
    void testInvalidVerificationCode() {
        // 测试无效验证码
        String email = "invalid@test.com";

        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setEmail(email);
        registerVO.setCode("wrong_code");
        registerVO.setUsername("invaliduser");
        registerVO.setPassword("password");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + email)).thenReturn("correct_code");

        String result = accountService.registerEmailAccount(registerVO);
        assertThat(result).isEqualTo("验证码错误，请重新输入");

        // 验证账户未创建
        Account account = accountMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Account>()
                        .eq("email", email)
        );
        assertNull(account);
    }

    @Test
    void testExpiredVerificationCode() {
        // 测试过期验证码
        String email = "expired@test.com";

        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setEmail(email);
        registerVO.setCode("123456");
        registerVO.setUsername("expireduser");
        registerVO.setPassword("password");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        // 模拟验证码已过期（返回null）
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + email)).thenReturn(null);

        String result = accountService.registerEmailAccount(registerVO);
        assertThat(result).isEqualTo("验证码错误，请重新输入");
    }

    @Test
    @Transactional
    void testRegistrationRollback() {
        // 测试注册过程中的事务回滚
        String email = "rollback@test.com";

        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setEmail(email);
        registerVO.setCode("123456");
        registerVO.setUsername("rollbackuser");
        registerVO.setPassword("password");

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + email)).thenReturn("123456");

        // 模拟数据库操作失败
        doThrow(new RuntimeException("Database error"))
                .when(stringRedisTemplate).delete(anyString());

        // 执行注册，期待异常
        assertThrows(RuntimeException.class, () -> {
            accountService.registerEmailAccount(registerVO);
        });

        // 验证账户未创建（事务回滚）
        Account account = accountMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Account>()
                        .eq("email", email)
        );
        assertNull(account);
    }
}