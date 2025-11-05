package cn.flying.identity.integration;

import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.OAuthCode;
import cn.flying.identity.mapper.OAuthClientMapper;
import cn.flying.identity.service.OAuthService;
import cn.flying.identity.service.SSOService;
import cn.flying.identity.service.impl.OAuthServiceImpl;
import cn.flying.identity.service.impl.SSOServiceImpl;
import cn.hutool.core.util.IdUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("OAuth 管理批量撤销集成测试")
public class OAuthAdminIntegrationTest extends BaseIntegrationTest {

    private static final String API_BASE = "/api/oauth";
    private static final String REDIRECT_URI = "http://localhost:8080/callback";
    private static final String THIRD_PARTY_PREFIX = "third_party:token:";

    @Autowired
    private OAuthClientMapper oauthClientMapper;

    @Autowired
    private OAuthService oauthService;

    @Autowired
    private OAuthServiceImpl oauthServiceImpl;

    @Autowired
    private SSOService ssoService;

    @Autowired
    private SSOServiceImpl ssoServiceImpl;

    @Autowired
    private OAuthConfig oauthConfig;

    @Autowired
    @Qualifier("stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    private String ssoTokenPrefix;
    private String ssoClientPrefix;
    private String ssoUserPrefix;
    private int ssoTokenTimeout;

    @BeforeEach
    void initPrefixes() {
        ssoTokenPrefix = (String) ReflectionTestUtils.getField(ssoServiceImpl, "ssoTokenPrefix");
        ssoClientPrefix = (String) ReflectionTestUtils.getField(ssoServiceImpl, "ssoClientPrefix");
        ssoUserPrefix = (String) ReflectionTestUtils.getField(ssoServiceImpl, "ssoUserPrefix");
        ssoTokenTimeout = (Integer) ReflectionTestUtils.getField(ssoServiceImpl, "ssoTokenTimeout");
        redisTemplate.afterPropertiesSet();
    }

    @Test
    @DisplayName("按用户批量撤销时清理 OAuth 索引、第三方映射与 SSO 会话")
    void revokeTokensByUser_shouldCleanOAuthIndexesAndSsoSessions() throws Exception {
        String clientKey = "admin-client-" + UUID.randomUUID();
        String clientSecret = "secret-" + UUID.randomUUID();
        insertClient(clientKey, clientSecret, 1);

        Long userId = testAccount.getId();
        Map<String, String> tokens = issueTokens(userId, clientKey, clientSecret);
        String accessToken = tokens.get("accessToken");
        String refreshToken = tokens.get("refreshToken");

        // 构造第三方映射
        String tpAccessKey = THIRD_PARTY_PREFIX + "github:access:" + accessToken;
        String tpRefreshKey = THIRD_PARTY_PREFIX + "github:refresh:" + refreshToken;
        redisTemplate.opsForValue().set(tpAccessKey, "github-user");
        redisTemplate.opsForValue().set(tpRefreshKey, "github-user");

        // 构造模拟 SSO 会话
        String ssoToken = mockSsoSession(userId, clientKey);

        assertTrue(redisKeyExists(oauthConfig.getAccessTokenPrefix() + accessToken));
        assertTrue(redisKeyExists(oauthConfig.getRefreshTokenPrefix() + refreshToken));
        Set<String> userAccessBefore = redisTemplate.opsForSet()
                .members(oauthConfig.getUserTokenPrefix() + userId + ":access");
        assertNotNull(userAccessBefore);
        assertFalse(userAccessBefore.isEmpty());
        Set<String> clientAccessBefore = redisTemplate.opsForSet()
                .members(oauthConfig.getClientTokenPrefix() + clientKey + ":access");
        assertNotNull(clientAccessBefore);
        assertFalse(clientAccessBefore.isEmpty());
        assertTrue(redisKeyExists(ssoTokenPrefix + ssoToken));

        Account admin = createAdminAccount("admin-" + UUID.randomUUID(), "admin-" + UUID.randomUUID() + "@example.com");
        String adminToken = getAuthToken(admin.getId());

        ResultActions revokeAction = mockMvc.perform(withToken(
                        post(API_BASE + "/tokens/revoke/users/{userId}", userId),
                        adminToken)
                .contentType(MediaType.APPLICATION_JSON));
        revokeAction.andExpect(status().isOk());

        assertFalse(redisKeyExists(oauthConfig.getAccessTokenPrefix() + accessToken),
                "访问令牌未被删除");
        assertFalse(redisKeyExists(oauthConfig.getRefreshTokenPrefix() + refreshToken),
                "刷新令牌未被删除");
        assertSetCleared(oauthConfig.getUserTokenPrefix() + userId + ":access");
        assertSetCleared(oauthConfig.getUserTokenPrefix() + userId + ":refresh");
        assertSetCleared(oauthConfig.getClientTokenPrefix() + clientKey + ":access");
        assertSetCleared(oauthConfig.getClientTokenPrefix() + clientKey + ":refresh");
        assertFalse(redisKeyExists(tpAccessKey), "第三方 access 映射未清理");
        assertFalse(redisKeyExists(tpRefreshKey), "第三方 refresh 映射未清理");

        assertFalse(redisKeyExists(ssoTokenPrefix + ssoToken), "SSO token 未清理");
        assertFalse(redisKeyExists(ssoClientPrefix + userId + ":" + clientKey),
                "SSO 客户端记录未清理");
        assertSetCleared(ssoUserPrefix + userId);
        assertSetCleared(ssoClientPrefix + "users:" + clientKey);
    }

    @Test
    @DisplayName("按客户端批量撤销会清理多个用户的 OAuth 与 SSO 索引")
    void revokeTokensByClient_shouldCleanAllUsersForClient() throws Exception {
        String clientKey = "multi-client-" + UUID.randomUUID();
        String clientSecret = "secret-" + UUID.randomUUID();
        insertClient(clientKey, clientSecret, 0);

        // 第二个用户
        Account secondAccount = createTestAccount("second-" + IdUtil.getSnowflakeNextId(),
                "second-" + IdUtil.getSnowflakeNextId() + "@example.com", TEST_PASSWORD);

        Map<String, String> user1Tokens = issueTokens(testAccount.getId(), clientKey, clientSecret);
        Map<String, String> user2Tokens = issueTokens(secondAccount.getId(), clientKey, clientSecret);

        mockSsoSession(testAccount.getId(), clientKey);
        mockSsoSession(secondAccount.getId(), clientKey);

        Account admin = createAdminAccount("client-admin-" + UUID.randomUUID(),
                "client-admin-" + UUID.randomUUID() + "@example.com");
        String adminToken = getAuthToken(admin.getId());

        mockMvc.perform(withToken(
                        post(API_BASE + "/tokens/revoke/clients/{clientId}", clientKey),
                        adminToken)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        assertFalse(redisKeyExists(oauthConfig.getClientTokenPrefix() + clientKey + ":access"));
        assertFalse(redisKeyExists(ssoClientPrefix + testAccount.getId() + ":" + clientKey));
        assertFalse(redisKeyExists(ssoClientPrefix + secondAccount.getId() + ":" + clientKey));
        assertSetCleared(ssoClientPrefix + "users:" + clientKey);

        assertFalse(redisKeyExists(oauthConfig.getAccessTokenPrefix() + user1Tokens.get("accessToken")));
        assertFalse(redisKeyExists(oauthConfig.getAccessTokenPrefix() + user2Tokens.get("accessToken")));

        assertSetCleared(oauthConfig.getUserTokenPrefix() + testAccount.getId() + ":access");
        assertSetCleared(oauthConfig.getUserTokenPrefix() + secondAccount.getId() + ":access");
    }

    private void assertSetCleared(String key) {
        Set<String> members = redisTemplate.opsForSet().members(key);
        if (members == null || members.isEmpty()) {
            assertFalse(redisKeyExists(key));
        } else {
            fail("Redis 集合未清空: " + key + " -> " + members);
        }
    }

    private boolean redisKeyExists(String key) {
        return !redisTemplate.keys(key).isEmpty();
    }

    private void insertClient(String clientKey, String clientSecret, int autoApprove) {
        OAuthClient client = new OAuthClient();
        client.setClientKey(clientKey);
        client.setClientSecret(clientSecret);
        client.setClientName("Admin Test Client");
        client.setRedirectUris(REDIRECT_URI);
        client.setScopes("read,write");
        client.setGrantTypes("authorization_code,refresh_token");
        client.setStatus(1);
        client.setAutoApprove(autoApprove);
        client.setDeleted(0);
        client.setCreateTime(LocalDateTime.now());
        oauthClientMapper.insert(client);
    }

    private Map<String, String> issueTokens(Long userId, String clientKey, String clientSecret) {
        OAuthCode code = oauthService.generateAuthorizationCode(clientKey, userId, REDIRECT_URI, "read", "state");
        Map<String, Object> tokenResponse = oauthService.getAccessToken(
                "authorization_code", code.getCode(), REDIRECT_URI, clientKey, clientSecret);
        String accessToken = (String) tokenResponse.get("access_token");
        String refreshToken = (String) tokenResponse.get("refresh_token");
        assertNotNull(accessToken, "生成访问令牌失败");
        assertNotNull(refreshToken, "生成刷新令牌失败");
        Map<String, String> tokens = new HashMap<>();
        tokens.put("accessToken", accessToken);
        tokens.put("refreshToken", refreshToken);
        return tokens;
    }

    private String mockSsoSession(Long userId, String clientId) {
        String token = "SSO-MOCK-" + UUID.randomUUID();
        String tokenKey = ssoTokenPrefix + token;
        String clientKey = ssoClientPrefix + userId + ":" + clientId;
        String userKey = ssoUserPrefix + userId;
        String clientUsersKey = ssoClientPrefix + "users:" + clientId;

        redisTemplate.opsForValue().set(tokenKey,
                userId + ":" + clientId + ":" + System.currentTimeMillis(),
                ssoTokenTimeout, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(clientKey,
                clientId + ":MockClient:" + System.currentTimeMillis(),
                ssoTokenTimeout, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.opsForSet().add(userKey, clientId);
        redisTemplate.expire(userKey, ssoTokenTimeout, java.util.concurrent.TimeUnit.SECONDS);
        redisTemplate.opsForSet().add(clientUsersKey, String.valueOf(userId));
        redisTemplate.expire(clientUsersKey, ssoTokenTimeout, java.util.concurrent.TimeUnit.SECONDS);
        return token;
    }
}
