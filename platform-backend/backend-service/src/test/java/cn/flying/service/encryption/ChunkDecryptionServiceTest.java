package cn.flying.service.encryption;

import cn.flying.service.encryption.EncryptionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.SecretKey;
import java.security.SecureRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@DisplayName("ChunkDecryptionService Tests")
@ExtendWith(MockitoExtension.class)
class ChunkDecryptionServiceTest {

    @Mock
    private EncryptionProperties encryptionProperties;

    private ChunkDecryptionService decryptionService;
    private EncryptionStrategyFactory strategyFactory;
    private AesGcmEncryptionStrategy aesStrategy;
    private ChaCha20EncryptionStrategy chaChaStrategy;
    private static final SecureRandom RANDOM = new SecureRandom();

    @BeforeEach
    void setUp() {
        when(encryptionProperties.getAlgorithmEnum()).thenReturn(EncryptionAlgorithm.AES_GCM);
        strategyFactory = new EncryptionStrategyFactory(encryptionProperties);
        strategyFactory.initialize();
        decryptionService = new ChunkDecryptionService(strategyFactory);
        aesStrategy = new AesGcmEncryptionStrategy();
        chaChaStrategy = new ChaCha20EncryptionStrategy();
    }

    @Nested
    @DisplayName("decryptChunk")
    class DecryptChunk {

        @Test
        @DisplayName("should decrypt AES-GCM encrypted data successfully")
        void decryptAesGcm_success() throws EncryptionException {
            byte[] plaintext = "Hello, AES-GCM encryption test!".getBytes();
            SecretKey key = aesStrategy.generateKey();
            byte[] iv = aesStrategy.generateIv();
            byte[] encrypted = aesStrategy.encrypt(plaintext, key, iv);
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] encryptedWithHeader = concatenate(header, iv, encrypted);

            byte[] decrypted = decryptionService.decryptChunk(encryptedWithHeader, key.getEncoded());

            assertArrayEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("should decrypt ChaCha20-Poly1305 encrypted data successfully")
        void decryptChaCha20_success() throws EncryptionException {
            byte[] plaintext = "Hello, ChaCha20-Poly1305 encryption test!".getBytes();
            SecretKey key = chaChaStrategy.generateKey();
            byte[] iv = chaChaStrategy.generateIv();
            byte[] encrypted = chaChaStrategy.encrypt(plaintext, key, iv);
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_CHACHA20);
            byte[] encryptedWithHeader = concatenate(header, iv, encrypted);

            byte[] decrypted = decryptionService.decryptChunk(encryptedWithHeader, key.getEncoded());

            assertArrayEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("should throw when encrypted data is null")
        void decryptChunk_nullData_throws() {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);

            EncryptionException ex = assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(null, key));

