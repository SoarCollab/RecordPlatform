package cn.flying.service.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ChunkEncryptionStrategy implementations.
 * Tests both AES-GCM and ChaCha20-Poly1305 strategies.
 */
@DisplayName("ChunkEncryptionStrategy Tests")
class ChunkEncryptionStrategyTest {

    /**
     * 提供所有分片加密策略实现，用于参数化测试同时覆盖 AES-GCM 与 ChaCha20-Poly1305。
     *
     * @return 加密策略实现流
     */
    static Stream<ChunkEncryptionStrategy> encryptionStrategies() {
        return Stream.of(
                new AesGcmEncryptionStrategy(),
                new ChaCha20EncryptionStrategy()
        );
    }

    @Nested
    @DisplayName("Key Generation")
    class KeyGeneration {

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should generate 256-bit key")
        void shouldGenerate256BitKey(ChunkEncryptionStrategy strategy) {
            SecretKey key = strategy.generateKey();

            assertNotNull(key);
            assertEquals(32, key.getEncoded().length); // 256 bits = 32 bytes
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should generate unique keys each time")
        void shouldGenerateUniqueKeys(ChunkEncryptionStrategy strategy) {
            SecretKey key1 = strategy.generateKey();
            SecretKey key2 = strategy.generateKey();

            assertFalse(Arrays.equals(key1.getEncoded(), key2.getEncoded()));
        }
    }

    @Nested
    @DisplayName("IV Generation")
    class IvGeneration {

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should generate IV with correct size")
        void shouldGenerateIvWithCorrectSize(ChunkEncryptionStrategy strategy) {
            byte[] iv = strategy.generateIv();

            assertNotNull(iv);
            assertEquals(strategy.getIvSize(), iv.length);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should generate unique IVs each time")
        void shouldGenerateUniqueIvs(ChunkEncryptionStrategy strategy) {
            byte[] iv1 = strategy.generateIv();
            byte[] iv2 = strategy.generateIv();

            assertFalse(Arrays.equals(iv1, iv2));
        }
    }

    @Nested
    @DisplayName("Encryption/Decryption Round Trip")
    class RoundTrip {

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should encrypt and decrypt small data correctly")
        void shouldRoundTripSmallData(ChunkEncryptionStrategy strategy) throws EncryptionException {
            String plaintext = "Hello, World!";
            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintextBytes, key, iv);
            byte[] decrypted = strategy.decrypt(ciphertext, key, iv);

            assertArrayEquals(plaintextBytes, decrypted);
            assertEquals(plaintext, new String(decrypted, StandardCharsets.UTF_8));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should encrypt and decrypt large data correctly")
        void shouldRoundTripLargeData(ChunkEncryptionStrategy strategy) throws EncryptionException {
            // 1MB of random data
            byte[] plaintext = new byte[1024 * 1024];
            new Random(42).nextBytes(plaintext);

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, key, iv);
            byte[] decrypted = strategy.decrypt(ciphertext, key, iv);

            assertArrayEquals(plaintext, decrypted);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should encrypt and decrypt empty data")
        void shouldRoundTripEmptyData(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = new byte[0];

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, key, iv);
            byte[] decrypted = strategy.decrypt(ciphertext, key, iv);

            assertArrayEquals(plaintext, decrypted);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("ciphertext should be larger than plaintext due to auth tag")
        void ciphertextShouldBeLargerThanPlaintext(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = "Test data".getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, key, iv);

            // GCM/ChaCha20-Poly1305 adds 16-byte auth tag
            assertTrue(ciphertext.length > plaintext.length);
            assertEquals(plaintext.length + (strategy.getTagBitLength() / 8), ciphertext.length);
        }
    }

    @Nested
    @DisplayName("Tamper Detection")
    class TamperDetection {

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should fail on tampered ciphertext")
        void shouldFailOnTamperedCiphertext(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = "Sensitive data".getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, key, iv);

            // Tamper with ciphertext (flip a bit)
            ciphertext[ciphertext.length / 2] ^= 0xFF;

            assertThrows(EncryptionException.class, () ->
                    strategy.decrypt(ciphertext, key, iv)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should fail on wrong key")
        void shouldFailOnWrongKey(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = "Secret message".getBytes(StandardCharsets.UTF_8);

            SecretKey correctKey = strategy.generateKey();
            SecretKey wrongKey = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, correctKey, iv);

            assertThrows(EncryptionException.class, () ->
                    strategy.decrypt(ciphertext, wrongKey, iv)
            );
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should fail on wrong IV")
        void shouldFailOnWrongIv(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = "Protected content".getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] correctIv = strategy.generateIv();
            byte[] wrongIv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, key, correctIv);

            assertThrows(EncryptionException.class, () ->
                    strategy.decrypt(ciphertext, key, wrongIv)
            );
        }
    }

