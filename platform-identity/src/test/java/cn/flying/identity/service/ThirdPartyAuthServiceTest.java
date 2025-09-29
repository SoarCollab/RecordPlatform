package cn.flying.identity.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.ThirdPartyAccount;
import cn.flying.identity.mapper.ThirdPartyAccountMapper;
import cn.flying.identity.service.impl.ThirdPartyAuthServiceImpl;
import cn.flying.identity.util.IdUtils;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 第三方认证服务测试类
 * 测试OAuth登录、账号绑定、令牌管理等功能
 *
 * @author flying
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThirdPartyAuthServiceTest {

    @InjectMocks
    private ThirdPartyAuthServiceImpl thirdPartyAuthService;

    @Mock
    private AccountService accountService;

    @Mock
    private ThirdPartyAccountMapper thirdPartyAccountMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    // 测试常量
    private static final Long TEST_USER_ID = 10001L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_PROVIDER = "github";
    private static final String TEST_REDIRECT_URI = "http://localhost:3000/callback";
    private static final String TEST_STATE = "STATE123456";
    private static final String TEST_CODE = "code123456";
    private static final String TEST_ACCESS_TOKEN = "access_token_123";
    private static final String TEST_REFRESH_TOKEN = "refresh_token_123";
    private static final String TEST_THIRD_PARTY_ID = "github_user_123";

    @BeforeEach
    void setUp() {
        // Mock Redis operations
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        // 注入OAuth配置
        ReflectionTestUtils.setField(thirdPartyAuthService, "githubClientId", "github_client_id");
        ReflectionTestUtils.setField(thirdPartyAuthService, "githubClientSecret", "github_client_secret");
        ReflectionTestUtils.setField(thirdPartyAuthService, "googleClientId", "google_client_id");
        ReflectionTestUtils.setField(thirdPartyAuthService, "googleClientSecret", "google_client_secret");
        ReflectionTestUtils.setField(thirdPartyAuthService, "wechatAppId", "wechat_app_id");
        ReflectionTestUtils.setField(thirdPartyAuthService, "wechatAppSecret", "wechat_app_secret");

        // 注入URL配置
        ReflectionTestUtils.setField(thirdPartyAuthService, "githubAuthUrl", "https://github.com/login/oauth/authorize");
        ReflectionTestUtils.setField(thirdPartyAuthService, "githubTokenUrl", "https://github.com/login/oauth/access_token");
        ReflectionTestUtils.setField(thirdPartyAuthService, "githubUserInfoUrl", "https://api.github.com/user");
    }

    @Test
    void testGetAuthorizationUrl_GitHub_Success() {
        // Mock IdUtils
        try (MockedStatic<IdUtils> idUtils = mockStatic(IdUtils.class)) {
            idUtils.when(() -> IdUtils.nextIdWithPrefix("STATE")).thenReturn(TEST_STATE);

            doNothing().when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

            // 执行测试
            Result<String> result = thirdPartyAuthService.getAuthorizationUrl("github", TEST_REDIRECT_URI, null);

            // 验证结果
            assertTrue(result.isSuccess());
            assertNotNull(result.getData());
            assertTrue(result.getData().contains("github.com"));
            assertTrue(result.getData().contains("client_id=github_client_id"));
            verify(valueOperations, times(1)).set(eq("third_party:state:" + TEST_STATE),
                    eq("github:" + TEST_REDIRECT_URI), eq(10L), eq(TimeUnit.MINUTES));
        }
    }

    @Test
    void testGetAuthorizationUrl_InvalidProvider() {
        // 执行测试
        Result<String> result = thirdPartyAuthService.getAuthorizationUrl("invalid", TEST_REDIRECT_URI, TEST_STATE);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void testHandleCallback_ExistingUser_Success() {
        // 准备测试数据
        when(valueOperations.get("third_party:state:" + TEST_STATE))
                .thenReturn("github:" + TEST_REDIRECT_URI);

        // Mock查找已绑定账号
        Account mockAccount = createMockAccount();
        ThirdPartyAccount mockThirdPartyAccount = createMockThirdPartyAccount();
        when(thirdPartyAccountMapper.findByProviderAndThirdPartyId(eq("github"), anyString()))
                .thenReturn(mockThirdPartyAccount);
        when(accountService.findAccountById(TEST_USER_ID)).thenReturn(mockAccount);

        // Mock Sa-Token 和 HTTP请求
        try (MockedStatic<HttpRequest> httpRequest = mockStatic(HttpRequest.class);
             MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            mockHttpTokenRequest(httpRequest);
            mockHttpUserInfoRequest(httpRequest);

            stpUtil.when(() -> StpUtil.login(anyLong())).then(invocation -> null);
            stpUtil.when(StpUtil::getTokenValue).thenReturn("sa_token_123");
            stpUtil.when(StpUtil::getSession).thenReturn(null);

            // 执行测试
            Result<Map<String, Object>> result = thirdPartyAuthService.handleCallback("github", TEST_CODE, TEST_STATE);

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("login_success", data.get("status"));
            assertEquals(TEST_USER_ID, data.get("user_id"));
            assertEquals(TEST_USERNAME, data.get("username"));
            verify(redisTemplate, times(1)).delete("third_party:state:" + TEST_STATE);
        }
    }

    @Test
    void testHandleCallback_InvalidState() {
        // 准备测试数据 - state不存在
        when(valueOperations.get("third_party:state:" + TEST_STATE)).thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = thirdPartyAuthService.handleCallback("github", TEST_CODE, TEST_STATE);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void testBindThirdPartyAccount_Success() {
        // 准备测试数据
        String bindKey = "third_party:bind:" + TEST_USER_ID + ":github";
        JSONObject bindData = JSONUtil.createObj()
                .set("thirdPartyId", TEST_THIRD_PARTY_ID)
                .set("accessToken", TEST_ACCESS_TOKEN)
                .set("refreshToken", TEST_REFRESH_TOKEN);
        when(valueOperations.get(bindKey)).thenReturn(bindData.toString());
        when(thirdPartyAccountMapper.findByUserIdAndProvider(TEST_USER_ID, "github")).thenReturn(null);
        when(thirdPartyAccountMapper.insert(any(ThirdPartyAccount.class))).thenReturn(1);

        // 执行测试
        Result<Void> result = thirdPartyAuthService.bindThirdPartyAccount(TEST_USER_ID, "github", TEST_CODE);

        // 验证结果
        assertTrue(result.isSuccess());
        verify(thirdPartyAccountMapper, times(1)).insert(any(ThirdPartyAccount.class));
        verify(redisTemplate, times(1)).delete(bindKey);
    }

    @Test
    void testUnbindThirdPartyAccount_Success() {
        // 准备测试数据
        ThirdPartyAccount mockThirdPartyAccount = createMockThirdPartyAccount();
        when(thirdPartyAccountMapper.findByUserIdAndProvider(TEST_USER_ID, "github"))
                .thenReturn(mockThirdPartyAccount);
        when(thirdPartyAccountMapper.deleteByUserIdAndProvider(TEST_USER_ID, "github")).thenReturn(1);

        // 执行测试
        Result<Void> result = thirdPartyAuthService.unbindThirdPartyAccount(TEST_USER_ID, "github");

        // 验证结果
        assertTrue(result.isSuccess());
        verify(thirdPartyAccountMapper, times(1)).deleteByUserIdAndProvider(TEST_USER_ID, "github");
    }

    @Test
    void testGetUserThirdPartyAccounts_Success() {
        // 准备测试数据
        List<ThirdPartyAccount> accounts = Arrays.asList(
                createMockThirdPartyAccount(),
                createMockThirdPartyAccount()
        );
        when(thirdPartyAccountMapper.findByUserId(TEST_USER_ID)).thenReturn(accounts);

        // 执行测试
        Result<Map<String, Object>> result = thirdPartyAuthService.getUserThirdPartyAccounts(TEST_USER_ID);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertEquals(TEST_USER_ID, data.get("user_id"));
        assertEquals(2, data.get("total"));
        assertNotNull(data.get("bindings"));
        List<Map<String, Object>> bindings = (List<Map<String, Object>>) data.get("bindings");
        assertEquals(2, bindings.size());
    }

    @Test
    void testGetSupportedProviders_Success() {
        // 执行测试
        Result<Map<String, Object>> result = thirdPartyAuthService.getSupportedProviders();

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> data = result.getData();
        assertNotNull(data.get("providers"));
        Map<String, Object> providers = (Map<String, Object>) data.get("providers");
        assertTrue(providers.containsKey("github"));
        assertTrue(providers.containsKey("google"));
        assertTrue(providers.containsKey("wechat"));
    }

    @Test
    void testGetThirdPartyUserInfo_GitHub_Success() {
        // Mock HTTP请求
        try (MockedStatic<HttpRequest> httpRequest = mockStatic(HttpRequest.class)) {
            mockHttpUserInfoRequest(httpRequest);

            // 执行测试
            Result<Map<String, Object>> result = thirdPartyAuthService.getThirdPartyUserInfo("github", TEST_ACCESS_TOKEN);

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> userInfo = result.getData();
            assertEquals(123456, userInfo.get("id"));
            assertEquals(TEST_USERNAME, userInfo.get("login"));
            assertEquals("https://avatars.githubusercontent.com/u/123456", userInfo.get("avatar_url"));
            assertEquals(TEST_EMAIL, userInfo.get("email"));
        }
    }

    @Test
    void testValidateThirdPartyToken_Valid() {
        // Mock获取用户信息成功
        try (MockedStatic<HttpRequest> httpRequest = mockStatic(HttpRequest.class)) {
            mockHttpUserInfoRequest(httpRequest);

            // 执行测试
            Result<Boolean> result = thirdPartyAuthService.validateThirdPartyToken("github", TEST_ACCESS_TOKEN);

            // 验证结果
            assertTrue(result.isSuccess());
            assertTrue(result.getData());
        }
    }

    @Test
    void testValidateThirdPartyToken_Invalid() {
        // Mock获取用户信息失败
        try (MockedStatic<HttpRequest> httpRequest = mockStatic(HttpRequest.class)) {
            HttpRequest mockRequest = mock(HttpRequest.class, RETURNS_DEEP_STUBS);
            HttpResponse mockResponse = mock(HttpResponse.class);

            httpRequest.when(() -> HttpRequest.get(anyString())).thenReturn(mockRequest);
            when(mockRequest.headerMap(anyMap(), anyBoolean())).thenReturn(mockRequest);
            when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
            when(mockRequest.execute()).thenReturn(mockResponse);
            when(mockResponse.isOk()).thenReturn(false);
            when(mockResponse.getStatus()).thenReturn(401);

            // 执行测试
            Result<Boolean> result = thirdPartyAuthService.validateThirdPartyToken("github", "invalid_token");

            // 验证结果
            assertTrue(result.isSuccess());
            assertFalse(result.getData());
        }
    }

    // 辅助方法

    private Account createMockAccount() {
        Account account = new Account();
        account.setId(TEST_USER_ID);
        account.setUsername(TEST_USERNAME);
        account.setEmail(TEST_EMAIL);
        account.setRole("user");
        account.setDeleted(0);
        return account;
    }

    private ThirdPartyAccount createMockThirdPartyAccount() {
        ThirdPartyAccount account = new ThirdPartyAccount();
        account.setId(1L);
        account.setUserId(TEST_USER_ID);
        account.setProvider("github");
        account.setThirdPartyId(TEST_THIRD_PARTY_ID);
        account.setAccessToken(TEST_ACCESS_TOKEN);
        account.setRefreshToken(TEST_REFRESH_TOKEN);
        account.setExpiresAt(java.time.LocalDateTime.now().plusDays(30));
        account.setDeleted(0);
        return account;
    }

    private void mockHttpTokenRequest(MockedStatic<HttpRequest> httpRequest) {
        HttpRequest mockRequest = mock(HttpRequest.class, RETURNS_DEEP_STUBS);
        HttpResponse mockResponse = mock(HttpResponse.class);

        httpRequest.when(() -> HttpRequest.post(anyString())).thenReturn(mockRequest);
        when(mockRequest.form(anyMap())).thenReturn(mockRequest);
        when(mockRequest.header(anyString(), anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
        when(mockRequest.execute()).thenReturn(mockResponse);
        when(mockResponse.isOk()).thenReturn(true);

        JSONObject tokenResponse = JSONUtil.createObj()
                .set("access_token", TEST_ACCESS_TOKEN)
                .set("token_type", "bearer")
                .set("scope", "user:email");
        when(mockResponse.body()).thenReturn(tokenResponse.toString());
    }

    private void mockHttpUserInfoRequest(MockedStatic<HttpRequest> httpRequest) {
        HttpRequest mockRequest = mock(HttpRequest.class, RETURNS_DEEP_STUBS);
        HttpResponse mockResponse = mock(HttpResponse.class);

        httpRequest.when(() -> HttpRequest.get(anyString())).thenReturn(mockRequest);
        when(mockRequest.headerMap(anyMap(), anyBoolean())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyInt())).thenReturn(mockRequest);
        when(mockRequest.execute()).thenReturn(mockResponse);
        when(mockResponse.isOk()).thenReturn(true);

        JSONObject userInfo = JSONUtil.createObj()
                .set("id", 123456)
                .set("login", TEST_USERNAME)
                .set("avatar_url", "https://avatars.githubusercontent.com/u/123456")
                .set("email", TEST_EMAIL);
        when(mockResponse.body()).thenReturn(userInfo.toString());
    }

}
