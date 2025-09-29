package cn.flying.identity.integration;

import cn.flying.identity.dto.Account;
import cn.flying.identity.dto.UserUpdateRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理控制器集成测试
 * 测试完整的用户管理流程，包括数据库交互和认证
 *
 * @author AI Assistant
 * @since 2025-01-16
 */
@DisplayName("用户管理集成测试")
public class UserControllerIntegrationTest extends BaseIntegrationTest {

    private static final String API_BASE = "/identity/api/users";

    @Test
    @DisplayName("获取当前用户信息 - 成功")
    public void testGetCurrentUser_Success() throws Exception {
        // 登录测试用户
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        mockMvc.perform(get(API_BASE + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.data.email").value(TEST_EMAIL))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("获取当前用户信息 - 未登录")
    public void testGetCurrentUser_NotLoggedIn() throws Exception {
        mockMvc.perform(get(API_BASE + "/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("更新用户信息 - 成功")
    public void testUpdateCurrentUser_Success() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        UserUpdateRequest request = new UserUpdateRequest();
        request.setAvatar("https://example.com/new-avatar.jpg");

        mockMvc.perform(patch(API_BASE + "/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.avatar").value("https://example.com/new-avatar.jpg"));
    }

    @Test
    @DisplayName("删除用户 - 成功")
    public void testDeleteUser_Success() throws Exception {
        // 创建一个新用户用于删除
        Account userToDelete = createTestAccount("usertodelete", "delete@example.com", TEST_PASSWORD);
        loginAs(userToDelete.getId());
        String token = getAuthToken(userToDelete.getId());

        mockMvc.perform(delete(API_BASE + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 验证用户已被逻辑删除
        Account deletedAccount = accountMapper.selectById(userToDelete.getId());
        assert deletedAccount == null || deletedAccount.getDeleted() == 1;
    }

    @Test
    @DisplayName("管理员查询用户列表")
    public void testListUsers_AsAdmin() throws Exception {
        // 创建管理员账户
        Account admin = createAdminAccount("admin", "admin@example.com");
        loginAs(admin.getId());
        String token = getAuthToken(admin.getId());

        // 创建几个测试用户
        createTestAccount("user1", "user1@example.com", TEST_PASSWORD);
        createTestAccount("user2", "user2@example.com", TEST_PASSWORD);

        mockMvc.perform(get(API_BASE)
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @DisplayName("普通用户无法查询用户列表")
    public void testListUsers_AsNormalUser_Forbidden() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        mockMvc.perform(get(API_BASE)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("根据ID获取用户 - 成功")
    public void testGetUserById_Success() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        mockMvc.perform(get(API_BASE + "/" + testAccount.getId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME));
    }

    @Test
    @DisplayName("根据ID获取用户 - 用户不存在")
    public void testGetUserById_NotFound() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        mockMvc.perform(get(API_BASE + "/999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("搜索用户 - 按用户名")
    public void testSearchUsers_ByUsername() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        // 创建多个用户用于搜索
        createTestAccount("searchuser1", "search1@example.com", TEST_PASSWORD);
        createTestAccount("searchuser2", "search2@example.com", TEST_PASSWORD);

        mockMvc.perform(get(API_BASE + "/search")
                        .header("Authorization", "Bearer " + token)
                        .param("keyword", "searchuser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @DisplayName("更新用户邮箱 - 成功")
    public void testUpdateUserEmail_Success() throws Exception {
        loginAsTestUser();
        String token = getAuthToken(testAccount.getId());

        String newEmail = "newemail@example.com";

        mockMvc.perform(patch(API_BASE + "/me/email")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + newEmail + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200));
    }

    @Test
    @DisplayName("完整用户生命周期测试")
    public void testUserLifecycle() throws Exception {
        // 1. 创建新用户
        Account newUser = createTestAccount("lifecycleuser", "lifecycle@example.com", TEST_PASSWORD);
        loginAs(newUser.getId());
        String token = getAuthToken(newUser.getId());

        // 2. 获取用户信息
        MvcResult result = mockMvc.perform(get(API_BASE + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        // 3. 更新用户信息
        UserUpdateRequest updateRequest = new UserUpdateRequest();
        updateRequest.setAvatar("https://example.com/avatar.jpg");

        mockMvc.perform(patch(API_BASE + "/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatar").value("https://example.com/avatar.jpg"));

        // 4. 删除用户
        mockMvc.perform(delete(API_BASE + "/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        // 5. 验证用户已被删除
        Account deletedUser = accountMapper.selectById(newUser.getId());
        assert deletedUser == null || deletedUser.getDeleted() == 1;
    }
}
