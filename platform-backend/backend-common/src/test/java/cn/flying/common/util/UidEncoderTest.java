package cn.flying.common.util;

import cn.flying.common.exception.GeneralException;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("UidEncoder Tests")
class UidEncoderTest {

    @Nested
    @DisplayName("encodeUid Tests")
    class EncodeUidTests {

        @Test
        @DisplayName("should encode UID to fixed length string")
        void encodeUid_producesFixedLengthOutput() {
            String result = UidEncoder.encodeUid("12345");

            assertThat(result).hasSize(12);
        }

        @Test
        @DisplayName("should produce consistent output for same input")
        void encodeUid_isConsistent() {
            String result1 = UidEncoder.encodeUid("test-uid-123");
            String result2 = UidEncoder.encodeUid("test-uid-123");

            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("should produce different output for different inputs")
        void encodeUid_differsByInput() {
            String result1 = UidEncoder.encodeUid("uid-a");
            String result2 = UidEncoder.encodeUid("uid-b");

            assertThat(result1).isNotEqualTo(result2);
        }

        @Test
        @DisplayName("should only contain alphabet characters")
        void encodeUid_containsOnlyAlphabetChars() {
            String result = UidEncoder.encodeUid("12345678");

            assertThat(result).matches("[A-Za-z]+");
        }

        @Test
        @DisplayName("should throw for null UID")
        void encodeUid_throwsForNull() {
            assertThatThrownBy(() -> UidEncoder.encodeUid(null))
                    .isInstanceOf(GeneralException.class)
                    .hasMessageContaining("UID不能为空");
        }

        @Test
        @DisplayName("should throw for empty UID")
        void encodeUid_throwsForEmpty() {
            assertThatThrownBy(() -> UidEncoder.encodeUid(""))
                    .isInstanceOf(GeneralException.class)
                    .hasMessageContaining("UID不能为空");
        }

        @Test
        @DisplayName("should handle long UID")
        void encodeUid_handlesLongInput() {
            String longUid = "a".repeat(1000);

            String result = UidEncoder.encodeUid(longUid);

            assertThat(result).hasSize(12);
            assertThat(result).matches("[A-Za-z]+");
        }

        @Test
        @DisplayName("should handle special characters in UID")
        void encodeUid_handlesSpecialChars() {
            String result = UidEncoder.encodeUid("uid@#$%^&*()!~");

            assertThat(result).hasSize(12);
            assertThat(result).matches("[A-Za-z]+");
        }

        @Test
        @DisplayName("should handle unicode characters in UID")
        void encodeUid_handlesUnicode() {
            String result = UidEncoder.encodeUid("用户ID中文测试");

            assertThat(result).hasSize(12);
            assertThat(result).matches("[A-Za-z]+");
        }
    }

    @Nested
    @DisplayName("encodeCid Tests")
    class EncodeCidTests {

        @Test
        @DisplayName("should encode CID with salt prefix")
        void encodeCid_producesSaltedOutput() {
            String result = UidEncoder.encodeCid("secure-uid-123");

            // Total length = SALT_LENGTH(4) + OUTPUT_LENGTH(12) = 16
            assertThat(result).hasSize(16);
        }

        @Test
        @DisplayName("should produce different output each call (random salt)")
        void encodeCid_producesRandomOutput() {
            String result1 = UidEncoder.encodeCid("same-uid");
            String result2 = UidEncoder.encodeCid("same-uid");

            // Results should likely differ due to random salt
            // (There's a tiny chance they could match if salt happens to be same)
            // We'll generate multiple to ensure at least one differs
            boolean foundDifferent = false;
            for (int i = 0; i < 10; i++) {
                String anotherResult = UidEncoder.encodeCid("same-uid");
                if (!anotherResult.equals(result1)) {
                    foundDifferent = true;
                    break;
                }
            }
            assertThat(foundDifferent).isTrue();
        }