    @Nested
    @DisplayName("Streaming Encryption")
    class StreamingEncryption {

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("should stream encrypt and decrypt correctly")
        void shouldStreamEncryptDecryptCorrectly(ChunkEncryptionStrategy strategy) throws Exception {
            byte[] plaintext = "Streaming encryption test data that spans multiple chunks".getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            // Encrypt in chunks
            EncryptionContext encCtx = strategy.createEncryptionContext(key, iv);
            byte[] encrypted1 = strategy.encryptUpdate(encCtx, plaintext, 0, 20);
            byte[] encrypted2 = strategy.encryptUpdate(encCtx, plaintext, 20, plaintext.length - 20);
            byte[] encryptedFinal = strategy.encryptFinal(encCtx);

            // Combine encrypted parts
            byte[] ciphertext = new byte[encrypted1.length + encrypted2.length + encryptedFinal.length];
            System.arraycopy(encrypted1, 0, ciphertext, 0, encrypted1.length);
            System.arraycopy(encrypted2, 0, ciphertext, encrypted1.length, encrypted2.length);
            System.arraycopy(encryptedFinal, 0, ciphertext, encrypted1.length + encrypted2.length, encryptedFinal.length);

            // Decrypt
            EncryptionContext decCtx = strategy.createDecryptionContext(key, iv);
            byte[] decrypted1 = strategy.decryptUpdate(decCtx, ciphertext, 0, ciphertext.length);
            byte[] decryptedFinal = strategy.decryptFinal(decCtx);

            // Combine decrypted parts
            byte[] decrypted = new byte[decrypted1.length + decryptedFinal.length];
            System.arraycopy(decrypted1, 0, decrypted, 0, decrypted1.length);
            System.arraycopy(decryptedFinal, 0, decrypted, decrypted1.length, decryptedFinal.length);

            assertArrayEquals(plaintext, decrypted);
        }
    }

    @Nested
    @DisplayName("Algorithm Properties")
    class AlgorithmProperties {

        @Test
        @DisplayName("AES-GCM should have correct properties")
        void aesGcmShouldHaveCorrectProperties() {
            AesGcmEncryptionStrategy strategy = new AesGcmEncryptionStrategy();

            assertEquals("AES-256-GCM", strategy.getAlgorithmName());
            assertEquals(12, strategy.getIvSize());
            assertEquals(128, strategy.getTagBitLength());
        }

        @Test
        @DisplayName("ChaCha20 should have correct properties")
        void chacha20ShouldHaveCorrectProperties() {
            ChaCha20EncryptionStrategy strategy = new ChaCha20EncryptionStrategy();

            assertEquals("ChaCha20-Poly1305", strategy.getAlgorithmName());
            assertEquals(12, strategy.getIvSize());
            assertEquals(128, strategy.getTagBitLength());
        }
    }

    @Nested
    @DisplayName("Encryption Behavior")
    class EncryptionBehavior {

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("different IVs should produce different ciphertext")
        void differentIvsShouldProduceDifferentCiphertext(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = "Same plaintext".getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] iv1 = strategy.generateIv();
            byte[] iv2 = strategy.generateIv();

            byte[] ciphertext1 = strategy.encrypt(plaintext, key, iv1);
            byte[] ciphertext2 = strategy.encrypt(plaintext, key, iv2);

            assertFalse(Arrays.equals(ciphertext1, ciphertext2),
                    "Different IVs should produce different ciphertexts");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("different keys should produce different ciphertext")
        void differentKeysShouldProduceDifferentCiphertext(ChunkEncryptionStrategy strategy) throws EncryptionException {
            byte[] plaintext = "Same plaintext".getBytes(StandardCharsets.UTF_8);

            SecretKey key1 = strategy.generateKey();
            SecretKey key2 = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext1 = strategy.encrypt(plaintext, key1, iv);
            byte[] ciphertext2 = strategy.encrypt(plaintext, key2, iv);

            assertFalse(Arrays.equals(ciphertext1, ciphertext2),
                    "Different keys should produce different ciphertexts");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cn.flying.service.encryption.ChunkEncryptionStrategyTest#encryptionStrategies")
        @DisplayName("ciphertext should not contain plaintext")
        void ciphertextShouldNotContainPlaintext(ChunkEncryptionStrategy strategy) throws EncryptionException {
            String secret = "TopSecret123!";
            byte[] plaintext = secret.getBytes(StandardCharsets.UTF_8);

            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();

            byte[] ciphertext = strategy.encrypt(plaintext, key, iv);
            String ciphertextStr = new String(ciphertext, StandardCharsets.ISO_8859_1);

            assertFalse(ciphertextStr.contains(secret),
                    "Ciphertext should not contain the original plaintext");
        }
    }
}
