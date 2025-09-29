package cn.flying.identity.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.util.IdUtils;
import cn.flying.identity.service.impl.SSOServiceImpl;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SSO服务单元测试
 *
 * @author 王贝强
 * @create 2025-01-12
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SSOServiceTest {

    @InjectMocks
    private SSOServiceImpl ssoService;

    @Mock
    private AccountService accountService;

    @Mock
    private OAuthService oauthService;

    @Mock
    private JwtBlacklistService jwtBlacklistService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private static final Long TEST_CLIENT_ID = 123L;
    private static final String TEST_CLIENT_ID_STR = "123";
    private static final String TEST_REDIRECT_URI = "https://example.com/callback";
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_PASSWORD = "Test123456";
    private static final Long TEST_USER_ID = 1L;

    @BeforeEach
    void setUp() {
        // 设置Redis模板操作
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
        when(setOperations.add(anyString(), anyString())).thenReturn(1L);
        when(redisTemplate.expire(anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        // 注入配置值
        ReflectionTestUtils.setField(ssoService, "ssoTokenPrefix", "sso:token:");
        ReflectionTestUtils.setField(ssoService, "ssoClientPrefix", "sso:client:");
        ReflectionTestUtils.setField(ssoService, "ssoUserPrefix", "sso:user:");
        ReflectionTestUtils.setField(ssoService, "ssoTokenTimeout", 7200);
    }

    @Test
    void testGetSSOLoginInfo_UserNotLoggedIn() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthService.getClient(TEST_CLIENT_ID_STR))
                .thenReturn(Result.success(mockClient));

        // 模拟用户未登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            Result<Map<String, Object>> result = ssoService.getSSOLoginInfo(
                    TEST_CLIENT_ID_STR, TEST_REDIRECT_URI, "read", "state123"
            );

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("need_login", data.get("status"));
            assertEquals(TEST_CLIENT_ID_STR, data.get("client_id"));
            assertEquals(TEST_REDIRECT_URI, data.get("redirect_uri"));
        }
    }

    @Test
    void testGetSSOLoginInfo_UserLoggedIn() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        Account mockAccount = createMockAccount();

        when(oauthService.getClient(TEST_CLIENT_ID_STR))
                .thenReturn(Result.success(mockClient));
        when(accountService.findAccountById(TEST_USER_ID))
                .thenReturn(mockAccount);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);
            idUtilsMock.when(() -> IdUtils.nextIdWithPrefix("SSO")).thenReturn("SSO_TOKEN");

            // 添加generateSSOToken和recordClientLogin所需的Mock
            // generateSSOToken需要的Redis操作
            doNothing().when(valueOperations).set(
                eq("sso:token:SSO_TOKEN"),
                anyString(),
                eq(7200L),
                eq(TimeUnit.SECONDS)
            );

            // recordClientLogin需要的Redis操作
            doNothing().when(valueOperations).set(
                eq("sso:client:" + TEST_USER_ID + ":" + TEST_CLIENT_ID_STR),
                anyString(),
                eq(7200L),
                eq(TimeUnit.SECONDS)
            );
            when(setOperations.add(eq("sso:user:" + TEST_USER_ID), eq(TEST_CLIENT_ID_STR))).thenReturn(1L);
            when(redisTemplate.expire(eq("sso:user:" + TEST_USER_ID), eq(7200L), eq(TimeUnit.SECONDS))).thenReturn(true);

            // 执行测试
            Result<Map<String, Object>> result = ssoService.getSSOLoginInfo(
                    TEST_CLIENT_ID_STR, TEST_REDIRECT_URI, "read", "state123"
            );

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("logged_in", data.get("status"));
            assertEquals(TEST_USER_ID, data.get("user_id"));
            assertEquals(TEST_USERNAME, data.get("username"));
            assertNotNull(data.get("sso_token"));
        }
    }

    @Test
    void testProcessSSOLogin_InvalidCredentials() {
        // 准备测试数据
        when(accountService.findAccountByNameOrEmail(TEST_USERNAME))
                .thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = ssoService.processSSOLogin(
                TEST_USERNAME, TEST_PASSWORD, TEST_CLIENT_ID_STR,
                TEST_REDIRECT_URI, "read", "state123"
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.USER_NOT_EXIST.getCode(), result.getCode());
    }

    @Test
    void testProcessSSOLogin_Success() {
        // 准备测试数据
        Account mockAccount = createMockAccount();
        OAuthClient mockClient = createMockOAuthClient();

        when(accountService.findAccountByNameOrEmail(TEST_USERNAME))
                .thenReturn(mockAccount);
        when(accountService.matchesPassword(TEST_PASSWORD, mockAccount.getPassword()))
                .thenReturn(true);
        when(oauthService.getClient(TEST_CLIENT_ID_STR))
                .thenReturn(Result.success(mockClient));

        // 模拟StpUtil登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class);
             MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
            stpUtil.when(() -> StpUtil.login(anyLong())).then(invocation -> null);
            stpUtil.when(StpUtil::getTokenValue).thenReturn("test-token-123");
            stpUtil.when(StpUtil::getSession).thenReturn(mockSaSession());
            idUtilsMock.when(() -> IdUtils.nextIdWithPrefix("SSO")).thenReturn("SSO_TOKEN");

            // 添加generateSSOToken所需的Redis操作Mock
            doNothing().when(valueOperations).set(
                eq("sso:token:SSO_TOKEN"),
                anyString(),
                eq(7200L),
                eq(TimeUnit.SECONDS)
            );

            // 添加recordClientLogin所需的Redis操作Mock
            doNothing().when(valueOperations).set(
                eq("sso:client:" + TEST_USER_ID + ":" + TEST_CLIENT_ID_STR),
                anyString(),
                eq(7200L),
                eq(TimeUnit.SECONDS)
            );
            when(setOperations.add(eq("sso:user:" + TEST_USER_ID), eq(TEST_CLIENT_ID_STR))).thenReturn(1L);
            when(redisTemplate.expire(eq("sso:user:" + TEST_USER_ID), eq(7200L), eq(TimeUnit.SECONDS))).thenReturn(true);

            // 执行测试
            Result<Map<String, Object>> result = ssoService.processSSOLogin(
                    TEST_USERNAME, TEST_PASSWORD, TEST_CLIENT_ID_STR,
                    TEST_REDIRECT_URI, "read", "state123"
            );

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("success", data.get("status"));
            assertEquals(TEST_USER_ID, data.get("user_id"));
            assertEquals(TEST_USERNAME, data.get("username"));
            assertNotNull(data.get("sso_token"));
        }
    }

    @Test
    void testValidateSSOToken_Valid() {
        // 准备测试数据
        String testToken = "SSO_test_token_123";
        String userInfo = TEST_USER_ID + ":" + TEST_CLIENT_ID_STR;

        when(valueOperations.get("sso:token:" + testToken))
                .thenReturn(userInfo);
        when(redisTemplate.getExpire("sso:token:" + testToken, TimeUnit.SECONDS))
                .thenReturn(3600L);

        // 执行测试
        Result<Map<String, Object>> result = ssoService.validateSSOToken(testToken);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertTrue((Boolean) data.get("valid"));
        assertEquals(TEST_USER_ID, data.get("user_id"));
        assertEquals(TEST_CLIENT_ID_STR, data.get("client_id"));
        assertEquals(3600L, data.get("expires_in"));
    }

    @Test
    void testValidateSSOToken_Invalid() {
        // 准备测试数据
        String testToken = "invalid_token";
        when(valueOperations.get("sso:token:" + testToken))
                .thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = ssoService.validateSSOToken(testToken);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertFalse((Boolean) data.get("valid"));
    }

    @Test
    void testSSOLogout_GlobalLogout() {
        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);
            stpUtil.when(StpUtil::getTokenValue).thenReturn("test-token");
            stpUtil.when(StpUtil::logout).then(invocation -> null);

            // 执行测试
            Result<Map<String, Object>> result = ssoService.ssoLogout(TEST_REDIRECT_URI, null);

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("global_logout_success", data.get("status"));
            assertEquals(TEST_USER_ID, data.get("user_id"));

            // 验证调用
            verify(jwtBlacklistService).blacklistToken(eq("test-token"), eq(-1L));
            stpUtil.verify(StpUtil::logout);
        }
    }

    @Test
    void testGetSSOUserInfo_Success() {
        // 准备测试数据
        String testToken = "SSO_test_token_123";
        String userInfo = TEST_USER_ID + ":" + TEST_CLIENT_ID_STR;
        Account mockAccount = createMockAccount();

        when(valueOperations.get("sso:token:" + testToken))
                .thenReturn(userInfo);
        when(accountService.findAccountById(TEST_USER_ID))
                .thenReturn(mockAccount);

        // 执行测试
        Result<Map<String, Object>> result = ssoService.getSSOUserInfo(testToken);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(TEST_USER_ID, data.get("user_id"));
        assertEquals(TEST_USERNAME, data.get("username"));
        assertEquals("test@example.com", data.get("email"));
        assertEquals("USER", data.get("role"));
        assertEquals(TEST_CLIENT_ID_STR, data.get("client_id"));
    }

    @Test
    void testGetSSOLoginInfo_InvalidClient() {
        // Mock客户端不存在
        when(oauthService.getClient(TEST_CLIENT_ID_STR))
                .thenReturn(Result.error(ResultEnum.RESULT_DATA_NONE, null));

        // 执行测试
        Result<Map<String, Object>> result = ssoService.getSSOLoginInfo(
                TEST_CLIENT_ID_STR, TEST_REDIRECT_URI, "read", "state123"
        );

        // 验证结果：应该返回错误
        assertFalse(result.isSuccess());

        // 验证oauthService被调用
        verify(oauthService).getClient(eq(TEST_CLIENT_ID_STR));
    }

    @Test
    void testGetSSOLoginInfo_DisabledClient() {
        // 准备测试数据：禁用的客户端
        OAuthClient disabledClient = createMockOAuthClient();
        disabledClient.setStatus(0); // 状态为0表示禁用

        when(oauthService.getClient(TEST_CLIENT_ID_STR))
                .thenReturn(Result.success(disabledClient));

        // 执行测试
        Result<Map<String, Object>> result = ssoService.getSSOLoginInfo(
                TEST_CLIENT_ID_STR, TEST_REDIRECT_URI, "read", "state123"
        );

        // 验证结果：应该返回错误（客户端已禁用）
        assertFalse(result.isSuccess());
    }

    @Test
    void testValidateSSOToken_Expired() {
        // 准备测试数据：过期的Token
        String testToken = "SSO_expired_token";
        String userInfo = TEST_USER_ID + ":" + TEST_CLIENT_ID_STR;

        when(valueOperations.get("sso:token:" + testToken))
                .thenReturn(userInfo);
        // Mock过期时间为-1（已过期）或0
        when(redisTemplate.getExpire("sso:token:" + testToken, TimeUnit.SECONDS))
                .thenReturn(-1L);

        // 执行测试
        Result<Map<String, Object>> result = ssoService.validateSSOToken(testToken);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        // 过期的Token应该返回有效但过期时间为负
        assertTrue((Boolean) data.get("valid"));
        assertEquals(-1L, data.get("expires_in"));
    }

    @Test
    void testSSOLogout_ClientSpecificLogout() {
        // 准备测试数据
        String ssoToken = "SSO_test_token_456";

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);
            stpUtil.when(StpUtil::getTokenValue).thenReturn("test-token");

            // 执行测试：传入ssoToken进行特定客户端登出
            Result<Map<String, Object>> result = ssoService.ssoLogout(TEST_REDIRECT_URI, ssoToken);

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("client_logout_success", data.get("status"));

            // 验证SSO Token被删除（实际删除的key格式为sso:client:{userId}:{ssoToken}）
            verify(redisTemplate).delete(eq("sso:client:" + TEST_USER_ID + ":" + ssoToken));

            // 验证全局登出未被调用（因为是客户端特定登出）
            stpUtil.verify(StpUtil::logout, never());
        }
    }

    @Test
    void testGetSSOUserInfo_TokenNotFound() {
        // 准备测试数据：Token不存在
        String testToken = "SSO_nonexistent_token";

        when(valueOperations.get("sso:token:" + testToken))
                .thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = ssoService.getSSOUserInfo(testToken);

        // 验证结果：应该返回错误
        assertFalse(result.isSuccess());

        // 验证accountService未被调用（因为Token不存在）
        verify(accountService, never()).findAccountById(anyLong());
    }

    // 辅助方法：创建模拟的OAuth客户端
    private OAuthClient createMockOAuthClient() {
        OAuthClient client = new OAuthClient();
        client.setClientId(TEST_CLIENT_ID);
        client.setClientName("Test Client");
        client.setRedirectUris(TEST_REDIRECT_URI);
        client.setStatus(1);
        return client;
    }

    // 辅助方法：创建模拟的账户
    private Account createMockAccount() {
        Account account = new Account();
        account.setId(TEST_USER_ID);
        account.setUsername(TEST_USERNAME);
        account.setEmail("test@example.com");
        account.setPassword("$2a$10$hashedpassword");
        account.setRole("USER");
        account.setDeleted(0);
        return account;
    }

    private SaSession mockSaSession() {
        return mock(SaSession.class);
    }
}
