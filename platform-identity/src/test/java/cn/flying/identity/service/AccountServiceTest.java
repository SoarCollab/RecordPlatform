package cn.flying.identity.service;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.constant.Const;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.service.impl.AccountServiceImpl;
import cn.flying.identity.util.FlowUtils;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.identity.vo.request.ModifyEmailVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 账户服务单元测试
 * 测试范围：用户查找、注册、验证码、密码管理、邮箱修改
 *
 * @author 王贝强
 * @create 2025-01-13
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountServiceTest {

    @Spy
    @InjectMocks
    private AccountServiceImpl accountService;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private EmailService emailService;

    @Mock
    private PasswordService passwordService;

    @Mock
    private FlowUtils flowUtils;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ApplicationProperties applicationProperties;

    @Mock
    private ApplicationProperties.VerifyCode verifyCodeConfig;

    // 测试数据常量
    private static final Long TEST_USER_ID = 123L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Test123456";
    private static final String TEST_PASSWORD_ENCODED = "$2a$10$hashedpassword";
    private static final String TEST_CODE = "123456";
    private static final String TEST_IP = "192.168.1.100";
    private static final int TEST_VERIFY_LIMIT = 60;

    @BeforeEach
    void setUp() {
        // 配置Redis Mock
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        // 配置ApplicationProperties Mock
        when(applicationProperties.getVerifyCode()).thenReturn(verifyCodeConfig);
        when(verifyCodeConfig.getEmailLimit()).thenReturn(TEST_VERIFY_LIMIT);

        // 注入baseMapper以支持MyBatis Plus的query()方法
        ReflectionTestUtils.setField(accountService, "baseMapper", accountMapper);

        // 配置exists()的默认Mock - 默认返回false（不存在）
        when(accountMapper.exists(any(QueryWrapper.class))).thenReturn(false);
    }

    @Test
    void testRegisterEmailVerifyCode_Success() {
        // Mock流量限制检查通过
        when(flowUtils.checkEmailVerifyLimit(eq(TEST_IP), eq(TEST_VERIFY_LIMIT)))
                .thenReturn(true);

        // Mock邮件发送成功
        when(emailService.sendVerifyCode(eq(TEST_EMAIL), anyString(), eq("register")))
                .thenReturn(true);

        // Mock Redis操作
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        // 执行测试
        Result<Void> result = accountService.registerEmailVerifyCode("register", TEST_EMAIL, TEST_IP);

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证流量限制被检查
        verify(flowUtils).checkEmailVerifyLimit(eq(TEST_IP), eq(TEST_VERIFY_LIMIT));

        // 验证邮件被发送
        verify(emailService).sendVerifyCode(eq(TEST_EMAIL), anyString(), eq("register"));

        // 验证验证码存储到Redis
        verify(valueOperations).set(
            eq(Const.VERIFY_EMAIL_DATA + TEST_EMAIL),
            anyString(),
            eq(3L),
            eq(TimeUnit.MINUTES)
        );
    }

    @Test
    void testRegisterEmailVerifyCode_RateLimit() {
        // Mock流量限制检查不通过
        when(flowUtils.checkEmailVerifyLimit(eq(TEST_IP), eq(TEST_VERIFY_LIMIT)))
                .thenReturn(false);

        // 执行测试
        Result<Void> result = accountService.registerEmailVerifyCode("register", TEST_EMAIL, TEST_IP);

        // 验证结果：应该返回失败
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());

        // 验证邮件未被发送
        verify(emailService, never()).sendVerifyCode(anyString(), anyString(), anyString());
    }

    @Test
    void testRegisterEmailAccount_CodeExpired() {
        // 准备测试数据
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setUsername(TEST_USERNAME);
        registerVO.setEmail(TEST_EMAIL);
        registerVO.setPassword(TEST_PASSWORD);
        registerVO.setCode(TEST_CODE);

        // Mock验证码不存在（已过期）
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + TEST_EMAIL))
                .thenReturn(null);

        // 执行测试
        Result<Void> result = accountService.registerEmailAccount(registerVO);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.AUTH_CODE_ERROR.getCode(), result.getCode());

        // 验证Mapper的insert未被调用
        verify(accountMapper, never()).insert(any(Account.class));
    }

    @Test
    void testRegisterEmailAccount_CodeMismatch() {
        // 准备测试数据
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setUsername(TEST_USERNAME);
        registerVO.setEmail(TEST_EMAIL);
        registerVO.setPassword(TEST_PASSWORD);
        registerVO.setCode("wrongcode");

        // Mock验证码存在但不匹配
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + TEST_EMAIL))
                .thenReturn(TEST_CODE);

        // 执行测试
        Result<Void> result = accountService.registerEmailAccount(registerVO);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.AUTH_CODE_ERROR.getCode(), result.getCode());

        // 验证Mapper的insert未被调用
        verify(accountMapper, never()).insert(any(Account.class));
    }

    @Test
    void testRegisterEmailAccount_EmailExists() {
        // 准备测试数据
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setUsername(TEST_USERNAME);
        registerVO.setEmail(TEST_EMAIL);
        registerVO.setPassword(TEST_PASSWORD);
        registerVO.setCode(TEST_CODE);

        // Mock验证码正确
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + TEST_EMAIL))
                .thenReturn(TEST_CODE);

        // Mock邮箱已存在（覆盖setUp中的默认Mock）
        when(accountMapper.exists(any(QueryWrapper.class)))
                .thenReturn(true);

        // 执行测试
        Result<Void> result = accountService.registerEmailAccount(registerVO);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_HAS_EXISTED.getCode(), result.getCode());

        // 验证Mapper的insert未被调用
        verify(accountMapper, never()).insert(any(Account.class));
    }

    @Test
    void testRegisterEmailAccount_UsernameExists() {
        // 准备测试数据
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setUsername(TEST_USERNAME);
        registerVO.setEmail(TEST_EMAIL);
        registerVO.setPassword(TEST_PASSWORD);
        registerVO.setCode(TEST_CODE);

        // Mock验证码正确
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + TEST_EMAIL))
                .thenReturn(TEST_CODE);

        // Mock邮箱不存在，但用户名存在（覆盖setUp中的默认Mock）
        when(accountMapper.exists(any(QueryWrapper.class)))
                .thenReturn(false)  // 第一次检查邮箱（不存在）
                .thenReturn(true);  // 第二次检查用户名（存在）

        // 执行测试
        Result<Void> result = accountService.registerEmailAccount(registerVO);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_HAS_EXISTED.getCode(), result.getCode());

        // 验证Mapper的insert未被调用
        verify(accountMapper, never()).insert(any(Account.class));
    }

    @Test
    void testChangePassword_WrongOldPassword() {
        // 准备测试数据
        ChangePasswordVO changePasswordVO = new ChangePasswordVO();
        changePasswordVO.setPassword("WrongPassword");
        changePasswordVO.setNewPassword("NewPassword123");

        Account mockAccount = createMockAccount();

        // Mock账户查询链 - 账户必须存在才能验证密码
        QueryChainWrapper<Account> queryChain = mock(QueryChainWrapper.class);
        when(accountService.query()).thenReturn(queryChain);
        when(queryChain.eq(eq("id"), eq(TEST_USER_ID))).thenReturn(queryChain);
        when(queryChain.one()).thenReturn(mockAccount); // 返回存在的账户

        // Mock旧密码错误
        when(passwordService.matches("WrongPassword", TEST_PASSWORD_ENCODED))
                .thenReturn(false);

        // 执行测试
        Result<Void> result = accountService.changePassword(TEST_USER_ID, changePasswordVO);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_LOGIN_ERROR.getCode(), result.getCode());

        // 验证Mapper的update未被调用
        verify(accountMapper, never()).update(any(), any(LambdaQueryWrapper.class));
    }

    @Test
    void testModifyEmail_AlreadyUsed() {
        // 准备测试数据
        ModifyEmailVO modifyEmailVO = new ModifyEmailVO();
        modifyEmailVO.setEmail("newemail@example.com");
        modifyEmailVO.setCode(TEST_CODE);

        Account otherAccount = createMockAccount();
        otherAccount.setId(999L); // 不同的用户ID

        // Mock验证码正确
        when(valueOperations.get(Const.VERIFY_EMAIL_DATA + "newemail@example.com"))
                .thenReturn(TEST_CODE);

        // Mock新邮箱已被其他用户使用 - 使用正确的spy Mock方式
        doReturn(otherAccount).when(accountService).findAccountByNameOrEmail("newemail@example.com");

        // 执行测试
        Result<Void> result = accountService.modifyEmail(TEST_USER_ID, modifyEmailVO);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_HAS_EXISTED.getCode(), result.getCode());

        // 验证Mapper的update未被调用
        verify(accountMapper, never()).update(any(), any(LambdaQueryWrapper.class));
        verify(stringRedisTemplate).delete(Const.VERIFY_EMAIL_DATA + "newemail@example.com");
    }

    // 辅助方法：创建模拟的账户
    private Account createMockAccount() {
        Account account = new Account();
        account.setId(TEST_USER_ID);
        account.setUsername(TEST_USERNAME);
        account.setEmail(TEST_EMAIL);
        account.setPassword(TEST_PASSWORD_ENCODED);
        account.setRole(Const.ROLE_DEFAULT);
        account.setDeleted(0);
        return account;
    }
}
