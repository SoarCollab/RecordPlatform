package cn.flying.identity.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.service.impl.AuthServiceImpl;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.request.ChangePasswordVO;
import cn.flying.identity.vo.request.EmailRegisterVO;
import cn.flying.identity.vo.request.EmailResetVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 认证服务单元测试
 * 测试范围：登录、注销、注册、密码管理、用户信息获取
 *
 * @author 王贝强
 * @create 2025-01-13
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @InjectMocks
    private AuthServiceImpl authService;

    @Mock
    private AccountService accountService;

    @Mock
    private JwtBlacklistService jwtBlacklistService;

    // 测试数据常量
    private static final Long TEST_USER_ID = 123L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Test123456";
    private static final String TEST_PASSWORD_ENCODED = "$2a$10$hashedpassword";
    private static final String TEST_TOKEN = "test-token-value-123";

    @BeforeEach
    void setUp() {
        // 预配置通用Mock行为可在此处设置
    }

    @Test
    void testLogin_Success() {
        // 准备测试数据
        Account mockAccount = createMockAccount();
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME))
                .thenReturn(mockAccount);
        when(accountService.matchesPassword(TEST_PASSWORD, TEST_PASSWORD_ENCODED))
                .thenReturn(true);

        // 模拟Sa-Token登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            // Mock登录操作
            stpUtil.when(() -> StpUtil.login(TEST_USER_ID)).then(invocation -> null);
            stpUtil.when(StpUtil::getSession).thenReturn(mock(cn.dev33.satoken.session.SaSession.class));
            stpUtil.when(StpUtil::getTokenValue).thenReturn(TEST_TOKEN);

            // 执行测试
            Result<String> result = authService.login(TEST_USERNAME, TEST_PASSWORD);

            // 验证结果
            assertTrue(result.isSuccess());
            assertEquals(TEST_TOKEN, result.getData());

            // 验证StpUtil.login被调用
            stpUtil.verify(() -> StpUtil.login(eq(TEST_USER_ID)));
        }
    }

    @Test
    void testLogin_UserNotExist() {
        // 模拟用户不存在
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME))
                .thenReturn(null);

        // 执行测试
        Result<String> result = authService.login(TEST_USERNAME, TEST_PASSWORD);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_NOT_EXIST.getCode(), result.getCode());

        // 验证accountService被调用
        verify(accountService).findAccountByNameOrEmail(eq(TEST_USERNAME));
    }

    @Test
    void testLogin_WrongPassword() {
        // 准备测试数据
        Account mockAccount = createMockAccount();
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME))
                .thenReturn(mockAccount);
        when(accountService.matchesPassword(TEST_PASSWORD, TEST_PASSWORD_ENCODED))
                .thenReturn(false);

        // 执行测试
        Result<String> result = authService.login(TEST_USERNAME, TEST_PASSWORD);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_LOGIN_ERROR.getCode(), result.getCode());

        // 验证密码匹配被调用
        verify(accountService).matchesPassword(eq(TEST_PASSWORD), eq(TEST_PASSWORD_ENCODED));
    }

    @Test
    void testLogout_Success() {
        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mock(cn.dev33.satoken.session.SaSession.class));
            stpUtil.when(StpUtil::getTokenValue).thenReturn(TEST_TOKEN);
            stpUtil.when(StpUtil::logout).then(invocation -> null);

            // 执行测试
            Result<Void> result = authService.logout();

            // 验证结果
            assertTrue(result.isSuccess());

            // 验证Token被加入黑名单
            verify(jwtBlacklistService).blacklistToken(eq(TEST_TOKEN), eq(-1L));

            // 验证StpUtil.logout被调用
            stpUtil.verify(StpUtil::logout);
        }
    }

    @Test
    void testLogout_WithTokenBlacklist() {
        // 模拟用户已登录，验证Token被正确加入黑名单
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mock(cn.dev33.satoken.session.SaSession.class));
            stpUtil.when(StpUtil::getTokenValue).thenReturn(TEST_TOKEN);
            stpUtil.when(StpUtil::logout).then(invocation -> null);

            // 执行测试
            Result<Void> result = authService.logout();

            // 验证结果
            assertTrue(result.isSuccess());

            // 验证Token被加入黑名单，ttl为-1表示使用Token自身的过期时间
            verify(jwtBlacklistService, times(1)).blacklistToken(eq(TEST_TOKEN), eq(-1L));
        }
    }

    @Test
    void testRegister_Success() {
        // 准备测试数据
        EmailRegisterVO registerVO = new EmailRegisterVO();
        registerVO.setUsername(TEST_USERNAME);
        registerVO.setEmail(TEST_EMAIL);
        registerVO.setPassword(TEST_PASSWORD);
        registerVO.setCode("123456");

        // Mock AccountService的注册方法
        when(accountService.registerEmailAccount(any(EmailRegisterVO.class)))
                .thenReturn(Result.success(null));

        // 执行测试
        Result<Void> result = authService.register(registerVO);

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证AccountService的registerEmailAccount被调用
        verify(accountService).registerEmailAccount(eq(registerVO));
    }

    @Test
    void testGetUserInfo_Success() {
        // 准备测试数据
        Account mockAccount = createMockAccount();
        when(accountService.findAccountById(TEST_USER_ID))
                .thenReturn(mockAccount);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);

            // 执行测试
            Result<AccountVO> result = authService.getUserInfo();

            // 验证结果
            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
            assertEquals(TEST_USERNAME, result.getData().getUsername());
            assertEquals(TEST_EMAIL, result.getData().getEmail());
        }
    }

    @Test
    void testGetUserInfo_NotLoggedIn() {
        // 模拟用户未登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            Result<AccountVO> result = authService.getUserInfo();

            // 验证结果
            assertFalse(result.isSuccess());
            assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(), result.getCode());
        }
    }

    @Test
    void testChangePassword_Success() {
        // 准备测试数据
        ChangePasswordVO changePasswordVO = new ChangePasswordVO();
        changePasswordVO.setPassword("OldPassword123");
        changePasswordVO.setNewPassword("NewPassword123");

        // Mock AccountService的修改密码方法
        when(accountService.changePassword(eq(TEST_USER_ID), any(ChangePasswordVO.class)))
                .thenReturn(Result.success(null));

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);

            // 执行测试
            Result<Void> result = authService.changePassword(changePasswordVO);

            // 验证结果
            assertTrue(result.isSuccess());

            // 验证AccountService的changePassword被调用
            verify(accountService).changePassword(eq(TEST_USER_ID), eq(changePasswordVO));
        }
    }

    @Test
    void testChangePassword_NotLoggedIn() {
        // 准备测试数据
        ChangePasswordVO changePasswordVO = new ChangePasswordVO();
        changePasswordVO.setPassword("OldPassword123");
        changePasswordVO.setNewPassword("NewPassword123");

        // 模拟用户未登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            Result<Void> result = authService.changePassword(changePasswordVO);

            // 验证结果
            assertFalse(result.isSuccess());
            assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(), result.getCode());

            // 验证AccountService的changePassword未被调用
            verify(accountService, never()).changePassword(anyLong(), any(ChangePasswordVO.class));
        }
    }

    @Test
    void testFindUserWithMasking_AdminAccess() {
        // 准备测试数据
        Account mockAccount = createMockAccount();
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME))
                .thenReturn(mockAccount);

        // 模拟管理员已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            cn.dev33.satoken.session.SaSession mockSession = mock(cn.dev33.satoken.session.SaSession.class);
            when(mockSession.get("role")).thenReturn("admin");
            when(mockSession.get("username")).thenReturn("admin");

            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mockSession);

            // 执行测试
            Result<AccountVO> result = authService.findUserWithMasking(TEST_USERNAME);

            // 验证结果
            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
            assertEquals(TEST_USERNAME, result.getData().getUsername());

            // 验证邮箱已脱敏
            String maskedEmail = result.getData().getEmail();
            assertTrue(maskedEmail.contains("***"));
            assertTrue(maskedEmail.contains("@example.com"));
        }
    }

    @Test
    void testFindUserWithMasking_NonAdminDenied() {
        // 模拟普通用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            cn.dev33.satoken.session.SaSession mockSession = mock(cn.dev33.satoken.session.SaSession.class);
            when(mockSession.get("role")).thenReturn("user");
            when(mockSession.get("username")).thenReturn(TEST_USERNAME);

            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mockSession);

            // 执行测试
            Result<AccountVO> result = authService.findUserWithMasking(TEST_USERNAME);

            // 验证结果：非管理员应该被拒绝访问
            assertFalse(result.isSuccess());
            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), result.getCode());

            // 验证AccountService的findAccountByNameOrEmail未被调用
            verify(accountService, never()).findAccountByNameOrEmail(anyString());
        }
    }

    // 辅助方法：创建模拟的账户
    private Account createMockAccount() {
        Account account = new Account();
        account.setId(TEST_USER_ID);
        account.setUsername(TEST_USERNAME);
        account.setEmail(TEST_EMAIL);
        account.setPassword(TEST_PASSWORD_ENCODED);
        account.setRole("USER");
        account.setDeleted(0);
        return account;
    }
}
