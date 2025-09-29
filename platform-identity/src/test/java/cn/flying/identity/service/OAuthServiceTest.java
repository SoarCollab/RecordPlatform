package cn.flying.identity.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.OAuthCode;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.mapper.OAuthClientMapper;
import cn.flying.identity.mapper.OAuthCodeMapper;
import cn.flying.identity.service.impl.OAuthServiceImpl;
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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OAuth2.0服务单元测试
 * 测试范围：授权流程、令牌管理、客户端验证、用户信息获取
 *
 * @author 王贝强
 * @create 2025-01-13
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthServiceTest {

    @InjectMocks
    private OAuthServiceImpl oauthService;

    @Mock
    private OAuthConfig oauthConfig;

    @Mock
    private OAuthClientMapper oauthClientMapper;

    @Mock
    private OAuthCodeMapper oauthCodeMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private OAuthClientSecretService oauthClientSecretService;

    // 测试数据常量
    private static final Long TEST_CLIENT_ID = 100L;
    private static final String TEST_CLIENT_KEY = "test_client_123";
    private static final String TEST_CLIENT_SECRET = "test_secret_456";
    private static final String TEST_REDIRECT_URI = "https://example.com/callback";
    private static final String TEST_SCOPE = "read write";
    private static final String TEST_STATE = "state_xyz_789";
    private static final Long TEST_USER_ID = 123L;
    private static final String TEST_CODE = "auth_code_abc123";
    private static final String TEST_ACCESS_TOKEN = "access_token_xyz789";
    private static final String TEST_REFRESH_TOKEN = "refresh_token_abc456";

    @BeforeEach
    void setUp() {
        // 配置Redis Mock
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        // 配置OAuthConfig默认值
        when(oauthConfig.getDefaultScope()).thenReturn("read");
        when(oauthConfig.getAccessTokenTimeout()).thenReturn(3600);
        when(oauthConfig.getRefreshTokenTimeout()).thenReturn(86400);
        when(oauthConfig.getCodeTimeout()).thenReturn(300);
        when(oauthConfig.getAccessTokenPrefix()).thenReturn("oauth:access:");
        when(oauthConfig.getRefreshTokenPrefix()).thenReturn("oauth:refresh:");
        when(oauthConfig.isRequireState()).thenReturn(true);
    }

    @Test
    void testGetAuthorizeInfo_ValidClient() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            // 执行测试
            Result<Map<String, Object>> result = oauthService.getAuthorizeInfo(
                TEST_CLIENT_KEY, TEST_REDIRECT_URI, TEST_SCOPE, TEST_STATE
            );

            // 验证结果
            assertTrue(result.isSuccess());
            Map<String, Object> data = result.getData();
            assertEquals("Test Client", data.get("clientName"));
            assertEquals(TEST_SCOPE, data.get("scope"));
            assertEquals(TEST_REDIRECT_URI, data.get("redirectUri"));
            assertEquals(TEST_STATE, data.get("state"));
        }
    }

    @Test
    void testGetAuthorizeInfo_InvalidClient() {
        // Mock客户端不存在
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getAuthorizeInfo(
            TEST_CLIENT_KEY, TEST_REDIRECT_URI, TEST_SCOPE, TEST_STATE
        );

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void testGetAuthorizeInfo_UserNotLoggedIn() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // 模拟用户未登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            Result<Map<String, Object>> result = oauthService.getAuthorizeInfo(
                TEST_CLIENT_KEY, TEST_REDIRECT_URI, TEST_SCOPE, TEST_STATE
            );

            // 验证结果：应该返回错误（用户未登录）
            assertFalse(result.isSuccess());
        }
    }

    @Test
    void testGetAuthorizeInfo_MissingState() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            // 执行测试（state为空）
            Result<Map<String, Object>> result = oauthService.getAuthorizeInfo(
                TEST_CLIENT_KEY, TEST_REDIRECT_URI, TEST_SCOPE, null
            );

            // 验证结果：应该返回错误（缺少state参数）
            assertFalse(result.isSuccess());
        }
    }

    @Test
    void testAuthorize_Success() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock授权码生成
        OAuthCode mockCode = createMockOAuthCode();
        when(oauthCodeMapper.insert(any(OAuthCode.class)))
                .thenReturn(1);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(TEST_USER_ID);

            // 执行测试（用户同意授权）
            Result<String> result = oauthService.authorize(
                TEST_CLIENT_KEY, TEST_REDIRECT_URI, TEST_SCOPE, TEST_STATE, true
            );

            // 验证结果
            assertTrue(result.isSuccess());
            String redirectUrl = result.getData();
            assertTrue(redirectUrl.contains("code="));
            assertTrue(redirectUrl.contains("state=" + TEST_STATE));

            // 验证授权码被保存
            verify(oauthCodeMapper).insert(any(OAuthCode.class));
        }
    }

    @Test
    void testAuthorize_UserDenied() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            // 执行测试（用户拒绝授权）
            Result<String> result = oauthService.authorize(
                TEST_CLIENT_KEY, TEST_REDIRECT_URI, TEST_SCOPE, TEST_STATE, false
            );

            // 验证结果
            assertTrue(result.isSuccess());
            String redirectUrl = result.getData();
            assertTrue(redirectUrl.contains("error=access_denied"));

            // 验证授权码未被保存
            verify(oauthCodeMapper, never()).insert(any(OAuthCode.class));
        }
    }

    @Test
    void testAuthorize_InvalidRedirectUri() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            // 执行测试（使用无效的重定向URI）
            Result<String> result = oauthService.authorize(
                TEST_CLIENT_KEY, "https://malicious.com/callback", TEST_SCOPE, TEST_STATE, true
            );

            // 验证结果：应该返回错误
            assertFalse(result.isSuccess());
        }
    }

    @Test
    void testAuthorize_InvalidScope() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // 模拟用户已登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            // 执行测试（使用无效的授权范围）
            Result<String> result = oauthService.authorize(
                TEST_CLIENT_KEY, TEST_REDIRECT_URI, "admin delete", TEST_STATE, true
            );

            // 验证结果：应该返回错误
            assertFalse(result.isSuccess());
        }
    }

    @Test
    void testGetAccessToken_Success() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock授权码验证
        OAuthCode mockCode = createMockOAuthCode();
        when(oauthCodeMapper.findByCodeAndClientKey(TEST_CODE, TEST_CLIENT_KEY))
                .thenReturn(mockCode);
        when(oauthCodeMapper.markCodeAsUsed(TEST_CODE))
                .thenReturn(1);

        // Mock用户查询
        Account mockAccount = createMockAccount();
        when(accountMapper.selectById(TEST_USER_ID))
                .thenReturn(mockAccount);

        // Mock Redis操作
        doNothing().when(hashOperations).putAll(anyString(), anyMap());
        when(redisTemplate.expire(anyString(), anyLong(), any()))
                .thenReturn(true);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getAccessToken(
            "authorization_code", TEST_CODE, TEST_REDIRECT_URI, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> tokenInfo = result.getData();
        assertNotNull(tokenInfo.get("access_token"));
        assertEquals("Bearer", tokenInfo.get("token_type"));
        assertNotNull(tokenInfo.get("refresh_token"));
        assertEquals(3600, tokenInfo.get("expires_in"));

        // 验证授权码被标记为已使用
        verify(oauthCodeMapper).markCodeAsUsed(TEST_CODE);
    }

    @Test
    void testGetAccessToken_InvalidCode() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock授权码不存在
        when(oauthCodeMapper.findByCodeAndClientKey(TEST_CODE, TEST_CLIENT_KEY))
                .thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getAccessToken(
            "authorization_code", TEST_CODE, TEST_REDIRECT_URI, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果：应该返回错误
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetAccessToken_CodeExpired() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock授权码已过期
        OAuthCode expiredCode = createMockOAuthCode();
        expiredCode.setExpireTime(LocalDateTime.now().minusMinutes(10)); // 10分钟前过期
        when(oauthCodeMapper.findByCodeAndClientKey(TEST_CODE, TEST_CLIENT_KEY))
                .thenReturn(expiredCode);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getAccessToken(
            "authorization_code", TEST_CODE, TEST_REDIRECT_URI, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果：应该返回错误（授权码过期）
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetAccessToken_CodeAlreadyUsed() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock授权码已使用
        OAuthCode usedCode = createMockOAuthCode();
        usedCode.setStatus(OAuthConfig.CodeStatus.USED);
        when(oauthCodeMapper.findByCodeAndClientKey(TEST_CODE, TEST_CLIENT_KEY))
                .thenReturn(usedCode);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getAccessToken(
            "authorization_code", TEST_CODE, TEST_REDIRECT_URI, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果：应该返回错误（授权码已使用）
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetAccessToken_InvalidClient() {
        // Mock客户端不存在
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(null);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getAccessToken(
            "authorization_code", TEST_CODE, TEST_REDIRECT_URI, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果：应该返回错误
        assertFalse(result.isSuccess());
    }

    @Test
    void testRefreshAccessToken_Success() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock刷新令牌存在
        Map<Object, Object> tokenData = new HashMap<>();
        tokenData.put("user_id", String.valueOf(TEST_USER_ID));
        tokenData.put("client_id", TEST_CLIENT_KEY);
        tokenData.put("access_token", "old_access_token");
        tokenData.put("scope", TEST_SCOPE);
        when(hashOperations.entries("oauth:refresh:" + TEST_REFRESH_TOKEN))
                .thenReturn(tokenData);

        // Mock用户验证
        Account mockAccount = createMockAccount();
        when(accountMapper.selectById(TEST_USER_ID))
                .thenReturn(mockAccount);

        // Mock Redis操作
        doNothing().when(hashOperations).putAll(anyString(), anyMap());
        when(redisTemplate.expire(anyString(), anyLong(), any()))
                .thenReturn(true);
        when(redisTemplate.delete(anyString()))
                .thenReturn(true);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.refreshAccessToken(
            "refresh_token", TEST_REFRESH_TOKEN, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> tokenInfo = result.getData();
        assertNotNull(tokenInfo.get("access_token"));
        assertEquals("Bearer", tokenInfo.get("token_type"));
        assertNotNull(tokenInfo.get("refresh_token"));

        // 验证旧令牌被删除
        verify(redisTemplate, atLeastOnce()).delete(anyString());
    }

    @Test
    void testRefreshAccessToken_InvalidRefreshToken() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock刷新令牌不存在
        when(hashOperations.entries("oauth:refresh:" + TEST_REFRESH_TOKEN))
                .thenReturn(new HashMap<>());

        // 执行测试
        Result<Map<String, Object>> result = oauthService.refreshAccessToken(
            "refresh_token", TEST_REFRESH_TOKEN, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果：应该返回错误
        assertFalse(result.isSuccess());
    }

    @Test
    void testRefreshAccessToken_ClientMismatch() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock刷新令牌存在但客户端不匹配
        Map<Object, Object> tokenData = new HashMap<>();
        tokenData.put("user_id", String.valueOf(TEST_USER_ID));
        tokenData.put("client_id", "different_client");
        tokenData.put("access_token", "old_access_token");
        when(hashOperations.entries("oauth:refresh:" + TEST_REFRESH_TOKEN))
                .thenReturn(tokenData);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.refreshAccessToken(
            "refresh_token", TEST_REFRESH_TOKEN, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果：应该返回错误（客户端不匹配）
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetUserInfo_Success() {
        // Mock访问令牌存在
        Map<Object, Object> tokenData = new HashMap<>();
        tokenData.put("user_id", String.valueOf(TEST_USER_ID));
        tokenData.put("client_id", TEST_CLIENT_KEY);
        when(hashOperations.entries("oauth:access:" + TEST_ACCESS_TOKEN))
                .thenReturn(tokenData);

        // Mock用户存在
        Account mockAccount = createMockAccount();
        when(accountMapper.selectById(TEST_USER_ID))
                .thenReturn(mockAccount);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getUserInfo(TEST_ACCESS_TOKEN);

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> userInfo = result.getData();
        assertEquals(TEST_USER_ID, userInfo.get("id"));
        assertEquals("testuser", userInfo.get("username"));
        assertEquals("test@example.com", userInfo.get("email"));
    }

    @Test
    void testGetUserInfo_InvalidToken() {
        // Mock访问令牌不存在
        when(hashOperations.entries("oauth:access:" + TEST_ACCESS_TOKEN))
                .thenReturn(new HashMap<>());

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getUserInfo(TEST_ACCESS_TOKEN);

        // 验证结果：应该返回错误
        assertFalse(result.isSuccess());
    }

    @Test
    void testRevokeToken_Success() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock Redis删除操作
        when(redisTemplate.delete(anyString()))
                .thenReturn(true);

        // 执行测试
        Result<Void> result = oauthService.revokeToken(
            TEST_ACCESS_TOKEN, "access_token", TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果
        assertTrue(result.isSuccess());

        // 验证Redis删除被调用
        verify(redisTemplate, atLeastOnce()).delete(anyString());
    }

    @Test
    void testClientCredentials_Success() {
        // 准备测试数据
        OAuthClient mockClient = createMockOAuthClient();
        when(oauthClientMapper.findByClientKey(TEST_CLIENT_KEY))
                .thenReturn(mockClient);

        // Mock客户端验证
        when(oauthClientSecretService.matches(TEST_CLIENT_SECRET, mockClient.getClientSecret()))
                .thenReturn(true);

        // Mock Redis操作
        doNothing().when(hashOperations).putAll(anyString(), anyMap());
        when(redisTemplate.expire(anyString(), anyLong(), any()))
                .thenReturn(true);

        // 执行测试
        Result<Map<String, Object>> result = oauthService.getClientCredentialsToken(
            "client_credentials", TEST_SCOPE, TEST_CLIENT_KEY, TEST_CLIENT_SECRET
        );

        // 验证结果
        assertTrue(result.isSuccess());
        Map<String, Object> tokenInfo = result.getData();
        assertNotNull(tokenInfo.get("access_token"));
        assertEquals("Bearer", tokenInfo.get("token_type"));
        assertNull(tokenInfo.get("refresh_token")); // 客户端凭证模式不返回刷新令牌
    }

    // 辅助方法：创建模拟的OAuth客户端
    private OAuthClient createMockOAuthClient() {
        OAuthClient client = new OAuthClient();
        client.setClientId(TEST_CLIENT_ID);
        client.setClientKey(TEST_CLIENT_KEY);
        client.setClientSecret("$2a$10$hashedclientsecret");
        client.setClientName("Test Client");
        client.setRedirectUris(TEST_REDIRECT_URI);
        client.setScopes("read write");
        client.setGrantTypes("authorization_code,refresh_token,client_credentials");
        client.setAccessTokenValidity(3600);
        client.setRefreshTokenValidity(86400);
        client.setAutoApprove(0);
        client.setStatus(1);
        return client;
    }

    // 辅助方法：创建模拟的授权码
    private OAuthCode createMockOAuthCode() {
        OAuthCode code = new OAuthCode();
        code.setCode(TEST_CODE);
        code.setClientKey(TEST_CLIENT_KEY);
        code.setUserId(TEST_USER_ID);
        code.setRedirectUri(TEST_REDIRECT_URI);
        code.setScope(TEST_SCOPE);
        code.setState(TEST_STATE);
        code.setStatus(OAuthConfig.CodeStatus.VALID);
        code.setExpireTime(LocalDateTime.now().plusMinutes(5)); // 5分钟后过期
        return code;
    }

    // 辅助方法：创建模拟的账户
    private Account createMockAccount() {
        Account account = new Account();
        account.setId(TEST_USER_ID);
        account.setUsername("testuser");
        account.setEmail("test@example.com");
        account.setPassword("$2a$10$hashedpassword");
        account.setRole("user");
        account.setDeleted(0);
        return account;
    }
}
