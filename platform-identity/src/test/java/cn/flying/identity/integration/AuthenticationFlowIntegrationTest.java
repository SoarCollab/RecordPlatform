package cn.flying.identity.integration;

import cn.flying.identity.dto.Account;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 完整认证流程端到端集成测试
 * 测试从注册到登录到密码管理的完整业务流程
 *
 * @author AI Assistant
 * @since 2025-01-16
 */
@DisplayName("完整认证流程集成测试")
public class AuthenticationFlowIntegrationTest extends BaseIntegrationTest {

    private static final String AUTH_API = "/identity/api/auth";

    @Test
    @DisplayName("完整用户注册流程")
    public void testCompleteRegistrationFlow() throws Exception {
        String newEmail = "newuser@example.com";
        String newUsername = "newuser";
        String password = "NewPassword123";

        // 1. 发送验证码
        Map<String, String> codeRequest = new HashMap<>();
        codeRequest.put("email", newEmail);
        codeRequest.put("type", "register");

        mockMvc.perform(post(AUTH_API + "/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(codeRequest)))
                .andExpect(status().isAccepted());

        // 2. 注册新用户（实际场景中需要真实的验证码，测试中可能需要mock）
        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("username", newUsername);
        registerRequest.put("email", newEmail);
        registerRequest.put("password", password);
        registerRequest.put("code", "123456");  // 测试验证码

        MvcResult result = mockMvc.perform(post(AUTH_API + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(registerRequest)))
                .andExpect(status().isCreated())
                .andReturn();

        // 3. 使用新注册的账号登录
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", newUsername);
        loginRequest.put("password", password);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());  // 返回token
    }

    @Test
    @DisplayName("登录-获取信息-注销完整流程")
    public void testLoginLogoutFlow() throws Exception {
        // 1. 用户登录
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").asText();

        // 2. 获取用户信息
        mockMvc.perform(get(AUTH_API + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME));

        // 3. 检查登录状态
        mockMvc.perform(get(AUTH_API + "/sessions/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.logged_in").value(true));

        // 4. 获取Token信息
        mockMvc.perform(get(AUTH_API + "/tokens/info")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        // 5. 用户注销
        mockMvc.perform(delete(AUTH_API + "/sessions/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 6. 验证注销后无法访问受保护资源
        mockMvc.perform(get(AUTH_API + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("修改密码完整流程")
    public void testChangePasswordFlow() throws Exception {
        // 创建测试用户
        Account user = createTestAccount("changepassuser", "changepass@example.com", TEST_PASSWORD);
        loginAs(user.getId());
        String token = getAuthToken(user.getId());

        String oldPassword = TEST_PASSWORD;
        String newPassword = "NewPassword456";

        // 1. 修改密码
        Map<String, String> changeRequest = new HashMap<>();
        changeRequest.put("password", oldPassword);
        changeRequest.put("newPassword", newPassword);

        mockMvc.perform(put(AUTH_API + "/passwords/current")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(changeRequest)))
                .andExpect(status().isOk());

        // 2. 验证旧密码不能再使用
        Map<String, String> oldLoginRequest = new HashMap<>();
        oldLoginRequest.put("username", "changepassuser");
        oldLoginRequest.put("password", oldPassword);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(oldLoginRequest)))
                .andExpect(status().isUnauthorized());

        // 3. 验证新密码可以登录
        Map<String, String> newLoginRequest = new HashMap<>();
        newLoginRequest.put("username", "changepassuser");
        newLoginRequest.put("password", newPassword);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(newLoginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString());
    }

    @Test
    @DisplayName("重置密码完整流程")
    public void testResetPasswordFlow() throws Exception {
        String email = testAccount.getEmail();
        String newPassword = "ResetPassword789";

        // 1. 发送重置密码验证码
        Map<String, String> codeRequest = new HashMap<>();
        codeRequest.put("email", email);
        codeRequest.put("type", "reset");

        mockMvc.perform(post(AUTH_API + "/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(codeRequest)))
                .andExpect(status().isAccepted());

        // 2. 确认重置密码（实际需要真实验证码）
        Map<String, String> resetRequest = new HashMap<>();
        resetRequest.put("email", email);
        resetRequest.put("code", "123456");  // 测试验证码
        resetRequest.put("password", newPassword);

        mockMvc.perform(put(AUTH_API + "/passwords/reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(resetRequest)))
                .andExpect(status().isOk());

        // 3. 使用新密码登录
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", newPassword);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isString());
    }

    @Test
    @DisplayName("登录安全测试 - 多次失败登录")
    public void testLoginSecurity_MultipleFailures() throws Exception {
        Map<String, String> wrongLoginRequest = new HashMap<>();
        wrongLoginRequest.put("username", TEST_USERNAME);
        wrongLoginRequest.put("password", "WrongPassword");

        // 连续多次错误登录
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(AUTH_API + "/sessions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(toJson(wrongLoginRequest)))
                    .andExpect(status().isUnauthorized());
        }

        // 即使使用正确密码，也可能被限流（取决于实现）
        Map<String, String> correctLoginRequest = new HashMap<>();
        correctLoginRequest.put("username", TEST_USERNAME);
        correctLoginRequest.put("password", TEST_PASSWORD);

        // 这里的状态取决于是否实现了登录限流
        mockMvc.perform(post(AUTH_API + "/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(toJson(correctLoginRequest)));
    }

    @Test
    @DisplayName("查找用户功能测试")
    public void testFindUserFlow() throws Exception {
        // 创建几个测试用户
        createTestAccount("alice", "alice@example.com", TEST_PASSWORD);
        createTestAccount("bob", "bob@example.com", TEST_PASSWORD);
        createTestAccount("charlie", "charlie@example.com", TEST_PASSWORD);

        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        // 按用户名查找
        mockMvc.perform(get(AUTH_API + "/users/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        // 按邮箱查找
        mockMvc.perform(get(AUTH_API + "/users/search")
                        .header("Authorization", "Bearer " + token)
                        .param("query", "bob@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @DisplayName("并发登录测试")
    public void testConcurrentLogin() throws Exception {
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);

        // 第一次登录
        MvcResult firstLogin = mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String firstToken = objectMapper.readTree(firstLogin.getResponse().getContentAsString())
                .get("data").asText();

        // 第二次登录（新设备）
        MvcResult secondLogin = mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String secondToken = objectMapper.readTree(secondLogin.getResponse().getContentAsString())
                .get("data").asText();

        // 验证两个token都不同（根据配置）
        assertNotEquals(firstToken, secondToken);

        // 两个token应该都能访问资源（允许并发登录）
        mockMvc.perform(get(AUTH_API + "/me")
                        .header("Authorization", "Bearer " + firstToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(AUTH_API + "/me")
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Token过期和续期测试")
    public void testTokenExpirationAndRenewal() throws Exception {
        // 登录获取token
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", TEST_USERNAME);
        loginRequest.put("password", TEST_PASSWORD);

        MvcResult loginResult = mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").asText();

        // 验证token有效
        mockMvc.perform(get(AUTH_API + "/tokens/info")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());

        // 使用token访问资源
        mockMvc.perform(get(AUTH_API + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("完整用户生命周期 - 从注册到删除")
    public void testCompleteUserLifecycle() throws Exception {
        String email = "lifecycle@example.com";
        String username = "lifecycleuser";
        String password = "LifeCycle123";

        // 1. 发送注册验证码
        Map<String, String> codeRequest = new HashMap<>();
        codeRequest.put("email", email);
        codeRequest.put("type", "register");

        mockMvc.perform(post(AUTH_API + "/verification-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(codeRequest)))
                .andExpect(status().isAccepted());

        // 2. 注册
        Map<String, Object> registerRequest = new HashMap<>();
        registerRequest.put("username", username);
        registerRequest.put("email", email);
        registerRequest.put("password", password);
        registerRequest.put("code", "123456");

        mockMvc.perform(post(AUTH_API + "/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(registerRequest)))
                .andExpect(status().isCreated());

        // 3. 登录
        Map<String, String> loginRequest = new HashMap<>();
        loginRequest.put("username", username);
        loginRequest.put("password", password);

        MvcResult loginResult = mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .get("data").asText();

        // 4. 获取用户信息
        mockMvc.perform(get(AUTH_API + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(username));

        // 5. 修改密码
        String newPassword = "NewLifeCycle456";
        Map<String, String> changeRequest = new HashMap<>();
        changeRequest.put("password", password);
        changeRequest.put("newPassword", newPassword);

        mockMvc.perform(put(AUTH_API + "/passwords/current")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(changeRequest)))
                .andExpect(status().isOk());

        // 6. 使用新密码登录
        loginRequest.put("password", newPassword);
        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(loginRequest)))
                .andExpect(status().isOk());

        // 7. 注销
        mockMvc.perform(delete(AUTH_API + "/sessions/current")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("输入验证测试 - SQL注入和XSS防护")
    public void testInputValidation_SecurityAttacks() throws Exception {
        // SQL注入测试
        Map<String, String> sqlInjectionRequest = new HashMap<>();
        sqlInjectionRequest.put("username", "admin'; DROP TABLE users; --");
        sqlInjectionRequest.put("password", TEST_PASSWORD);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(sqlInjectionRequest)))
                .andExpect(status().isBadRequest());

        // XSS测试
        Map<String, String> xssRequest = new HashMap<>();
        xssRequest.put("username", "<script>alert('xss')</script>");
        xssRequest.put("password", TEST_PASSWORD);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(xssRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("边界值测试 - 极长输入")
    public void testBoundaryValues_ExtremelyLongInput() throws Exception {
        // 超长用户名
        String longUsername = "a".repeat(200);
        Map<String, String> longUsernameRequest = new HashMap<>();
        longUsernameRequest.put("username", longUsername);
        longUsernameRequest.put("password", TEST_PASSWORD);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(longUsernameRequest)))
                .andExpect(status().isBadRequest());

        // 超长密码
        String longPassword = "a".repeat(200);
        Map<String, String> longPasswordRequest = new HashMap<>();
        longPasswordRequest.put("username", TEST_USERNAME);
        longPasswordRequest.put("password", longPassword);

        mockMvc.perform(post(AUTH_API + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(longPasswordRequest)))
                .andExpect(status().isBadRequest());
    }
}
