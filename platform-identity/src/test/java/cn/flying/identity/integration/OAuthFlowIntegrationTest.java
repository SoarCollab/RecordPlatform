package cn.flying.identity.integration;

import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.mapper.OAuthClientMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OAuth 2.0授权流程集成测试
 * 测试完整的OAuth授权码流程、刷新令牌等
 *
 * @author AI Assistant
 * @since 2025-01-16
 */
@DisplayName("OAuth 2.0授权流程集成测试")
public class OAuthFlowIntegrationTest extends BaseIntegrationTest {

    private static final String API_BASE = "/identity/api/oauth";
    private static final String TEST_CLIENT_ID = "test-client-id";
    private static final String TEST_CLIENT_SECRET = "test-client-secret";
    private static final String TEST_REDIRECT_URI = "http://localhost:8080/callback";

    @Autowired
    private OAuthClientMapper oauthClientMapper;

    @BeforeEach
    public void setUp() {
        // 创建测试OAuth客户端
        OAuthClient testOAuthClient = new OAuthClient();
        testOAuthClient.setClientKey(TEST_CLIENT_ID);
        testOAuthClient.setClientSecret(TEST_CLIENT_SECRET);
        testOAuthClient.setClientName("Test OAuth Client");
        testOAuthClient.setRedirectUris(TEST_REDIRECT_URI);
        testOAuthClient.setScopes("read,write");
        testOAuthClient.setGrantTypes("authorization_code,refresh_token");
        testOAuthClient.setStatus(1);  // 启用状态
        testOAuthClient.setAutoApprove(0);  // 需要用户手动授权
        testOAuthClient.setDeleted(0);
        testOAuthClient.setCreateTime(LocalDateTime.now());

        oauthClientMapper.insert(testOAuthClient);
    }