        @Test
        @DisplayName("should only contain alphabet characters")
        void encodeCid_containsOnlyAlphabetChars() {
            String result = UidEncoder.encodeCid("test-cid");

            assertThat(result).matches("[A-Za-z]+");
        }

        @Test
        @DisplayName("should throw for null SUID")
        void encodeCid_throwsForNull() {
            assertThatThrownBy(() -> UidEncoder.encodeCid(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUID 不能为空");
        }

        @Test
        @DisplayName("should throw for empty SUID")
        void encodeCid_throwsForEmpty() {
            assertThatThrownBy(() -> UidEncoder.encodeCid(""))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("SUID 不能为空");
        }
    }

    @Nested
    @DisplayName("verifyCid Tests")
    class VerifyCidTests {

        @Test
        @DisplayName("should verify correctly encoded CID")
        void verifyCid_successForValidEncoding() {
            String originalCid = "my-secret-cid";
            String encoded = UidEncoder.encodeCid(originalCid);

            boolean result = UidEncoder.verifyCid(encoded, originalCid);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should fail for wrong original CID")
        void verifyCid_failsForWrongOriginal() {
            String encoded = UidEncoder.encodeCid("correct-cid");

            boolean result = UidEncoder.verifyCid(encoded, "wrong-cid");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should fail for null encoded value")
        void verifyCid_failsForNullEncoded() {
            boolean result = UidEncoder.verifyCid(null, "some-cid");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should fail for null original CID")
        void verifyCid_failsForNullOriginal() {
            String encoded = UidEncoder.encodeCid("some-cid");

            boolean result = UidEncoder.verifyCid(encoded, null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should fail for empty original CID")
        void verifyCid_failsForEmptyOriginal() {
            String encoded = UidEncoder.encodeCid("some-cid");

            boolean result = UidEncoder.verifyCid(encoded, "");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should fail for wrong length encoded value")
        void verifyCid_failsForWrongLength() {
            boolean resultTooShort = UidEncoder.verifyCid("abc", "some-cid");
            boolean resultTooLong = UidEncoder.verifyCid("a".repeat(20), "some-cid");

            assertThat(resultTooShort).isFalse();
            assertThat(resultTooLong).isFalse();
        }

        @Test
        @DisplayName("should fail for invalid characters in salt")
        void verifyCid_failsForInvalidSaltChars() {
            // Create a string with correct length but invalid chars in salt position
            String invalidSalt = "!@#$" + "a".repeat(12);

            boolean result = UidEncoder.verifyCid(invalidSalt, "some-cid");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should fail for tampered encoded value")
        void verifyCid_failsForTamperedValue() {
            String originalCid = "original-cid";
            String encoded = UidEncoder.encodeCid(originalCid);

            // Tamper with the encoded value (change last char)
            char[] chars = encoded.toCharArray();
            chars[chars.length - 1] = chars[chars.length - 1] == 'A' ? 'B' : 'A';
            String tampered = new String(chars);

            boolean result = UidEncoder.verifyCid(tampered, originalCid);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should verify multiple encodings of same CID")
        void verifyCid_worksForMultipleEncodings() {
            String originalCid = "multi-encode-test";

            for (int i = 0; i < 10; i++) {
                String encoded = UidEncoder.encodeCid(originalCid);
                assertThat(UidEncoder.verifyCid(encoded, originalCid)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("should handle encode-verify cycle for various inputs")
        void encodeVerifyCycle_worksForVariousInputs() {
            String[] testCids = {
                    "simple",
                    "with-dashes-123",
                    "with_underscores",
                    "CamelCase",
                    "a".repeat(100),
                    "special!@#$%",
                    "unicode中文"
            };

            for (String cid : testCids) {
                String encoded = UidEncoder.encodeCid(cid);
                assertThat(UidEncoder.verifyCid(encoded, cid))
                        .as("Verification should succeed for: " + cid)
                        .isTrue();
            }
        }
    }
}
