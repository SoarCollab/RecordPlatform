package cn.flying.identity.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InputValidatorTest {

    @Nested
    @DisplayName("Email Validation")
    class EmailValidation {
        @Test
        @DisplayName("accepts RFC compliant email")
        void shouldAcceptValidEmail() {
            assertTrue(InputValidator.isValidEmail("user.test+alias@example.com"));
        }

        @Test
        @DisplayName("rejects malformed email")
        void shouldRejectInvalidEmail() {
            assertFalse(InputValidator.isValidEmail("user@@example..com"));
        }

        @Test
        @DisplayName("rejects overly long email")
        void shouldRejectTooLongEmail() {
            String localPart = "a".repeat(200);
            String domain = "b".repeat(55);
            String email = localPart + "@" + domain + ".com";
            assertFalse(InputValidator.isValidEmail(email));
        }
    }

    @Nested
    @DisplayName("Username Validation")
    class UsernameValidation {
        @Test
        @DisplayName("accepts alphanumeric usernames")
        void shouldAcceptValidUsername() {
            assertTrue(InputValidator.isValidUsername("User_123"));
        }

        @Test
        @DisplayName("rejects short usernames")
        void shouldRejectShortUsername() {
            assertFalse(InputValidator.isValidUsername("abc"));
        }

        @Test
        @DisplayName("rejects usernames with special chars")
        void shouldRejectUsernameWithSpecialCharacters() {
            assertFalse(InputValidator.isValidUsername("user-name"));
        }
    }

    @Nested
    @DisplayName("Password Validation")
    class PasswordValidation {
        @Test
        @DisplayName("accepts strong password")
        void shouldAcceptValidPassword() {
            assertTrue(InputValidator.isValidPassword("Aa123456"));
        }

        @Test
        @DisplayName("rejects password without uppercase")
        void shouldRejectPasswordMissingUppercase() {
            assertFalse(InputValidator.isValidPassword("aa123456"));
        }

        @Test
        @DisplayName("rejects password without digit")
        void shouldRejectPasswordMissingDigit() {
            assertFalse(InputValidator.isValidPassword("Aabcdefg"));
        }

        @Test
        @DisplayName("rejects overly short password")
        void shouldRejectShortPassword() {
            assertFalse(InputValidator.isValidPassword("Aa1"));
        }
    }

    @Nested
    @DisplayName("Security Patterns")
    class SecurityPatternDetection {
        @Test
        @DisplayName("flags SQL injection keyword")
        void shouldDetectSqlInjection() {
            assertTrue(InputValidator.containsSqlInjection("SELECT * FROM users;"));
        }

        @Test
        @DisplayName("ignores safe text for SQL injection")
        void shouldNotDetectSqlInjectionInSafeText() {
            assertFalse(InputValidator.containsSqlInjection("Fetch records by id"));
        }

        @Test
        @DisplayName("flags inline script as XSS")
        void shouldDetectXssPayload() {
            assertTrue(InputValidator.containsXss("<script>alert('xss')</script>"));
        }

        @Test
        @DisplayName("ignores plain text for XSS")
        void shouldNotDetectXssInSafeText() {
            assertFalse(InputValidator.containsXss("Hello world"));
        }

        @Test
        @DisplayName("sanitizes html and sql characters")
        void shouldSanitizeDangerousInput() {
            String sanitized = InputValidator.sanitizeInput("<b>alert('hack');</b> --test");
            assertEquals("alert(hack) test", sanitized);
        }
    }

    @Nested
    @DisplayName("Miscellaneous Validation")
    class MiscValidation {
        @Test
        void shouldValidateVerificationCode() {
            assertTrue(InputValidator.isValidVerificationCode("123456"));
            assertFalse(InputValidator.isValidVerificationCode("12345"));
        }

        @Test
        void shouldValidateClientId() {
            assertTrue(InputValidator.isValidClientId("client_1234"));
            assertFalse(InputValidator.isValidClientId("short"));
            assertFalse(InputValidator.isValidClientId("client-123"));
        }

        @Test
        void shouldValidateRedirectUri() {
            assertTrue(InputValidator.isValidRedirectUri("https://example.com/callback"));
            assertFalse(InputValidator.isValidRedirectUri("ftp://example.com"));
            assertFalse(InputValidator.isValidRedirectUri("https://example.com/../../etc/passwd"));
        }

        @Test
        void shouldValidatePagination() {
            assertTrue(InputValidator.isValidPagination(1, 20));
            assertFalse(InputValidator.isValidPagination(null, 20));
            assertFalse(InputValidator.isValidPagination(0, 20));
            assertFalse(InputValidator.isValidPagination(1, 0));
        }
    }
}
