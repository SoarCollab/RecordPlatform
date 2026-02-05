package cn.flying.common.util;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SecureIdCodec Tests")
class SecureIdCodecTest {

    private SecureIdCodec codec;

    @BeforeEach
    void setUp() {
        // Use a strong random test key (64 characters for full entropy)
        String testKey = "SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234";
        codec = new SecureIdCodec(testKey);
    }

    @Nested
    @DisplayName("Constructor Validation")
    class ConstructorTests {

        @Test
        @DisplayName("should throw for null key")
        void constructor_throwsForNullKey() {
            assertThatThrownBy(() -> new SecureIdCodec(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 32 characters");
        }

        @Test
        @DisplayName("should throw for short key")
        void constructor_throwsForShortKey() {
            assertThatThrownBy(() -> new SecureIdCodec("short"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 32 characters");
        }

        @Test
        @DisplayName("should accept valid key")
        void constructor_acceptsValidKey() {
            String validKey = "a".repeat(32);

            assertThatCode(() -> new SecureIdCodec(validKey))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Entity ID Encoding/Decoding")
    class EntityIdTests {

        @Test
        @DisplayName("should encode and decode entity ID correctly")
        void roundTrip_entityId() {
            Long originalId = 12345L;

            String externalId = codec.toExternalId(originalId);
            Long decodedId = codec.fromExternalId(externalId);

            assertThat(decodedId).isEqualTo(originalId);
        }

        @Test
        @DisplayName("should produce E-prefixed external ID")
        void toExternalId_hasEPrefix() {
            String externalId = codec.toExternalId(100L);

            assertThat(externalId).startsWith("E");
        }

        @Test
        @DisplayName("should return null for null input")
        void toExternalId_returnsNullForNullInput() {
            String result = codec.toExternalId(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should produce deterministic output")
        void toExternalId_isDeterministic() {
            Long id = 99999L;

            String result1 = codec.toExternalId(id);
            String result2 = codec.toExternalId(id);

            assertThat(result1).isEqualTo(result2);
        }

        @Test
        @DisplayName("should handle Long.MAX_VALUE")
        void roundTrip_maxValue() {
            Long maxValue = Long.MAX_VALUE;

            String externalId = codec.toExternalId(maxValue);
            Long decodedId = codec.fromExternalId(externalId);

            assertThat(decodedId).isEqualTo(maxValue);
        }

        @Test
        @DisplayName("should handle zero")
        void roundTrip_zero() {
            Long zero = 0L;

            String externalId = codec.toExternalId(zero);
            Long decodedId = codec.fromExternalId(externalId);

            assertThat(decodedId).isEqualTo(zero);
        }

        @Test
        @DisplayName("should handle negative ID")
        void roundTrip_negativeId() {
            Long negativeId = -12345L;

            String externalId = codec.toExternalId(negativeId);
            Long decodedId = codec.fromExternalId(externalId);

            assertThat(decodedId).isEqualTo(negativeId);
        }
    }

    @Nested
    @DisplayName("User ID Encoding/Decoding")
    class UserIdTests {

        @Test
        @DisplayName("should encode and decode user ID correctly")
        void roundTrip_userId() {
            Long originalUserId = 67890L;

            String externalUserId = codec.toExternalUserId(originalUserId);
            Long decodedUserId = codec.fromExternalId(externalUserId);

            assertThat(decodedUserId).isEqualTo(originalUserId);
        }

        @Test
        @DisplayName("should produce U-prefixed external user ID")
        void toExternalUserId_hasUPrefix() {
            String externalUserId = codec.toExternalUserId(100L);

            assertThat(externalUserId).startsWith("U");
        }

        @Test
        @DisplayName("should return null for null input")
        void toExternalUserId_returnsNullForNullInput() {
            String result = codec.toExternalUserId(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should differentiate user and entity IDs")
        void userAndEntityIds_areDifferent() {
            Long id = 12345L;

            String entityId = codec.toExternalId(id);
            String userId = codec.toExternalUserId(id);

            assertThat(entityId).isNotEqualTo(userId);
        }
    }

    @Nested
    @DisplayName("Decoding Error Handling")
    class DecodingErrorTests {

        @Test
        @DisplayName("should return null for null external ID")
        void fromExternalId_returnsNullForNull() {
            Long result = codec.fromExternalId(null);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for empty external ID")
        void fromExternalId_returnsNullForEmpty() {
            Long result = codec.fromExternalId("");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for single character")
        void fromExternalId_returnsNullForSingleChar() {
            Long result = codec.fromExternalId("E");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for invalid Base62")
        void fromExternalId_returnsNullForInvalidBase62() {
            Long result = codec.fromExternalId("E!!!invalid!!!");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for wrong length ciphertext")
        void fromExternalId_returnsNullForWrongLength() {
            Long result = codec.fromExternalId("Eabc");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for tampered ciphertext")
        void fromExternalId_returnsNullForTamperedData() {
            String validExternalId = codec.toExternalId(12345L);
            // Tamper with some characters in the middle
            char[] chars = validExternalId.toCharArray();
            if (chars.length > 10) {
                chars[10] = chars[10] == 'A' ? 'B' : 'A';
            }
            String tamperedId = new String(chars);

            Long result = codec.fromExternalId(tamperedId);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should reject entity ID decoded as user ID")
        void fromExternalId_rejectsWrongTypePrefix() {
            // Create an entity ID
            String entityExternalId = codec.toExternalId(12345L);
            // Try to decode it but pretend it starts with U
            String fakeUserId = "U" + entityExternalId.substring(1);

            Long result = codec.fromExternalId(fakeUserId);

            // Should fail because internal type marker doesn't match prefix
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Different Keys")
    class DifferentKeysTests {

        @Test
        @DisplayName("should produce different output with different keys")
        void differentKeys_produceDifferentOutput() {
            String key1 = "KeyOne12345678901234567890ABCDEF";
            String key2 = "KeyTwo12345678901234567890ABCDEF";

            SecureIdCodec codec1 = new SecureIdCodec(key1);
            SecureIdCodec codec2 = new SecureIdCodec(key2);

            String external1 = codec1.toExternalId(12345L);
            String external2 = codec2.toExternalId(12345L);

            assertThat(external1).isNotEqualTo(external2);
        }

        @Test
        @DisplayName("should not decode ID from different key")
        void differentKeys_cannotCrossDecode() {
            String key1 = "KeyOne12345678901234567890ABCDEF";
            String key2 = "KeyTwo12345678901234567890ABCDEF";

            SecureIdCodec codec1 = new SecureIdCodec(key1);
            SecureIdCodec codec2 = new SecureIdCodec(key2);

            String external = codec1.toExternalId(12345L);
            Long result = codec2.fromExternalId(external);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("Bulk Operations")
    class BulkOperationsTests {

        @Test
        @DisplayName("should handle common ID patterns correctly")
        void bulkEncodeDecode_worksCorrectly() {
            // Test specific ID patterns that are commonly used
            long[] testIds = {
                    1L, 2L, 10L, 100L, 1000L, 10000L, 100000L, 1000000L,
                    Long.MAX_VALUE, Long.MAX_VALUE - 1, Long.MAX_VALUE / 2,
                    123456789L, 999999999L, 1234567890123456789L
            };

            for (long id : testIds) {
                String externalId = codec.toExternalId(id);
                Long decodedId = codec.fromExternalId(externalId);

                assertThat(decodedId)
                        .as("ID %d should round-trip correctly", id)
                        .isEqualTo(id);
            }
        }

        @Test
        @DisplayName("should produce unique external IDs for unique internal IDs")
        void uniqueInternalIds_produceUniqueExternalIds() {
            java.util.Set<String> externalIds = new java.util.HashSet<>();

            for (long i = 1; i <= 100; i++) {
                String externalId = codec.toExternalId(i);
                boolean isNew = externalIds.add(externalId);

                assertThat(isNew)
                        .as("External ID for %d should be unique", i)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("should handle sequential IDs")
        void sequentialIds_allRoundTripCorrectly() {
            for (long i = 1; i <= 100; i++) {
                String externalId = codec.toExternalId(i);
                Long decodedId = codec.fromExternalId(externalId);
                assertThat(decodedId)
                        .as("Sequential ID %d should round-trip correctly", i)
                        .isEqualTo(i);
            }
        }
    }
}
