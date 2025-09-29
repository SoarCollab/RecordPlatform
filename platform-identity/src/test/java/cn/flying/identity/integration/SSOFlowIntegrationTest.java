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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SSO单点登录集成测试
 * 测试完整的SSO登录流程
 *
 * @author AI Assistant
 * @since 2025-01-16
 */
@DisplayName("SSO单点登录集成测试")
public class SSOFlowIntegrationTest extends BaseIntegrationTest {

    private static final String API_BASE = "/identity/api/sso";
    private static final String TEST_CLIENT_ID = "sso-test-client";
    private static final String TEST_CLIENT_SECRET = "sso-test-secret";
    private static final String TEST_REDIRECT_URI = "http://localhost:8080/sso/callback";

    @Autowired
    private OAuthClientMapper oauthClientMapper;

    @BeforeEach
    public void setUp() {
        // 创建测试SSO客户端
        OAuthClient testSSOClient = new OAuthClient();
        testSSOClient.setClientKey(TEST_CLIENT_ID);
        testSSOClient.setClientSecret(TEST_CLIENT_SECRET);
        testSSOClient.setClientName("Test SSO Client");
        testSSOClient.setRedirectUris(TEST_REDIRECT_URI);
        testSSOClient.setScopes("read,write");
        testSSOClient.setGrantTypes("authorization_code");
        testSSOClient.setStatus(1);
        testSSOClient.setAutoApprove(1);  // SSO自动授权
        testSSOClient.setDeleted(0);
        testSSOClient.setCreateTime(LocalDateTime.now());

        oauthClientMapper.insert(testSSOClient);
    }

