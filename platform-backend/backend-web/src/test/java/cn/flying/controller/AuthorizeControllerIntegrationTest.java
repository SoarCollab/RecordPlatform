package cn.flying.controller;

import cn.flying.common.util.Const;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.vo.auth.ConfirmResetVO;
import cn.flying.dao.vo.auth.EmailRegisterVO;
import cn.flying.dao.vo.auth.EmailResetVO;
import cn.flying.test.support.BaseControllerIntegrationTest;
import cn.flying.test.support.JwtTestSupport;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * AuthorizeController Integration Tests
 *
 * Tests for authentication endpoints including:
 * - Email verification code request
 * - User registration
 * - Password reset confirmation
 * - Password reset
 * - Token refresh
 * - SSE token generation
 */
@Transactional
@DisplayName("AuthorizeController Integration Tests")
@TestPropertySource(properties = "test.context=AuthorizeControllerIntegrationTest")
class AuthorizeControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/auth";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private Account existingAccount;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        clearVerifyEmailRateLimit();
        existingAccount = createTestAccount(100L, 1L);
    }

    /**
     * 清理邮件验证码获取的 IP 限流 Key，避免测试之间因为同一 remoteAddr 共享而出现“请求频繁”。
     */
    private void clearVerifyEmailRateLimit() {
        stringRedisTemplate.delete(List.of(
                Const.VERIFY_EMAIL_LIMIT + "127.0.0.1",
                Const.VERIFY_EMAIL_LIMIT + "0:0:0:0:0:0:0:1"
        ));
    }

    private Account createTestAccount(Long userId, Long tenantId) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername("existinguser");
        account.setEmail("existing@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("user");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Existing User");
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        accountMapper.insert(account);
        return account;
    }

    /**
     * 在 Redis 中写入邮件验证码，模拟 /api/v1/auth/ask-code 写入的验证码键。
     *
     * @param email 邮箱
     * @param code  验证码
     */
    private void setVerificationCode(String email, String code) {
        String key = Const.VERIFY_EMAIL_DATA + email;
        stringRedisTemplate.opsForValue().set(key, code, 5, TimeUnit.MINUTES);
    }

    @Nested
    @DisplayName("Request Verification Code Operations")
    class AskVerifyCodeTests {

        @Test
        @DisplayName("GET /ask-code - Should request registration code for new email")
        void askCode_shouldRequestRegistrationCodeForNewEmail() throws Exception {
            mockMvc.perform(get(BASE_URL + "/ask-code")
                            .param("email", "newuser@test.com")
                            .param("type", "register")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /ask-code - Should return 400 for invalid email format")
        void askCode_shouldReturn400ForInvalidEmail() throws Exception {
            mockMvc.perform(get(BASE_URL + "/ask-code")
                            .param("email", "invalid-email")
                            .param("type", "register")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET /ask-code - Should return 400 for invalid type")
        void askCode_shouldReturn400ForInvalidType() throws Exception {
            mockMvc.perform(get(BASE_URL + "/ask-code")
                            .param("email", "test@test.com")
                            .param("type", "invalid")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET /ask-code - Should accept reset type for existing email")
        void askCode_shouldAcceptResetTypeForExistingEmail() throws Exception {
            mockMvc.perform(get(BASE_URL + "/ask-code")
                            .param("email", existingAccount.getEmail())
                            .param("type", "reset")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /ask-code - Should accept modify type")
        void askCode_shouldAcceptModifyType() throws Exception {
            mockMvc.perform(get(BASE_URL + "/ask-code")
                            .param("email", existingAccount.getEmail())
                            .param("type", "modify")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("User Registration Operations")
    class RegisterTests {

        @Test
        @DisplayName("POST /register - Should register new user successfully")
        void register_shouldRegisterNewUserSuccessfully() throws Exception {
            String newEmail = "newuser@test.com";
            setVerificationCode(newEmail, "123456");

            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail(newEmail);
            registerVO.setCode("123456");
            registerVO.setUsername("newuser");
            registerVO.setPassword("password123");
            registerVO.setNickname("New User");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // Verify account was created
            Account created = TenantContext.callWithTenant(testTenantId, () -> accountMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Account>()
                            .eq(Account::getEmail, newEmail)
            ));
            assertThat(created).isNotNull();
            assertThat(created.getUsername()).isEqualTo("newuser");
            assertThat(passwordEncoder.matches("password123", created.getPassword())).isTrue();
        }

        @Test
        @DisplayName("POST /register - Should return 400 for invalid email format")
        void register_shouldReturn400ForInvalidEmail() throws Exception {
            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail("invalid-email");
            registerVO.setCode("123456");
            registerVO.setUsername("newuser");
            registerVO.setPassword("password123");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /register - Should return 400 for code length not 6")
        void register_shouldReturn400ForInvalidCodeLength() throws Exception {
            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail("new@test.com");
            registerVO.setCode("12345"); // Should be 6 chars
            registerVO.setUsername("newuser");
            registerVO.setPassword("password123");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /register - Should return 400 for password too short")
        void register_shouldReturn400ForPasswordTooShort() throws Exception {
            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail("new@test.com");
            registerVO.setCode("123456");
            registerVO.setUsername("newuser");
            registerVO.setPassword("12345"); // Less than 6 chars

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /register - Should return 400 for password too long")
        void register_shouldReturn400ForPasswordTooLong() throws Exception {
            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail("new@test.com");
            registerVO.setCode("123456");
            registerVO.setUsername("newuser");
            registerVO.setPassword("a".repeat(21)); // More than 20 chars

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /register - Should return 400 for username too long")
        void register_shouldReturn400ForUsernameTooLong() throws Exception {
            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail("new@test.com");
            registerVO.setCode("123456");
            registerVO.setUsername("a".repeat(11)); // More than 10 chars
            registerVO.setPassword("password123");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /register - Should return 400 for invalid username characters")
        void register_shouldReturn400ForInvalidUsernameCharacters() throws Exception {
            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail("new@test.com");
            registerVO.setCode("123456");
            registerVO.setUsername("user@name"); // Contains special char
            registerVO.setPassword("password123");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Password Reset Confirmation Operations")
    class ResetConfirmTests {

        @Test
        @DisplayName("POST /reset-confirm - Should confirm reset with valid code")
        void resetConfirm_shouldConfirmWithValidCode() throws Exception {
            setVerificationCode(existingAccount.getEmail(), "123456");

            ConfirmResetVO confirmVO = new ConfirmResetVO(existingAccount.getEmail(), "123456");

            mockMvc.perform(post(BASE_URL + "/reset-confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /reset-confirm - Should return 400 for invalid email format")
        void resetConfirm_shouldReturn400ForInvalidEmail() throws Exception {
            ConfirmResetVO confirmVO = new ConfirmResetVO("invalid-email", "123456");

            mockMvc.perform(post(BASE_URL + "/reset-confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /reset-confirm - Should return 400 for code length not 6")
        void resetConfirm_shouldReturn400ForInvalidCodeLength() throws Exception {
            ConfirmResetVO confirmVO = new ConfirmResetVO(existingAccount.getEmail(), "12345");

            mockMvc.perform(post(BASE_URL + "/reset-confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(confirmVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Password Reset Operations")
    class ResetPasswordTests {

        @Test
        @DisplayName("POST /reset-password - Should reset password successfully")
        void resetPassword_shouldResetSuccessfully() throws Exception {
            setVerificationCode(existingAccount.getEmail(), "123456");

            EmailResetVO resetVO = new EmailResetVO();
            resetVO.setEmail(existingAccount.getEmail());
            resetVO.setCode("123456");
            resetVO.setPassword("newPassword123");

            mockMvc.perform(post(BASE_URL + "/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            // Verify password was changed
            Account updated = TenantContext.callWithTenant(testTenantId, () -> accountMapper.selectById(existingAccount.getId()));
            assertThat(passwordEncoder.matches("newPassword123", updated.getPassword())).isTrue();
        }

        @Test
        @DisplayName("POST /reset-password - Should return 400 for invalid email")
        void resetPassword_shouldReturn400ForInvalidEmail() throws Exception {
            EmailResetVO resetVO = new EmailResetVO();
            resetVO.setEmail("invalid-email");
            resetVO.setCode("123456");
            resetVO.setPassword("newPassword123");

            mockMvc.perform(post(BASE_URL + "/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /reset-password - Should return 400 for password too short")
        void resetPassword_shouldReturn400ForPasswordTooShort() throws Exception {
            EmailResetVO resetVO = new EmailResetVO();
            resetVO.setEmail(existingAccount.getEmail());
            resetVO.setCode("123456");
            resetVO.setPassword("12345"); // Less than 6 chars

            mockMvc.perform(post(BASE_URL + "/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("POST /reset-password - Should return 400 for password too long")
        void resetPassword_shouldReturn400ForPasswordTooLong() throws Exception {
            EmailResetVO resetVO = new EmailResetVO();
            resetVO.setEmail(existingAccount.getEmail());
            resetVO.setCode("123456");
            resetVO.setPassword("a".repeat(21)); // More than 20 chars

            mockMvc.perform(post(BASE_URL + "/reset-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(resetVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Token Refresh Operations")
    class RefreshTokenTests {

        @Test
        @DisplayName("POST /refresh - Should refresh token successfully")
        void refreshToken_shouldRefreshSuccessfully() throws Exception {
            mockMvc.perform(post(BASE_URL + "/refresh")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.token").isNotEmpty())
                    .andExpect(jsonPath("$.data.expire").isString())
                    .andExpect(jsonPath("$.data.expire").isNotEmpty());
        }

        @Test
        @DisplayName("POST /refresh - Should return 401 without token")
        void refreshToken_shouldReturn401WithoutToken() throws Exception {
            mockMvc.perform(post(BASE_URL + "/refresh")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /refresh - Should return 401 for expired token")
        void refreshToken_shouldReturn401ForExpiredToken() throws Exception {
            String expiredToken = JwtTestSupport.generateExpiredToken(testUserId);

            mockMvc.perform(post(BASE_URL + "/refresh")
                            .header("Authorization", "Bearer " + expiredToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /refresh - Should return 401 for invalid token")
        void refreshToken_shouldReturn401ForInvalidToken() throws Exception {
            mockMvc.perform(post(BASE_URL + "/refresh")
                            .header("Authorization", "Bearer invalid-token")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("SSE Token Operations")
    class SseTokenTests {

        @Test
        @DisplayName("POST /sse-token - Should generate SSE token successfully")
        void getSseToken_shouldGenerateSuccessfully() throws Exception {
            mockMvc.perform(post(BASE_URL + "/sse-token")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.sseToken").isNotEmpty())
                    .andExpect(jsonPath("$.data.expiresIn").value("30"));
        }

        @Test
        @DisplayName("POST /sse-token - Should return 401 without authentication")
        void getSseToken_shouldReturn401WithoutAuth() throws Exception {
            mockMvc.perform(post(BASE_URL + "/sse-token")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("POST /sse-token - Should return 401 for expired token")
        void getSseToken_shouldReturn401ForExpiredToken() throws Exception {
            String expiredToken = JwtTestSupport.generateExpiredToken(testUserId);

            mockMvc.perform(post(BASE_URL + "/sse-token")
                            .header("Authorization", "Bearer " + expiredToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Input Validation Tests")
    class InputValidationTests {

        @Test
        @DisplayName("POST /register - Should accept Chinese characters in username")
        void register_shouldAcceptChineseUsername() throws Exception {
            String newEmail = "chinese@test.com";
            setVerificationCode(newEmail, "123456");

            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail(newEmail);
            registerVO.setCode("123456");
            registerVO.setUsername("用户名");
            registerVO.setPassword("password123");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /register - Should accept alphanumeric username")
        void register_shouldAcceptAlphanumericUsername() throws Exception {
            String newEmail = "alpha@test.com";
            setVerificationCode(newEmail, "123456");

            EmailRegisterVO registerVO = new EmailRegisterVO();
            registerVO.setEmail(newEmail);
            registerVO.setCode("123456");
            registerVO.setUsername("User123");
            registerVO.setPassword("password123");

            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(registerVO))
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("POST /register - Should reject empty request body")
        void register_shouldRejectEmptyBody() throws Exception {
            mockMvc.perform(post(BASE_URL + "/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }
    }
}