            assertTrue(ex.getMessage().contains("empty"));
        }

        @Test
        @DisplayName("should throw when encrypted data is empty")
        void decryptChunk_emptyData_throws() {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);

            EncryptionException ex = assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(new byte[0], key));

            assertTrue(ex.getMessage().contains("empty"));
        }

        @Test
        @DisplayName("should throw when key is null")
        void decryptChunk_nullKey_throws() {
            byte[] data = createValidAesEncryptedData();

            EncryptionException ex = assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(data, null));

            assertTrue(ex.getMessage().contains("Invalid key"));
        }

        @Test
        @DisplayName("should throw when key length is wrong")
        void decryptChunk_wrongKeyLength_throws() {
            byte[] data = createValidAesEncryptedData();
            byte[] wrongKey = new byte[16];
            RANDOM.nextBytes(wrongKey);

            EncryptionException ex = assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(data, wrongKey));

            assertTrue(ex.getMessage().contains("32 bytes"));
        }

        @Test
        @DisplayName("should throw when data is tampered")
        void decryptChunk_tamperedData_throws() throws EncryptionException {
            byte[] plaintext = "Original data".getBytes();
            SecretKey key = aesStrategy.generateKey();
            byte[] iv = aesStrategy.generateIv();
            byte[] encrypted = aesStrategy.encrypt(plaintext, key, iv);
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] encryptedWithHeader = concatenate(header, iv, encrypted);
            
            encryptedWithHeader[encryptedWithHeader.length - 1] ^= 0xFF;

            assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(encryptedWithHeader, key.getEncoded()));
        }

        @Test
        @DisplayName("should throw when using wrong key")
        void decryptChunk_wrongKey_throws() throws EncryptionException {
            byte[] plaintext = "Secret data".getBytes();
            SecretKey correctKey = aesStrategy.generateKey();
            SecretKey wrongKey = aesStrategy.generateKey();
            byte[] iv = aesStrategy.generateIv();
            byte[] encrypted = aesStrategy.encrypt(plaintext, correctKey, iv);
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] encryptedWithHeader = concatenate(header, iv, encrypted);

            assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(encryptedWithHeader, wrongKey.getEncoded()));
        }

        @Test
        @DisplayName("should throw when data is too short to extract IV")
        void decryptChunk_dataTooShort_throws() {
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] shortData = new byte[header.length + 5];
            System.arraycopy(header, 0, shortData, 0, header.length);
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);

            EncryptionException ex = assertThrows(EncryptionException.class,
                    () -> decryptionService.decryptChunk(shortData, key));

            assertTrue(ex.getMessage().contains("too short"));
        }
    }

    @Nested
    @DisplayName("detectAlgorithm")
    class DetectAlgorithm {

        @Test
        @DisplayName("should detect AES-GCM algorithm")
        void detectAesGcm() {
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] data = new byte[header.length + 100];
            System.arraycopy(header, 0, data, 0, header.length);

            String algorithm = decryptionService.detectAlgorithm(data);

            assertEquals("AES-256-GCM", algorithm);
        }

        @Test
        @DisplayName("should detect ChaCha20-Poly1305 algorithm")
        void detectChaCha20() {
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_CHACHA20);
            byte[] data = new byte[header.length + 100];
            System.arraycopy(header, 0, data, 0, header.length);

            String algorithm = decryptionService.detectAlgorithm(data);

            assertEquals("ChaCha20-Poly1305", algorithm);
        }

        @Test
        @DisplayName("should throw for null data")
        void detectAlgorithm_nullData_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> decryptionService.detectAlgorithm(null));
        }

        @Test
        @DisplayName("should throw for empty data")
        void detectAlgorithm_emptyData_throws() {
            assertThrows(IllegalArgumentException.class,
                    () -> decryptionService.detectAlgorithm(new byte[0]));
        }

        @Test
        @DisplayName("should throw for data without valid header")
        void detectAlgorithm_invalidHeader_throws() {
            byte[] invalidData = {0x00, 0x00, 0x00, 0x00, 0x01, 0x02};

            assertThrows(IllegalArgumentException.class,
                    () -> decryptionService.detectAlgorithm(invalidData));
        }
    }

    @Nested
    @DisplayName("isValidFormat")
    class IsValidFormat {

        @Test
        @DisplayName("should return true for valid AES header")
        void isValidFormat_aesHeader_true() {
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] data = new byte[header.length + 50];
            System.arraycopy(header, 0, data, 0, header.length);

            assertTrue(decryptionService.isValidFormat(data));
        }

        @Test
        @DisplayName("should return true for valid ChaCha header")
        void isValidFormat_chaChaHeader_true() {
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_CHACHA20);
            byte[] data = new byte[header.length + 50];
            System.arraycopy(header, 0, data, 0, header.length);

            assertTrue(decryptionService.isValidFormat(data));
        }

        @Test
        @DisplayName("should return false for null data")
        void isValidFormat_null_false() {
            assertFalse(decryptionService.isValidFormat(null));
        }

        @Test
        @DisplayName("should return false for data without magic bytes")
        void isValidFormat_noMagic_false() {
            byte[] data = {0x00, 0x00, 0x01, 0x01, 0x02, 0x03};

            assertFalse(decryptionService.isValidFormat(data));
        }

        @Test
        @DisplayName("should return false for data shorter than header")
        void isValidFormat_tooShort_false() {
            byte[] data = {0x52, 0x50};

            assertFalse(decryptionService.isValidFormat(data));
        }
    }

    @Nested
    @DisplayName("createDecryptionContext")
    class CreateDecryptionContext {

        @Test
        @DisplayName("should create context for AES-GCM")
        void createContext_aesGcm_success() throws EncryptionException {
            byte[] data = createValidAesEncryptedData();
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);

            ChunkDecryptionService.DecryptionResult result = 
                    decryptionService.createDecryptionContext(data, key);

            assertNotNull(result);
            assertEquals("AES-256-GCM", result.algorithmName());
            assertEquals(ChunkFileHeader.HEADER_SIZE + 12, result.ciphertextOffset());
        }

        @Test
        @DisplayName("should throw for null data")
        void createContext_nullData_throws() {
            byte[] key = new byte[32];
            RANDOM.nextBytes(key);

            assertThrows(EncryptionException.class,
                    () -> decryptionService.createDecryptionContext(null, key));
        }

        @Test
        @DisplayName("should throw for invalid key")
        void createContext_invalidKey_throws() {
            byte[] data = createValidAesEncryptedData();

            assertThrows(EncryptionException.class,
                    () -> decryptionService.createDecryptionContext(data, new byte[16]));
        }
    }

    @Nested
    @DisplayName("Parameterized Tests")
    class ParameterizedTests {

        @ParameterizedTest(name = "Round-trip encryption/decryption with {0}")
        @MethodSource("cn.flying.service.encryption.ChunkDecryptionServiceTest#algorithmProvider")
        @DisplayName("should round-trip encrypt and decrypt correctly")
        void roundTrip(String algorithmName, byte algorithmId, ChunkEncryptionStrategy strategy) 
                throws EncryptionException {
            byte[] plaintext = generateRandomBytes(1024);
            SecretKey key = strategy.generateKey();
            byte[] iv = strategy.generateIv();
            byte[] encrypted = strategy.encrypt(plaintext, key, iv);
            byte[] header = ChunkFileHeader.createHeader(algorithmId);
            byte[] encryptedWithHeader = concatenate(header, iv, encrypted);

            byte[] decrypted = decryptionService.decryptChunk(encryptedWithHeader, key.getEncoded());

            assertArrayEquals(plaintext, decrypted, 
                    "Round-trip failed for " + algorithmName);
        }

        @ParameterizedTest(name = "Large data ({0} bytes) encryption/decryption")
        @MethodSource("cn.flying.service.encryption.ChunkDecryptionServiceTest#dataSizeProvider")
        @DisplayName("should handle various data sizes")
        void variousDataSizes(int dataSize) throws EncryptionException {
            byte[] plaintext = generateRandomBytes(dataSize);
            SecretKey key = aesStrategy.generateKey();
            byte[] iv = aesStrategy.generateIv();
            byte[] encrypted = aesStrategy.encrypt(plaintext, key, iv);
            byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
            byte[] encryptedWithHeader = concatenate(header, iv, encrypted);

            byte[] decrypted = decryptionService.decryptChunk(encryptedWithHeader, key.getEncoded());

            assertArrayEquals(plaintext, decrypted);
        }
    }

    static Stream<Arguments> algorithmProvider() {
        return Stream.of(
                Arguments.of("AES-256-GCM", ChunkFileHeader.ALGORITHM_AES_GCM, new AesGcmEncryptionStrategy()),
                Arguments.of("ChaCha20-Poly1305", ChunkFileHeader.ALGORITHM_CHACHA20, new ChaCha20EncryptionStrategy())
        );
    }

    static Stream<Arguments> dataSizeProvider() {
        return Stream.of(
                Arguments.of(1),
                Arguments.of(16),
                Arguments.of(1024),
                Arguments.of(64 * 1024),
                Arguments.of(256 * 1024)
        );
    }

    private byte[] createValidAesEncryptedData() {
        byte[] header = ChunkFileHeader.createHeader(ChunkFileHeader.ALGORITHM_AES_GCM);
        byte[] iv = new byte[12];
        byte[] ciphertext = new byte[100];
        RANDOM.nextBytes(iv);
        RANDOM.nextBytes(ciphertext);
        return concatenate(header, iv, ciphertext);
    }

    private byte[] concatenate(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] arr : arrays) {
            totalLength += arr.length;
        }
        byte[] result = new byte[totalLength];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }

    private byte[] generateRandomBytes(int size) {
        byte[] bytes = new byte[size];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
