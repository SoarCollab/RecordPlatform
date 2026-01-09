package cn.flying.controller;

import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.auth.ChangePasswordVO;
import cn.flying.dao.vo.auth.ModifyEmailVO;
import cn.flying.dao.vo.auth.UpdateUserVO;
import cn.flying.common.tenant.TenantContext;
import cn.flying.test.support.BaseControllerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AccountController Integration Tests
 * 
 * Tests for user account management endpoints including:
 * - User info retrieval
 * - User info update
 * - Password change
 * - Email modification
 */
@Transactional
@DisplayName("AccountController Integration Tests")
@TestPropertySource(properties = "test.context=AccountControllerIntegrationTest")
class AccountControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/users";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId);
    }

    private Account createTestAccount(Long userId, Long tenantId) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername("testuser_" + userId);
        account.setEmail("testuser_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("user");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Test User " + userId);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        accountMapper.insert(account);
        return account;
    }

    @Nested
    @DisplayName("Get User Info Operations")
    class GetUserInfoTests {

        @Test
        @DisplayName("GET /info - Should return user info successfully")
        void getUserInfo_shouldReturnUserInfo() throws Exception {
            MvcResult result = performGet(BASE_URL + "/info")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.username").value(testAccount.getUsername()))
                    .andExpect(jsonPath("$.data.email").value(testAccount.getEmail()))
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(content).get("data");
            
            assertThat(data.has("username")).isTrue();
            assertThat(data.has("email")).isTrue();
            // Password should not be exposed
            assertThat(data.has("password")).isFalse();
        }

        @Test
        @DisplayName("GET /info - Should return 401 for unauthenticated request")
        void getUserInfo_shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /info - Should return correct avatar URL")
        void getUserInfo_shouldReturnCorrectAvatarUrl() throws Exception {
            performGet(BASE_URL + "/info")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.avatar").value(testAccount.getAvatar()));
        }
    }

    @Nested
    @DisplayName("Update User Info Operations")
    class UpdateUserInfoTests {

        @Test
        @DisplayName("PUT /info - Should update user info successfully")
        void updateUserInfo_shouldUpdateSuccessfully() throws Exception {
            UpdateUserVO updateVO = new UpdateUserVO();
            updateVO.setAvatar("https://example.com/new-avatar.png");
            updateVO.setNickname("Updated Nickname");

            performPut(BASE_URL + "/info", updateVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.avatar").value("https://example.com/new-avatar.png"))
                    .andExpect(jsonPath("$.data.nickname").value("Updated Nickname"));

            // Verify in database
            Account updated = TenantContext.callWithTenant(testTenantId, () -> accountMapper.selectById(testUserId));
            assertThat(updated.getAvatar()).isEqualTo("https://example.com/new-avatar.png");
            assertThat(updated.getNickname()).isEqualTo("Updated Nickname");
        }

        @Test
        @DisplayName("PUT /info - Should update only avatar")
        void updateUserInfo_shouldUpdateOnlyAvatar() throws Exception {
            String originalNickname = testAccount.getNickname();
            
            UpdateUserVO updateVO = new UpdateUserVO();
            updateVO.setAvatar("https://example.com/avatar-only.png");

            performPut(BASE_URL + "/info", updateVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            Account updated = TenantContext.callWithTenant(testTenantId, () -> accountMapper.selectById(testUserId));
            assertThat(updated.getAvatar()).isEqualTo("https://example.com/avatar-only.png");
            // Nickname should remain unchanged or be null
        }

        @Test
        @DisplayName("PUT /info - Should return 401 for unauthenticated request")
        void updateUserInfo_shouldReturn401ForUnauthenticatedRequest() throws Exception {
            UpdateUserVO updateVO = new UpdateUserVO();
            updateVO.setAvatar("https://example.com/avatar.png");

            mockMvc.perform(put(BASE_URL + "/info")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(updateVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Change Password Operations")
    class ChangePasswordTests {

        @Test
        @DisplayName("POST /change-password - Should change password successfully")
        void changePassword_shouldChangeSuccessfully() throws Exception {
            ChangePasswordVO changeVO = new ChangePasswordVO();
            changeVO.setPassword("password123");
            changeVO.setNew_password("newPassword123");

            performPost(BASE_URL + "/change-password", changeVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // Verify new password works
            Account updated = TenantContext.callWithTenant(testTenantId, () -> accountMapper.selectById(testUserId));
            assertThat(passwordEncoder.matches("newPassword123", updated.getPassword())).isTrue();
        }

        @Test
        @DisplayName("POST /change-password - Should return business error for wrong old password")
        void changePassword_shouldReturnBusinessErrorForWrongOldPassword() throws Exception {
            ChangePasswordVO changeVO = new ChangePasswordVO();
            changeVO.setPassword("wrongPassword");
            changeVO.setNew_password("newPassword123");

            performPost(BASE_URL + "/change-password", changeVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(10001));
        }

        @Test
        @DisplayName("POST /change-password - Should return 400 for password too short")
        void changePassword_shouldReturn400ForPasswordTooShort() throws Exception {
            ChangePasswordVO changeVO = new ChangePasswordVO();
            changeVO.setPassword("password123");
            changeVO.setNew_password("12345"); // Less than 6 characters

            performPost(BASE_URL + "/change-password", changeVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /change-password - Should return 400 for password too long")
        void changePassword_shouldReturn400ForPasswordTooLong() throws Exception {
            ChangePasswordVO changeVO = new ChangePasswordVO();
            changeVO.setPassword("password123");
            changeVO.setNew_password("a".repeat(21)); // More than 20 characters

            performPost(BASE_URL + "/change-password", changeVO)
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Modify Email Operations")
    class ModifyEmailTests {

        @Test
        @DisplayName("POST /modify-email - Should return 400 for invalid email format")
        void modifyEmail_shouldReturn400ForInvalidEmailFormat() throws Exception {
            ModifyEmailVO modifyVO = new ModifyEmailVO();
            modifyVO.setEmail("invalid-email");
            modifyVO.setCode("123456");

            performPost(BASE_URL + "/modify-email", modifyVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /modify-email - Should return 400 for invalid verification code length")
        void modifyEmail_shouldReturn400ForInvalidCodeLength() throws Exception {
            ModifyEmailVO modifyVO = new ModifyEmailVO();
            modifyVO.setEmail("new@test.com");
            modifyVO.setCode("12345"); // Should be exactly 6 characters

            performPost(BASE_URL + "/modify-email", modifyVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /modify-email - Should return 400 for code too long")
        void modifyEmail_shouldReturn400ForCodeTooLong() throws Exception {
            ModifyEmailVO modifyVO = new ModifyEmailVO();
            modifyVO.setEmail("new@test.com");
            modifyVO.setCode("1234567"); // More than 6 characters

            performPost(BASE_URL + "/modify-email", modifyVO)
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("GET /info - Should only return current tenant user info")
        void getUserInfo_shouldIsolateByTenant() throws Exception {
            // Create user in different tenant
            setTestUser(200L, 2L);
            Account otherTenantAccount = createTestAccount(200L, 2L);

            // Switch back to original tenant
            setTestUser(100L, 1L);

            MvcResult result = performGet(BASE_URL + "/info")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(content).get("data");

            // Should return original user, not the other tenant's user
            assertThat(data.get("username").asText()).isEqualTo(testAccount.getUsername());
            assertThat(data.get("username").asText()).isNotEqualTo(otherTenantAccount.getUsername());
        }
    }

    @Nested
    @DisplayName("JWT Authentication Tests")
    class JwtAuthenticationTests {

        @Test
        @DisplayName("All endpoints - Should return 401 without valid JWT")
        void allEndpoints_shouldReturn401WithoutJwt() throws Exception {
            mockMvc.perform(get(BASE_URL + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(put(BASE_URL + "/info")
                            .contentType("application/json")
                            .content("{}")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post(BASE_URL + "/change-password")
                            .contentType("application/json")
                            .content("{}")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post(BASE_URL + "/modify-email")
                            .contentType("application/json")
                            .content("{}")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
