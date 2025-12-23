package cn.flying.service.encryption;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 *  flyingcoding
 *
 * <p>适用于有 AES-NI 硬件加速的服务器环境，性能最优。</p>
 *
 * <h3>优化点：</h3>
 * <ul>
 *   <li>ThreadLocal 缓存 Cipher 和 KeyGenerator，避免重复实例化</li>
 *   <li>复用 SecureRandom 实例</li>
 *   <li>预初始化 KeyGenerator 参数</li>
 * </ul>
 *
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>256 位密钥强度</li>
 *   <li>128 位认证标签（GCM 最大强度）</li>
 *   <li>12 字节 IV（NIST 推荐）</li>
 * </ul>
 *
 * @author flyingcoding
 * @since 2.0.0
 */
@Slf4j
public class AesGcmEncryptionStrategy implements ChunkEncryptionStrategy {

    private static final String ALGORITHM_NAME = "AES-256-GCM";
    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int KEY_SIZE_BITS = 256;
    private static final int IV_SIZE_BYTES = 12;
    private static final int TAG_BIT_LENGTH = 128;

    /**
     * 共享的 SecureRandom 实例（线程安全）
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     *  flyingcoding
     */
    private static final ThreadLocal<KeyGenerator> KEY_GENERATOR_CACHE = ThreadLocal.withInitial(() -> {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(KEY_ALGORITHM);
            keyGen.init(KEY_SIZE_BITS, SECURE_RANDOM);
            return keyGen;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("AES algorithm not available", e);
        }
    });

    /**
     *  flyingcoding
     * 注意：每次使用前仍需 init()，但避免了 getInstance() 的开销
     */
    private static final ThreadLocal<Cipher> CIPHER_CACHE = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(CIPHER_TRANSFORMATION);
        } catch (Exception e) {
            throw new IllegalStateException("AES/GCM cipher not available", e);
        }
    });

    public AesGcmEncryptionStrategy() {
        log.info("AES-256-GCM encryption strategy initialized (optimized with ThreadLocal caching)");
    }

    @Override
    public String getAlgorithmName() {
        return ALGORITHM_NAME;
    }

    @Override
    public int getIvSize() {
        return IV_SIZE_BYTES;
    }

    @Override
    public int getTagBitLength() {
        return TAG_BIT_LENGTH;
    }

    @Override
    public SecretKey generateKey() {
        return KEY_GENERATOR_CACHE.get().generateKey();
    }

    @Override
    public byte[] generateIv() {
        byte[] iv = new byte[IV_SIZE_BYTES];
        SECURE_RANDOM.nextBytes(iv);
        return iv;
    }

    @Override
    public byte[] encrypt(byte[] plaintext, SecretKey key, byte[] iv) throws EncryptionException {
        try {
            Cipher cipher = CIPHER_CACHE.get();
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new EncryptionException("AES-GCM encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] iv) throws EncryptionException {
        try {
            Cipher cipher = CIPHER_CACHE.get();
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new EncryptionException("AES-GCM decryption failed (possible tampering or wrong key)", e);
        }
    }

    @Override
    public EncryptionContext createEncryptionContext(SecretKey key, byte[] iv) throws EncryptionException {
        try {
            // 流式操作需要独立的 Cipher 实例，不能使用 ThreadLocal 缓存
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec);
            return new EncryptionContext(cipher, ALGORITHM_NAME);
        } catch (Exception e) {
            throw new EncryptionException("Failed to create AES-GCM encryption context", e);
        }
    }

    @Override
    public byte[] encryptUpdate(EncryptionContext context, byte[] data, int offset, int length) throws EncryptionException {
        try {
            byte[] result = context.getCipher().update(data, offset, length);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("AES-GCM encryption update failed", e);
        }
    }

    @Override
    public byte[] encryptFinal(EncryptionContext context) throws EncryptionException {
        try {
            byte[] result = context.getCipher().doFinal();
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("AES-GCM encryption finalization failed", e);
        }
    }

    @Override
    public EncryptionContext createDecryptionContext(SecretKey key, byte[] iv) throws EncryptionException {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_BIT_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            return new EncryptionContext(cipher, ALGORITHM_NAME);
        } catch (Exception e) {
            throw new EncryptionException("Failed to create AES-GCM decryption context", e);
        }
    }

    @Override
    public byte[] decryptUpdate(EncryptionContext context, byte[] data, int offset, int length) throws EncryptionException {
        try {
            byte[] result = context.getCipher().update(data, offset, length);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("AES-GCM decryption update failed", e);
        }
    }

    @Override
    public byte[] decryptFinal(EncryptionContext context) throws EncryptionException {
        try {
            byte[] result = context.getCipher().doFinal();
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("AES-GCM decryption finalization failed (authentication tag mismatch)", e);
        }
    }
}
