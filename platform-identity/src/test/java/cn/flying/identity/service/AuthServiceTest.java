package cn.flying.identity.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.dto.Account;
import cn.flying.identity.exception.BusinessException;
import cn.flying.identity.service.impl.AuthServiceImpl;
import cn.flying.identity.util.FlowUtils;
import cn.flying.identity.vo.AccountVO;
import cn.flying.identity.vo.LoginStatusVO;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 认证服务单元测试
 * 覆盖登录、注销、账号管理与登录态查询
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

    @Mock
    private FlowUtils flowUtils;

    @Mock
    private ApplicationProperties applicationProperties;

    private ApplicationProperties.LoginSecurity loginSecurity;

    private static final Long TEST_USER_ID = 123L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PASSWORD = "Test123456";
    private static final String TEST_PASSWORD_ENCODED = "$2a$10$hashedpassword";
    private static final String TEST_TOKEN = "test-token-value-123";

    @BeforeEach
    void setUp() {
        loginSecurity = new ApplicationProperties.LoginSecurity();
        when(applicationProperties.getLoginSecurity()).thenReturn(loginSecurity);
        when(flowUtils.recordLoginFailure(anyString(), anyInt())).thenReturn(1);
    }

    @Test
    void testLogin_Success() {
        Account mockAccount = createMockAccount();
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME)).thenReturn(mockAccount);
        when(accountService.matchesPassword(TEST_PASSWORD, TEST_PASSWORD_ENCODED)).thenReturn(true);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            SaSession mockSession = mock(SaSession.class);
            when(mockSession.set(anyString(), any())).thenReturn(mockSession);

            stpUtil.when(() -> StpUtil.login(TEST_USER_ID)).then(invocation -> null);
            stpUtil.when(StpUtil::getSession).thenReturn(mockSession);
            stpUtil.when(StpUtil::getTokenValue).thenReturn(TEST_TOKEN);

            String token = authService.login(TEST_USERNAME, TEST_PASSWORD);

            assertEquals(TEST_TOKEN, token);
            stpUtil.verify(() -> StpUtil.login(eq(TEST_USER_ID)));
        }
    }

    @Test
    void testLogin_UserNotExist() {
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(TEST_USERNAME, TEST_PASSWORD));
        assertEquals(ResultEnum.USER_LOGIN_ERROR.getCode(), ex.getCode());
        verify(accountService).findAccountByNameOrEmail(TEST_USERNAME);
        verify(flowUtils, atLeastOnce()).recordLoginFailure(anyString(), anyInt());
    }

    @Test
    void testLogin_WrongPassword() {
        Account mockAccount = createMockAccount();
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME)).thenReturn(mockAccount);
        when(accountService.matchesPassword(TEST_PASSWORD, TEST_PASSWORD_ENCODED)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(TEST_USERNAME, TEST_PASSWORD));
        assertEquals(ResultEnum.USER_LOGIN_ERROR.getCode(), ex.getCode());
        verify(accountService).matchesPassword(eq(TEST_PASSWORD), eq(TEST_PASSWORD_ENCODED));
        verify(flowUtils, atLeastOnce()).recordLoginFailure(anyString(), anyInt());
    }

    @Test
    void testLogin_TooManyAttemptsForAccount() {
        loginSecurity.setMaxAttemptsPerAccount(3);
        loginSecurity.setLockMinutes(5);
        when(flowUtils.getLoginFailureCount(anyString())).thenReturn(3, 0, 0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(TEST_USERNAME, TEST_PASSWORD));

        assertEquals(ResultEnum.PERMISSION_LIMIT.getCode(), ex.getCode());
        verify(accountService, never()).findAccountByNameOrEmail(anyString());
        verify(flowUtils, never()).recordLoginFailure(anyString(), anyInt());
    }

    @Test
    void testLogin_TooManyAttemptsForIp() {
        loginSecurity.setMaxAttemptsPerIp(2);
        loginSecurity.setLockMinutes(5);
        when(flowUtils.getLoginFailureCount(argThat(id -> id.startsWith("acct:")))).thenReturn(0);
        when(flowUtils.getLoginFailureCount(argThat(id -> id.startsWith("ip:")))).thenReturn(2);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.login(TEST_USERNAME, TEST_PASSWORD));

        assertEquals(ResultEnum.PERMISSION_LIMIT.getCode(), ex.getCode());
        verify(accountService, never()).findAccountByNameOrEmail(anyString());
        verify(flowUtils, never()).recordLoginFailure(anyString(), anyInt());
    }

    @Test
    void testLogout_WhenLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            SaSession mockSession = mock(SaSession.class);
            when(mockSession.get("username")).thenReturn(TEST_USERNAME);

            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mockSession);
            stpUtil.when(StpUtil::getTokenValue).thenReturn(TEST_TOKEN);
            stpUtil.when(StpUtil::logout).then(invocation -> null);

            authService.logout();

            verify(jwtBlacklistService).blacklistToken(TEST_TOKEN, -1L);
            stpUtil.verify(StpUtil::logout);
        }
    }

    @Test
    void testLogout_WhenNotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            authService.logout();

            verifyNoInteractions(jwtBlacklistService);
            stpUtil.verify(StpUtil::isLogin);
        }
    }

    @Test
    void testRegister_Success() {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setUsername(TEST_USERNAME);
        vo.setEmail(TEST_EMAIL);
        vo.setPassword(TEST_PASSWORD);
        vo.setCode("123456");

        when(accountService.registerEmailAccount(vo)).thenReturn(Result.success(null));

        assertDoesNotThrow(() -> authService.register(vo));
        verify(accountService).registerEmailAccount(vo);
    }

    @Test
    void testRegister_Failure() {
        EmailRegisterVO vo = new EmailRegisterVO();
        vo.setUsername(TEST_USERNAME);
        vo.setEmail(TEST_EMAIL);
        vo.setPassword(TEST_PASSWORD);
        vo.setCode("123456");

        when(accountService.registerEmailAccount(vo))
                .thenReturn(Result.error(ResultEnum.USER_HAS_EXISTED));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.register(vo));
        assertEquals(ResultEnum.USER_HAS_EXISTED.getCode(), ex.getCode());
    }

    @Test
    void testAskVerifyCode_Success() {
        when(accountService.registerEmailVerifyCode(eq("register"), eq(TEST_EMAIL), anyString()))
                .thenReturn(Result.success(null));

        assertDoesNotThrow(() -> authService.askVerifyCode(TEST_EMAIL, "register"));
        verify(accountService).registerEmailVerifyCode(eq("register"), eq(TEST_EMAIL), anyString());
    }

    @Test
    void testAskVerifyCode_Failure() {
        when(accountService.registerEmailVerifyCode(eq("reset"), eq(TEST_EMAIL), anyString()))
                .thenReturn(Result.error(ResultEnum.AUTH_CODE_ERROR));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authService.askVerifyCode(TEST_EMAIL, "reset"));
        assertEquals(ResultEnum.AUTH_CODE_ERROR.getCode(), ex.getCode());
    }

    @Test
    void testResetConfirm_Success() {
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail(TEST_EMAIL);
        vo.setCode("123456");
        vo.setPassword("NewPass123");
        when(accountService.resetEmailAccountPassword(vo)).thenReturn(Result.success(null));

        assertDoesNotThrow(() -> authService.resetConfirm(vo));
        verify(accountService).resetEmailAccountPassword(vo);
    }

    @Test
    void testResetConfirm_Failure() {
        EmailResetVO vo = new EmailResetVO();
        vo.setEmail(TEST_EMAIL);
        vo.setCode("123456");
        vo.setPassword("NewPass123");
        when(accountService.resetEmailAccountPassword(vo))
                .thenReturn(Result.error(ResultEnum.AUTH_CODE_ERROR));

        BusinessException ex = assertThrows(BusinessException.class, () -> authService.resetConfirm(vo));
        assertEquals(ResultEnum.AUTH_CODE_ERROR.getCode(), ex.getCode());
    }

    @Test
    void testChangePassword_Success() {
        ChangePasswordVO changePasswordVO = new ChangePasswordVO();
        changePasswordVO.setPassword("OldPassword123");
        changePasswordVO.setNewPassword("NewPassword123");

        when(accountService.changePassword(eq(TEST_USER_ID), any(ChangePasswordVO.class)))
                .thenReturn(Result.success(null));

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);

            assertDoesNotThrow(() -> authService.changePassword(changePasswordVO));
            verify(accountService).changePassword(eq(TEST_USER_ID), eq(changePasswordVO));
        }
    }

    @Test
    void testChangePassword_NotLoggedIn() {
        ChangePasswordVO changePasswordVO = new ChangePasswordVO();
        changePasswordVO.setPassword("OldPassword123");
        changePasswordVO.setNewPassword("NewPassword123");

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> authService.changePassword(changePasswordVO));
            assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(), ex.getCode());
            verify(accountService, never()).changePassword(anyLong(), any(ChangePasswordVO.class));
        }
    }

    @Test
    void testGetUserInfo_Success() {
        Account mockAccount = createMockAccount();
        when(accountService.findAccountById(TEST_USER_ID)).thenReturn(mockAccount);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);

            AccountVO vo = authService.getUserInfo();
            assertEquals(TEST_USERNAME, vo.getUsername());
            assertEquals(TEST_EMAIL, vo.getEmail());
        }
    }

    @Test
    void testGetUserInfo_NotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class, () -> authService.getUserInfo());
            assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(), ex.getCode());
        }
    }

    @Test
    void testFindUserWithMasking_AdminAccess() {
        Account mockAccount = createMockAccount();
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME)).thenReturn(mockAccount);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            SaSession mockSession = mock(SaSession.class);
            when(mockSession.get("role")).thenReturn("admin");
            when(mockSession.get("username")).thenReturn("admin");

            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mockSession);

            AccountVO vo = authService.findUserWithMasking(TEST_USERNAME);
            assertTrue(vo.getEmail().contains("***"));
            assertEquals(TEST_USERNAME, vo.getUsername());
        }
    }

    @Test
    void testFindUserWithMasking_NonAdminDenied() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            SaSession mockSession = mock(SaSession.class);
            when(mockSession.get("role")).thenReturn("user");
            when(mockSession.get("username")).thenReturn(TEST_USERNAME);

            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getSession).thenReturn(mockSession);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> authService.findUserWithMasking(TEST_USERNAME));
            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getCode());
            verify(accountService, never()).findAccountByNameOrEmail(anyString());
        }
    }

    @Test
    void testCheckLoginStatus_WhenLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);

            LoginStatusVO status = authService.checkLoginStatus();
            assertTrue(status.isLoggedIn());
            assertEquals(TEST_USER_ID, status.getUserId());
        }
    }

    @Test
    void testCheckLoginStatus_WhenNotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            LoginStatusVO status = authService.checkLoginStatus();
            assertFalse(status.isLoggedIn());
            assertNull(status.getUserId());
        }
    }

    @Test
    void testGetTokenInfo_NotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            BusinessException ex = assertThrows(BusinessException.class, () -> authService.getTokenInfo());
            assertEquals(ResultEnum.USER_NOT_LOGGED_IN.getCode(), ex.getCode());
        }
    }

    @Test
    void testGetTokenInfo_Success() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            SaTokenInfo info = mock(SaTokenInfo.class);
            when(info.getTokenName()).thenReturn("satoken");
            when(info.getTokenValue()).thenReturn(TEST_TOKEN);
            when(info.getLoginId()).thenReturn(TEST_USER_ID);
            when(info.getLoginType()).thenReturn("default");
            when(info.getIsLogin()).thenReturn(true);
            when(info.getTokenTimeout()).thenReturn(3600L);
            when(info.getSessionTimeout()).thenReturn(3600L);
            when(info.getTokenSessionTimeout()).thenReturn(3600L);

            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getTokenInfo).thenReturn(info);

            Map<String, Object> tokenInfo = authService.getTokenInfo();
            assertEquals(TEST_TOKEN, tokenInfo.get("tokenValue"));
            assertEquals(TEST_USER_ID, tokenInfo.get("loginId"));
        }
    }

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
