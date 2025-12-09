package cn.flying.service.encryption;

import lombok.extern.slf4j.Slf4j;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * ChaCha20-Poly1305 加密策略
 *
 * <p>适用于无 AES-NI 硬件加速的环境（如某些云容器、移动设备）或需要更强侧信道防护的场景。</p>
 *
 * <h3>特点：</h3>
 * <ul>
 *   <li>纯软件实现性能优异，不依赖硬件加速</li>
 *   <li>常量时间操作，天然抵抗缓存时序攻击</li>
 *   <li>JDK 11+ 原生支持，无需第三方库</li>
 * </ul>
 *
 * <h3>安全特性：</h3>
 * <ul>
 *   <li>256 位密钥强度</li>
 *   <li>128 位认证标签（Poly1305）</li>
 *   <li>96 位 Nonce（与 AES-GCM 相同）</li>
 * </ul>
 *
 * <h3>性能对比：</h3>
 * <ul>
 *   <li>有 AES-NI：AES-GCM 更快</li>
 *   <li>无 AES-NI：ChaCha20-Poly1305 快 3-4 倍</li>
 * </ul>
 *
 * @author Claude Code
 * @since 2.0.0
 */
@Slf4j
public class ChaCha20EncryptionStrategy implements ChunkEncryptionStrategy {

    private static final String ALGORITHM_NAME = "ChaCha20-Poly1305";
    private static final String CIPHER_TRANSFORMATION = "ChaCha20-Poly1305";
    private static final String KEY_ALGORITHM = "ChaCha20";
    private static final int KEY_SIZE_BITS = 256;
    private static final int NONCE_SIZE_BYTES = 12;  // 96 bits, 标准 ChaCha20-Poly1305 nonce
    private static final int TAG_BIT_LENGTH = 128;   // Poly1305 固定 128 位标签

    /**
     * 共享的 SecureRandom 实例（线程安全）
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * ThreadLocal 缓存的 KeyGenerator
     */
    private static final ThreadLocal<KeyGenerator> KEY_GENERATOR_CACHE = ThreadLocal.withInitial(() -> {
        try {
            KeyGenerator keyGen = KeyGenerator.getInstance(KEY_ALGORITHM);
            keyGen.init(KEY_SIZE_BITS, SECURE_RANDOM);
            return keyGen;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("ChaCha20 algorithm not available (requires JDK 11+)", e);
        }
    });

    /**
     * ThreadLocal 缓存的 Cipher
     */
    private static final ThreadLocal<Cipher> CIPHER_CACHE = ThreadLocal.withInitial(() -> {
        try {
            return Cipher.getInstance(CIPHER_TRANSFORMATION);
        } catch (Exception e) {
            throw new IllegalStateException("ChaCha20-Poly1305 cipher not available (requires JDK 11+)", e);
        }
    });

    public ChaCha20EncryptionStrategy() {
        // 验证 JDK 版本支持
        validateJdkSupport();
        log.info("ChaCha20-Poly1305 encryption strategy initialized (optimized with ThreadLocal caching)");
    }

    /**
     * 验证 JDK 是否支持 ChaCha20-Poly1305
     */
    private void validateJdkSupport() {
        try {
            Cipher.getInstance(CIPHER_TRANSFORMATION);
            KeyGenerator.getInstance(KEY_ALGORITHM);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "ChaCha20-Poly1305 requires JDK 11+. Current JDK: " + System.getProperty("java.version"), e);
        }
    }

    @Override
    public String getAlgorithmName() {
        return ALGORITHM_NAME;
    }

    @Override
    public int getIvSize() {
        return NONCE_SIZE_BYTES;
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
        byte[] nonce = new byte[NONCE_SIZE_BYTES];
        SECURE_RANDOM.nextBytes(nonce);
        return nonce;
    }

    @Override
    public byte[] encrypt(byte[] plaintext, SecretKey key, byte[] iv) throws EncryptionException {
        try {
            Cipher cipher = CIPHER_CACHE.get();
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new EncryptionException("ChaCha20-Poly1305 encryption failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, SecretKey key, byte[] iv) throws EncryptionException {
        try {
            Cipher cipher = CIPHER_CACHE.get();
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new EncryptionException("ChaCha20-Poly1305 decryption failed (possible tampering or wrong key)", e);
        }
    }

    @Override
    public EncryptionContext createEncryptionContext(SecretKey key, byte[] iv) throws EncryptionException {
        try {
            // 流式操作需要独立的 Cipher 实例
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec);
            return new EncryptionContext(cipher, ALGORITHM_NAME);
        } catch (Exception e) {
            throw new EncryptionException("Failed to create ChaCha20-Poly1305 encryption context", e);
        }
    }

    @Override
    public byte[] encryptUpdate(EncryptionContext context, byte[] data, int offset, int length) throws EncryptionException {
        try {
            byte[] result = context.getCipher().update(data, offset, length);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("ChaCha20-Poly1305 encryption update failed", e);
        }
    }

    @Override
    public byte[] encryptFinal(EncryptionContext context) throws EncryptionException {
        try {
            byte[] result = context.getCipher().doFinal();
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("ChaCha20-Poly1305 encryption finalization failed", e);
        }
    }

    @Override
    public EncryptionContext createDecryptionContext(SecretKey key, byte[] iv) throws EncryptionException {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec);
            return new EncryptionContext(cipher, ALGORITHM_NAME);
        } catch (Exception e) {
            throw new EncryptionException("Failed to create ChaCha20-Poly1305 decryption context", e);
        }
    }

    @Override
    public byte[] decryptUpdate(EncryptionContext context, byte[] data, int offset, int length) throws EncryptionException {
        try {
            byte[] result = context.getCipher().update(data, offset, length);
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("ChaCha20-Poly1305 decryption update failed", e);
        }
    }

    @Override
    public byte[] decryptFinal(EncryptionContext context) throws EncryptionException {
        try {
            byte[] result = context.getCipher().doFinal();
            return result != null ? result : new byte[0];
        } catch (Exception e) {
            throw new EncryptionException("ChaCha20-Poly1305 decryption finalization failed (authentication tag mismatch)", e);
        }
    }
}