    @Test
    @DisplayName("OAuth授权码流程 - 完整流程测试")
    public void testAuthorizationCodeFlow_Complete() throws Exception {
        // 1. 用户登录
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        // 2. 用户授权
        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", TEST_CLIENT_ID);
        authRequest.put("redirectUri", TEST_REDIRECT_URI);
        authRequest.put("scope", "read");
        authRequest.put("state", "random-state-123");
        authRequest.put("approved", true);

        MvcResult authResult = mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        String authCode = objectMapper.readTree(authResult.getResponse().getContentAsString())
                .get("data").asText();

        // 3. 使用授权码获取访问令牌
        Map<String, Object> tokenRequest = new HashMap<>();
        tokenRequest.put("grantType", "authorization_code");
        tokenRequest.put("code", authCode);
        tokenRequest.put("redirectUri", TEST_REDIRECT_URI);
        tokenRequest.put("clientId", TEST_CLIENT_ID);
        tokenRequest.put("clientSecret", TEST_CLIENT_SECRET);

        MvcResult tokenResult = mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tokenRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.refresh_token").exists())
                .andExpect(jsonPath("$.data.token_type").value("Bearer"))
                .andExpect(jsonPath("$.data.expires_in").isNumber())
                .andReturn();

        String refreshToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("data").get("refresh_token").asText();

        // 4. 使用刷新令牌获取新的访问令牌
        Map<String, Object> refreshRequest = new HashMap<>();
        refreshRequest.put("grantType", "refresh_token");
        refreshRequest.put("refreshToken", refreshToken);
        refreshRequest.put("clientId", TEST_CLIENT_ID);
        refreshRequest.put("clientSecret", TEST_CLIENT_SECRET);

        mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists())
                .andExpect(jsonPath("$.data.refresh_token").exists());
    }

    @Test
    @DisplayName("OAuth授权 - 用户拒绝授权")
    public void testAuthorization_UserDenied() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", TEST_CLIENT_ID);
        authRequest.put("redirectUri", TEST_REDIRECT_URI);
        authRequest.put("scope", "read");
        authRequest.put("state", "random-state-123");
        authRequest.put("approved", false);  // 用户拒绝授权

        mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("OAuth授权 - 无效的客户端ID")
    public void testAuthorization_InvalidClientId() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", "invalid-client-id");
        authRequest.put("redirectUri", TEST_REDIRECT_URI);
        authRequest.put("scope", "read");
        authRequest.put("approved", true);

        mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("OAuth授权 - 无效的回调URI")
    public void testAuthorization_InvalidRedirectUri() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", TEST_CLIENT_ID);
        authRequest.put("redirectUri", "http://malicious.com/callback");  // 不匹配注册的URI
        authRequest.put("scope", "read");
        authRequest.put("approved", true);

        mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("获取访问令牌 - 无效的授权码")
    public void testGetAccessToken_InvalidCode() throws Exception {
        Map<String, Object> tokenRequest = new HashMap<>();
        tokenRequest.put("grantType", "authorization_code");
        tokenRequest.put("code", "invalid-auth-code");
        tokenRequest.put("redirectUri", TEST_REDIRECT_URI);
        tokenRequest.put("clientId", TEST_CLIENT_ID);
        tokenRequest.put("clientSecret", TEST_CLIENT_SECRET);

        mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tokenRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("获取访问令牌 - 无效的客户端密钥")
    public void testGetAccessToken_InvalidClientSecret() throws Exception {
        // 先获取授权码
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", TEST_CLIENT_ID);
        authRequest.put("redirectUri", TEST_REDIRECT_URI);
        authRequest.put("scope", "read");
        authRequest.put("approved", true);

        MvcResult authResult = mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String authCode = objectMapper.readTree(authResult.getResponse().getContentAsString())
                .get("data").asText();

        // 使用错误的客户端密钥
        Map<String, Object> tokenRequest = new HashMap<>();
        tokenRequest.put("grantType", "authorization_code");
        tokenRequest.put("code", authCode);
        tokenRequest.put("redirectUri", TEST_REDIRECT_URI);
        tokenRequest.put("clientId", TEST_CLIENT_ID);
        tokenRequest.put("clientSecret", "wrong-secret");

        mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tokenRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("刷新令牌 - 成功")
    public void testRefreshToken_Success() throws Exception {
        // 先完成授权流程获取refresh_token
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", TEST_CLIENT_ID);
        authRequest.put("redirectUri", TEST_REDIRECT_URI);
        authRequest.put("scope", "read");
        authRequest.put("approved", true);

        MvcResult authResult = mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String authCode = objectMapper.readTree(authResult.getResponse().getContentAsString())
                .get("data").asText();

        Map<String, Object> tokenRequest = new HashMap<>();
        tokenRequest.put("grantType", "authorization_code");
        tokenRequest.put("code", authCode);
        tokenRequest.put("redirectUri", TEST_REDIRECT_URI);
        tokenRequest.put("clientId", TEST_CLIENT_ID);
        tokenRequest.put("clientSecret", TEST_CLIENT_SECRET);

        MvcResult tokenResult = mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tokenRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String refreshToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("data").get("refresh_token").asText();

        // 使用刷新令牌
        Map<String, Object> refreshRequest = new HashMap<>();
        refreshRequest.put("grantType", "refresh_token");
        refreshRequest.put("refreshToken", refreshToken);
        refreshRequest.put("clientId", TEST_CLIENT_ID);
        refreshRequest.put("clientSecret", TEST_CLIENT_SECRET);

        mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.access_token").exists());
    }

    @Test
    @DisplayName("撤销令牌 - 成功")
    public void testRevokeToken_Success() throws Exception {
        // 先获取访问令牌
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        Map<String, Object> authRequest = new HashMap<>();
        authRequest.put("clientId", TEST_CLIENT_ID);
        authRequest.put("redirectUri", TEST_REDIRECT_URI);
        authRequest.put("scope", "read");
        authRequest.put("approved", true);

        MvcResult authResult = mockMvc.perform(post(API_BASE + "/authorizations")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(authRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String authCode = objectMapper.readTree(authResult.getResponse().getContentAsString())
                .get("data").asText();

        Map<String, Object> tokenRequest = new HashMap<>();
        tokenRequest.put("grantType", "authorization_code");
        tokenRequest.put("code", authCode);
        tokenRequest.put("redirectUri", TEST_REDIRECT_URI);
        tokenRequest.put("clientId", TEST_CLIENT_ID);
        tokenRequest.put("clientSecret", TEST_CLIENT_SECRET);

        MvcResult tokenResult = mockMvc.perform(post(API_BASE + "/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(tokenRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String accessToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("data").get("access_token").asText();

        // 撤销令牌
        Map<String, Object> revokeRequest = new HashMap<>();
        revokeRequest.put("token", accessToken);
        revokeRequest.put("clientId", TEST_CLIENT_ID);
        revokeRequest.put("clientSecret", TEST_CLIENT_SECRET);

        mockMvc.perform(post(API_BASE + "/tokens/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(revokeRequest)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("获取OAuth客户端信息")
    public void testGetClientInfo() throws Exception {
        mockMvc.perform(get(API_BASE + "/clients/" + TEST_CLIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clientId").value(TEST_CLIENT_ID))
                .andExpect(jsonPath("$.data.clientName").value("Test OAuth Client"))
                .andExpect(jsonPath("$.data.scopes").exists());
    }

    @Test
    @DisplayName("获取不存在的客户端信息")
    public void testGetClientInfo_NotFound() throws Exception {
        mockMvc.perform(get(API_BASE + "/clients/non-existent-client"))
                .andExpect(status().isNotFound());
    }
}