    @Test
    @DisplayName("SSO登录 - 完整流程测试")
    public void testSSOLogin_CompleteFlow() throws Exception {
        // 1. 获取SSO授权信息
        mockMvc.perform(get(API_BASE + "/authorization")
                        .param("clientId", TEST_CLIENT_ID)
                        .param("redirectUri", TEST_REDIRECT_URI)
                        .param("scope", "read")
                        .param("state", "random-state-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        // 2. 执行SSO登录
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);
        loginRequest.put("clientId", TEST_CLIENT_ID);
        loginRequest.put("redirectUri", TEST_REDIRECT_URI);
        loginRequest.put("scope", "read");
        loginRequest.put("state", "random-state-123");

        MvcResult loginResult = mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sso_token").exists())
                .andExpect(jsonPath("$.data.redirect_uri").exists())
                .andReturn();

        String ssoToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("sso_token").asText();

        // 3. 验证SSO令牌
        Map<String, Object> validateRequest = new HashMap<>();
        validateRequest.put("token", ssoToken);
        validateRequest.put("clientId", TEST_CLIENT_ID);

        mockMvc.perform(post(API_BASE + "/tokens/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.user_id").exists());
    }

    @Test
    @DisplayName("SSO登录 - 用户名或密码错误")
    public void testSSOLogin_InvalidCredentials() throws Exception {
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", "WrongPassword123");  // 错误密码
        loginRequest.put("clientId", TEST_CLIENT_ID);
        loginRequest.put("redirectUri", TEST_REDIRECT_URI);
        loginRequest.put("scope", "read");

        mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SSO登录 - 无效的客户端ID")
    public void testSSOLogin_InvalidClientId() throws Exception {
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);
        loginRequest.put("clientId", "invalid-client-id");
        loginRequest.put("redirectUri", TEST_REDIRECT_URI);
        loginRequest.put("scope", "read");

        mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SSO登录 - 重定向URI不匹配")
    public void testSSOLogin_MismatchedRedirectUri() throws Exception {
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);
        loginRequest.put("clientId", TEST_CLIENT_ID);
        loginRequest.put("redirectUri", "http://malicious.com/callback");  // 不匹配
        loginRequest.put("scope", "read");

        mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("检查SSO会话状态 - 已登录")
    public void testCheckSSOSessionStatus_LoggedIn() throws Exception {
        // 先登录
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        mockMvc.perform(get(API_BASE + "/sessions/status")
                        .header("Authorization", "Bearer " + token)
                        .param("clientId", TEST_CLIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logged_in").value(true))
                .andExpect(jsonPath("$.data.user_id").exists());
    }

    @Test
    @DisplayName("检查SSO会话状态 - 未登录")
    public void testCheckSSOSessionStatus_NotLoggedIn() throws Exception {
        mockMvc.perform(get(API_BASE + "/sessions/status")
                        .param("clientId", TEST_CLIENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logged_in").value(false));
    }

    @Test
    @DisplayName("SSO注销 - 成功")
    public void testSSOLogout_Success() throws Exception {
        // 先登录
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);
        loginRequest.put("clientId", TEST_CLIENT_ID);
        loginRequest.put("redirectUri", TEST_REDIRECT_URI);
        loginRequest.put("scope", "read");

        MvcResult loginResult = mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String ssoToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("sso_token").asText();

        // 注销
        mockMvc.perform(delete(API_BASE + "/sessions/current")
                        .param("token", ssoToken)
                        .param("clientId", TEST_CLIENT_ID))
                .andExpect(status().isNoContent());

        // 验证令牌已失效
        Map<String, Object> validateRequest = new HashMap<>();
        validateRequest.put("token", ssoToken);
        validateRequest.put("clientId", TEST_CLIENT_ID);

        mockMvc.perform(post(API_BASE + "/tokens/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validateRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("验证SSO令牌 - 无效令牌")
    public void testValidateSSOToken_InvalidToken() throws Exception {
        Map<String, Object> validateRequest = new HashMap<>();
        validateRequest.put("token", "invalid-sso-token");
        validateRequest.put("clientId", TEST_CLIENT_ID);

        mockMvc.perform(post(API_BASE + "/tokens/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validateRequest)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SSO多应用场景 - 同时登录多个应用")
    public void testSSO_MultipleApplications() throws Exception {
        // 创建第二个SSO客户端
        OAuthClient secondClient = new OAuthClient();
        secondClient.setClientKey("second-sso-client");
        secondClient.setClientSecret("second-sso-secret");
        secondClient.setClientName("Second SSO Client");
        secondClient.setRedirectUris("http://localhost:8081/callback");
        secondClient.setScopes("read");
        secondClient.setGrantTypes("authorization_code");
        secondClient.setStatus(1);
        secondClient.setAutoApprove(1);
        secondClient.setDeleted(0);
        secondClient.setCreateTime(LocalDateTime.now());
        oauthClientMapper.insert(secondClient);

        // 1. 登录第一个应用
        Map<String, Object> firstLoginRequest = new HashMap<>();
        firstLoginRequest.put("username", TEST_USERNAME);
        firstLoginRequest.put("password", TEST_PASSWORD);
        firstLoginRequest.put("clientId", TEST_CLIENT_ID);
        firstLoginRequest.put("redirectUri", TEST_REDIRECT_URI);
        firstLoginRequest.put("scope", "read");

        MvcResult firstLogin = mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(firstLoginRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.sso_token").exists())
                .andReturn();

        // 2. 使用SSO自动登录第二个应用
        loginAsTestUser();  // 模拟已登录状态

        Map<String, Object> secondLoginRequest = new HashMap<>();
        secondLoginRequest.put("clientId", "second-sso-client");
        secondLoginRequest.put("redirectUri", "http://localhost:8081/callback");
        secondLoginRequest.put("scope", "read");

        mockMvc.perform(get(API_BASE + "/authorization")
                        .param("clientId", "second-sso-client")
                        .param("redirectUri", "http://localhost:8081/callback")
                        .param("scope", "read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.auto_approved").exists());
    }

    @Test
    @DisplayName("SSO Token刷新 - 成功")
    public void testSSOTokenRefresh_Success() throws Exception {
        // 先登录获取Token
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);
        loginRequest.put("clientId", TEST_CLIENT_ID);
        loginRequest.put("redirectUri", TEST_REDIRECT_URI);
        loginRequest.put("scope", "read");

        MvcResult loginResult = mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String ssoToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("sso_token").asText();

        // 刷新Token
        Map<String, Object> refreshRequest = new HashMap<>();
        refreshRequest.put("token", ssoToken);
        refreshRequest.put("clientId", TEST_CLIENT_ID);

        mockMvc.perform(post(API_BASE + "/tokens/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.new_token").exists());
    }

    @Test
    @DisplayName("SSO完整生命周期测试")
    public void testSSOLifecycle() throws Exception {
        // 1. 获取授权页面信息
        mockMvc.perform(get(API_BASE + "/authorization")
                        .param("clientId", TEST_CLIENT_ID)
                        .param("redirectUri", TEST_REDIRECT_URI)
                        .param("scope", "read"))
                .andExpect(status().isOk());

        // 2. 用户登录
        Map<String, Object> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);
        loginRequest.put("clientId", TEST_CLIENT_ID);
        loginRequest.put("redirectUri", TEST_REDIRECT_URI);
        loginRequest.put("scope", "read");

        MvcResult loginResult = mockMvc.perform(post(API_BASE + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        String ssoToken = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").get("sso_token").asText();

        // 3. 验证令牌
        Map<String, Object> validateRequest = new HashMap<>();
        validateRequest.put("token", ssoToken);
        validateRequest.put("clientId", TEST_CLIENT_ID);

        mockMvc.perform(post(API_BASE + "/tokens/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        // 4. 刷新令牌
        Map<String, Object> refreshRequest = new HashMap<>();
        refreshRequest.put("token", ssoToken);
        refreshRequest.put("clientId", TEST_CLIENT_ID);

        MvcResult refreshResult = mockMvc.perform(post(API_BASE + "/tokens/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(refreshRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String newToken = objectMapper.readTree(refreshResult.getResponse().getContentAsString())
                .get("data").get("new_token").asText();

        // 5. 注销
        mockMvc.perform(delete(API_BASE + "/sessions/current")
                        .param("token", newToken)
                        .param("clientId", TEST_CLIENT_ID))
                .andExpect(status().isNoContent());

        // 6. 验证令牌已失效
        validateRequest.put("token", newToken);
        mockMvc.perform(post(API_BASE + "/tokens/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(validateRequest)))
                .andExpect(status().isUnauthorized());
    }
}
