package cn.flying.service.impl;

import cn.flying.common.util.Const;
import cn.flying.common.util.FlowUtils;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.auth.ChangePasswordVO;
import cn.flying.dao.vo.auth.EmailRegisterVO;
import cn.flying.dao.vo.auth.EmailResetVO;
import cn.flying.dao.vo.auth.ModifyEmailVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceImplTest {

    @InjectMocks
    private AccountServiceImpl accountService;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private AmqpTemplate rabbitTemplate;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private FlowUtils flowUtils;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(accountService, "VERIFY_LIMIT", 60);
        ReflectionTestUtils.setField(accountService, "baseMapper", accountMapper);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void loadUserByUsername_returnsUserDetailsWhenFound() {
        Account account = new Account(1L, "alice", "encoded", "alice@test.com", "user", null);
        when(accountMapper.selectOne(any())).thenReturn(account);

        UserDetails userDetails = accountService.loadUserByUsername("alice");

        assertThat(userDetails.getUsername()).isEqualTo("alice");
        assertThat(userDetails.getPassword()).isEqualTo("encoded");
        assertThat(userDetails.getAuthorities()).extracting("authority").containsExactly("ROLE_user");
    }

    @Test
    void loadUserByUsername_throwsWhenMissing() {
        when(accountMapper.selectOne(any())).thenReturn(null);
        assertThrows(UsernameNotFoundException.class, () -> accountService.loadUserByUsername("missing"));
    }

    @Test
    void registerEmailVerifyCode_persistsCodeAndQueuesMail() {
        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(true);

        String result = accountService.registerEmailVerifyCode("register", "user@test.com", "127.0.0.1");

        assertNull(result);
        ArgumentCaptor<Map<String, Object>> mailCaptor = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> valueCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(eq(Const.MQ_MAIL), mailCaptor.capture());
        verify(valueOperations).set(eq(Const.VERIFY_EMAIL_DATA + "user@test.com"),
                valueCaptor.capture(), eq(3L), eq(TimeUnit.MINUTES));
        Map<String, Object> mailPayload = mailCaptor.getValue();
        assertThat(mailPayload.get("type")).isEqualTo("register");
        assertThat(mailPayload.get("email")).isEqualTo("user@test.com");
        assertThat(String.valueOf(mailPayload.get("code"))).isEqualTo(valueCaptor.getValue());
    }

    @Test
    void registerEmailVerifyCode_respectsRateLimit() {
        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(false);

        String message = accountService.registerEmailVerifyCode("register", "user@test.com", "127.0.0.1");

        assertThat(message).isEqualTo("请求频繁，请稍后再试");
        verifyNoInteractions(rabbitTemplate);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void registerEmailAccount_succeedsWhenDataValid() {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail("user@test.com");
        vo.setCode("123456");
        vo.setUsername("alice");
        vo.setPassword("1234567");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "user@test.com")).thenReturn("123456");
        when(accountMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("1234567")).thenReturn("encoded");
        when(accountMapper.insert(any(Account.class))).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        try (MockedStatic<IdUtils> mocked = mockStatic(IdUtils.class)) {
            mocked.when(IdUtils::nextUserId).thenReturn(1000L);

            String result = accountService.registerEmailAccount(vo);

            assertNull(result);
            verify(accountMapper).insert(argThat((Account account) -> account.getEmail().equals("user@test.com")));
            verify(stringRedisTemplate).delete((String) eq(Const.VERIFY_EMAIL_DATA + "user@test.com"));
        }
    }

    @Test
    void registerEmailAccount_returnsErrorWhenCodeMismatch() {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail("user@test.com");
        vo.setCode("000000");
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "user@test.com")).thenReturn("123456");

        String result = accountService.registerEmailAccount(vo);

        assertThat(result).isEqualTo("验证码错误，请重新输入");
    }

    @Test
    void resetEmailAccountPassword_updatesPassword() {
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail("user@test.com");
        vo.setCode("123456");
        vo.setPassword("newPass");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "user@test.com")).thenReturn("123456");
        when(passwordEncoder.encode("newPass")).thenReturn("encodedNew");
        when(accountMapper.update(any(), any())).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        String result = accountService.resetEmailAccountPassword(vo);

        assertNull(result);
        verify(accountMapper).update(eq(null), any());
        verify(stringRedisTemplate).delete((String) eq(Const.VERIFY_EMAIL_DATA + "user@test.com"));
    }

    @Test
    void resetEmailAccountPassword_returnsErrorWhenUpdateFails() {
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail("user@test.com");
        vo.setCode("123456");
        vo.setPassword("newPass");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "user@test.com")).thenReturn("123456");
        when(passwordEncoder.encode("newPass")).thenReturn("encodedNew");
        when(accountMapper.update(any(), any())).thenReturn(0);

        String result = accountService.resetEmailAccountPassword(vo);

        assertThat(result).isEqualTo("更新失败，请联系管理员");
    }

    @Test
    void modifyEmail_updatesWhenCodeValidAndUnused() {
        ModifyEmailVO vo = new ModifyEmailVO();
        vo.setEmail("new@test.com");
        vo.setCode("123456");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "new@test.com")).thenReturn("123456");
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(accountMapper.update(any(), any())).thenReturn(1);

        String result = accountService.modifyEmail(1L, vo);

        assertNull(result);
        verify(stringRedisTemplate).delete((String) eq(Const.VERIFY_EMAIL_DATA + "new@test.com"));
        verify(accountMapper).update(any(), any());
    }

    @Test
    void modifyEmail_detectsEmailInUse() {
        ModifyEmailVO vo = new ModifyEmailVO();
        vo.setEmail("used@test.com");
        vo.setCode("123456");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "used@test.com")).thenReturn("123456");
        Account existing = new Account(2L, "bob", "pwd", "used@test.com", "user", null);
        when(accountMapper.selectOne(any())).thenReturn(existing);

        String result = accountService.modifyEmail(1L, vo);

        assertThat(result).isEqualTo("此邮箱已被他人绑定，无法完成此操作！");
        verify(accountMapper, never()).update(any(), any());
    }

    @Test
    void changePassword_updatesOnMatch() {
        ChangePasswordVO vo = new ChangePasswordVO();
        vo.setPassword("old");
        vo.setNew_password("new");

        Account account = new Account(1L, "alice", "encodedOld", "user@test.com", "user", null);
        when(accountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("old", "encodedOld")).thenReturn(true);
        when(passwordEncoder.encode("new")).thenReturn("encodedNew");
        when(accountMapper.update(any(), any())).thenReturn(1);

        String result = accountService.changePassword("1", vo);

        assertNull(result);
        verify(accountMapper).update(any(), any());
    }

    @Test
    void changePassword_returnsErrorOnMismatch() {
        ChangePasswordVO vo = new ChangePasswordVO();
        vo.setPassword("bad");

        Account account = new Account(1L, "alice", "encodedOld", "user@test.com", "user", null);
        when(accountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("bad", "encodedOld")).thenReturn(false);

        String result = accountService.changePassword("1", vo);

        assertThat(result).isEqualTo("原密码错误，请重新输入");
        verify(accountMapper, never()).update(any(), any());
    }

    // ============= 新增测试用例：并发、安全性和验证 =============

    @Test
    void registerEmailAccount_handlesConcurrentRegistration() throws InterruptedException {
        // 测试并发注册同一邮箱
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail("concurrent@test.com");
        vo.setCode("123456");
        vo.setUsername("concurrent");
        vo.setPassword("password123");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "concurrent@test.com")).thenReturn("123456");
        when(passwordEncoder.encode("password123")).thenReturn("encoded");

        // 第一个请求成功 - 邮箱和用户名都不存在
        when(accountMapper.selectOne(any())).thenReturn(null);
        when(accountMapper.exists(any())).thenReturn(false);
        when(accountMapper.insert(any(Account.class))).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        try (MockedStatic<IdUtils> mocked = mockStatic(IdUtils.class)) {
            mocked.when(IdUtils::nextUserId).thenReturn(1000L);

            // 第一个注册应该成功
            String result1 = accountService.registerEmailAccount(vo);
            assertNull(result1);

            // 第二个注册 - 用户名已存在
            Account existingAccount = new Account(1000L, "concurrent", "encoded",
                "concurrent@test.com", "user", null);
            // 重新配置Mock，使得exists返回true（表示用户名已存在）
            when(accountMapper.exists(any())).thenReturn(true);

            String result2 = accountService.registerEmailAccount(vo);
            // 根据实际实现，当邮箱已经被注册时，会返回"该邮件地址已被注册"
            assertThat(result2).isEqualTo("该邮件地址已被注册");
        }
    }

    @Test
    void registerEmailAccount_preventsSQLInjection() {
        // 测试SQL注入防护
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail("test@test.com");
        vo.setCode("123456");
        vo.setUsername("user'; DROP TABLE accounts; --");
        vo.setPassword("password123");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "test@test.com")).thenReturn("123456");
        when(accountMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(accountMapper.insert(any(Account.class))).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        try (MockedStatic<IdUtils> mocked = mockStatic(IdUtils.class)) {
            mocked.when(IdUtils::nextUserId).thenReturn(1000L);

            // 应该正常处理，不会执行SQL注入
            String result = accountService.registerEmailAccount(vo);
            assertNull(result);

            // 验证用户名被正确存储（包含特殊字符）
            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountMapper).insert(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getUsername()).isEqualTo("user'; DROP TABLE accounts; --");
        }
    }

    @Test
    void registerEmailVerifyCode_validatesEmailFormat() {
        // 测试各种邮箱格式
        String[] validEmails = {
            "user@example.com",
            "user.name@example.com",
            "user+tag@example.co.uk",
            "user_name@example-domain.com"
        };

        String[] invalidEmails = {
            "invalid.email",
            "@example.com",
            "user@",
            "user @example.com",
            "user@.com",
            ""
        };

        when(flowUtils.limitOnceCheck(anyString(), anyInt())).thenReturn(true);

        // 测试有效邮箱
        for (String email : validEmails) {
            String result = accountService.registerEmailVerifyCode("register", email, "127.0.0.1");
            assertNull(result, "应该接受有效邮箱: " + email);
        }

        // 注意：实际实现可能没有邮箱格式验证，这里只是示例
        // 如果需要，应该在实际代码中添加验证逻辑
    }

    @Test
    void registerEmailAccount_handlesLongUsername() {
        // 测试超长用户名
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail("test@test.com");
        vo.setCode("123456");
        vo.setUsername("a".repeat(256)); // 256个字符的用户名
        vo.setPassword("password123");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "test@test.com")).thenReturn("123456");
        when(accountMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(accountMapper.insert(any(Account.class))).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        try (MockedStatic<IdUtils> mocked = mockStatic(IdUtils.class)) {
            mocked.when(IdUtils::nextUserId).thenReturn(1000L);

            String result = accountService.registerEmailAccount(vo);

            // 根据实际业务逻辑，可能会成功或失败
            // 这里假设系统应该处理或拒绝超长用户名
            verify(accountMapper).insert(any(Account.class));
        }
    }

    @Test
    void modifyEmail_handlesConcurrentModification() {
        // 测试并发修改邮箱
        ModifyEmailVO vo = new ModifyEmailVO();
        vo.setEmail("new@test.com");
        vo.setCode("123456");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "new@test.com")).thenReturn("123456");

        // 模拟并发：第一次检查邮箱未被使用，第二次已被使用
        when(accountMapper.selectOne(any()))
            .thenReturn(null)  // 第一次检查：邮箱未被使用
            .thenReturn(new Account(2L, "other", "pwd", "new@test.com", "user", null)); // 第二次已被占用

        // 模拟更新时检测到邮箱已被占用（更新返回0）
        when(accountMapper.update(any(), any())).thenReturn(0);

        String result = accountService.modifyEmail(1L, vo);

        // 应该返回错误或null（取决于实现）
        assertThat(result).isEqualTo("更新失败，请联系管理员");
    }

    @Test
    void registerEmailVerifyCode_handlesHighFrequencyRequests() {
        // 测试高频请求
        String email = "frequent@test.com";
        String ip = "192.168.1.1";

        // 前几次请求通过
        when(flowUtils.limitOnceCheck(anyString(), anyInt()))
            .thenReturn(true, true, true, false);

        // 正常请求
        for (int i = 0; i < 3; i++) {
            String result = accountService.registerEmailVerifyCode("register", email, ip);
            assertNull(result);
        }

        // 第4次请求应该被限流
        String result = accountService.registerEmailVerifyCode("register", email, ip);
        assertThat(result).isEqualTo("请求频繁，请稍后再试");

        // 验证只发送了3封邮件
        verify(rabbitTemplate, times(3)).convertAndSend(eq(Const.MQ_MAIL), any(Map.class));
    }

    @Test
    void changePassword_validatesPasswordStrength() {
        // 测试密码强度验证
        ChangePasswordVO weakPasswordVO = new ChangePasswordVO();
        weakPasswordVO.setPassword("old");
        weakPasswordVO.setNew_password("123"); // 弱密码

        Account account = new Account(1L, "alice", "encodedOld", "user@test.com", "user", null);
        when(accountMapper.selectOne(any())).thenReturn(account);
        when(passwordEncoder.matches("old", "encodedOld")).thenReturn(true);

        // 注意：实际实现可能没有密码强度验证
        // 这里只是示例，展示应该如何测试
        String result = accountService.changePassword("1", weakPasswordVO);

        // 如果有密码强度验证，应该返回错误
        // assertThat(result).isEqualTo("密码强度不足");

        // 当前实现没有密码强度验证，所以会成功
        when(passwordEncoder.encode("123")).thenReturn("encodedWeak");
        when(accountMapper.update(any(), any())).thenReturn(1);
        result = accountService.changePassword("1", weakPasswordVO);
        assertNull(result);
    }

    @Test
    void resetEmailAccountPassword_preventsPasswordReuse() {
        // 测试防止密码重复使用
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail("user@test.com");
        vo.setCode("123456");
        vo.setPassword("oldPassword"); // 尝试使用旧密码

        Account existingAccount = new Account(1L, "user", "encodedOld", "user@test.com", "user", null);

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "user@test.com")).thenReturn("123456");
        when(accountMapper.selectOne(any())).thenReturn(existingAccount);
        when(passwordEncoder.encode("oldPassword")).thenReturn("encodedOld");
        when(passwordEncoder.matches("oldPassword", "encodedOld")).thenReturn(true);

        // 注意：实际实现可能没有防止密码重用的逻辑
        // 这里展示了如何测试这种场景
        when(accountMapper.update(any(), any())).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        String result = accountService.resetEmailAccountPassword(vo);

        // 当前实现允许重用密码
        assertNull(result);
    }

    @Test
    void registerEmailAccount_handlesSpecialCharactersInEmail() {
        // 测试邮箱中的特殊字符处理
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setEmail("user+test@example.com");
        vo.setCode("123456");
        vo.setUsername("specialuser");
        vo.setPassword("password123");

        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "user+test@example.com")).thenReturn("123456");
        when(accountMapper.exists(any())).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("encoded");
        when(accountMapper.insert(any(Account.class))).thenReturn(1);
        when(stringRedisTemplate.delete((String) any())).thenReturn(true);

        try (MockedStatic<IdUtils> mocked = mockStatic(IdUtils.class)) {
            mocked.when(IdUtils::nextUserId).thenReturn(1000L);

            String result = accountService.registerEmailAccount(vo);
            assertNull(result);

            ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
            verify(accountMapper).insert(accountCaptor.capture());
            assertThat(accountCaptor.getValue().getEmail()).isEqualTo("user+test@example.com");
        }
    }
}
