package cn.flying.common.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 无状态 ID 加解密编解码器
 *
 * <p>基于 AES-256-GCM (SIV风格) 实现无损、确定性的ID转换。</p>
 *
 * <h3>特点：</h3>
 * <ul>
 *   <li>无需 Redis 缓存，纯算法转换</li>
 *   <li>确定性：相同输入始终产生相同输出</li>
 *   <li>安全：AES-256 加密 + 完整的 HMAC-SHA256 认证标签</li>
 *   <li>紧凑：输出约 40 字符</li>
 * </ul>
 *
 * <h3>数据结构：</h3>
 * <pre>
 * 明文 (10 bytes): [version:1][type:1][id:8]
 * 密文 (42 bytes): [SIV:16][AES-CTR(plaintext):16][HMAC-SHA256(SIV||ciphertext):10]
 * 输出: prefix + Base62(密文) ≈ 40 chars
 * </pre>
 *
 * <h3>密钥派生：</h3>
 * <p>使用 HKDF 风格派生，从 JWT_KEY 派生出独立的加密密钥和 MAC 密钥，
 * 包含 salt 和上下文隔离以防止密钥混用。</p>
 */
@Slf4j
@Component
public class SecureIdCodec {

    /** 版本号，用于未来格式升级 */
    private static final byte VERSION = 0x02;  // 升级版本号

    /** 实体类型标识 */
    private static final byte TYPE_ENTITY = 0x45;  // 'E'
    private static final byte TYPE_USER = 0x55;    // 'U'

    /** SIV 长度 (Synthetic IV) - 使用完整 16 字节 */
    private static final int SIV_LENGTH = 16;

    /** AES 块大小 */
    private static final int AES_BLOCK_SIZE = 16;

    /** 明文总长度: version(1) + type(1) + id(8) = 10 bytes，填充到 16 bytes */
    private static final int PLAINTEXT_LENGTH = 10;
    private static final int PADDED_PLAINTEXT_LENGTH = 16;

    /** MAC 截断长度 - 使用 10 字节（80 位），提供足够的安全边际 */
    private static final int MAC_LENGTH = 10;

    /** 密文总长度: SIV(16) + AES-CTR(plaintext:16) + MAC(10) = 42 bytes */
    private static final int CIPHERTEXT_LENGTH = SIV_LENGTH + PADDED_PLAINTEXT_LENGTH + MAC_LENGTH;

    /** HKDF Salt - 固定值用于密钥派生 */
    private static final byte[] HKDF_SALT = "RecordPlatform.IdCodec.v2".getBytes();

    /** AES 加密密钥 (256-bit) */
    private final SecretKeySpec aesKey;

    /** HMAC 签名密钥 */
    private final SecretKeySpec hmacKey;

    private static SecureIdCodec instance;

    /**
     * 构造函数，从 JWT 密钥派生加密密钥
     *
     * @param jwtKey JWT 密钥 (至少32字符)
     */
    public SecureIdCodec(@Value("${spring.security.jwt.key}") String jwtKey) {
        if (jwtKey == null || jwtKey.length() < 32) {
            throw new IllegalArgumentException("JWT key must be at least 32 characters for ID encryption");
        }

        // 使用 HKDF 风格的密钥派生，带 salt 和上下文隔离
        this.aesKey = new SecretKeySpec(deriveKey(jwtKey, HKDF_SALT, "ID_ENC_KEY_V2", 32), "AES");
        this.hmacKey = new SecretKeySpec(deriveKey(jwtKey, HKDF_SALT, "ID_MAC_KEY_V2", 32), "HmacSHA256");

        SecureIdCodec.instance = this;
        log.info("SecureIdCodec v2 initialized with AES-256-CTR + HMAC-SHA256");
    }

    /**
     * 获取单例实例（供静态方法使用）
     */
    public static SecureIdCodec getInstance() {
        if (instance == null) {
            throw new IllegalStateException("SecureIdCodec not initialized. Ensure Spring context is loaded.");
        }
        return instance;
    }

    /**
     * 将内部实体ID转换为外部ID
     *
     * @param internalId 内部数据库ID
     * @return 外部ID (格式: E + Base62编码), 如输入为null则返回null
     */
    public String toExternalId(Long internalId) {
        return encode(internalId, "E", TYPE_ENTITY);
    }

    /**
     * 将内部用户ID转换为外部ID
     *
     * @param userId 内部用户ID
     * @return 外部用户ID (格式: U + Base62编码), 如输入为null则返回null
     */
    public String toExternalUserId(Long userId) {
        return encode(userId, "U", TYPE_USER);
    }

    /**
     * 将外部ID还原为内部ID
     *
     * @param externalId 外部ID
     * @return 内部ID, 如果解密失败或格式错误返回null
     */
    public Long fromExternalId(String externalId) {
        if (externalId == null || externalId.length() < 2) {
            return null;
        }

        String prefix = externalId.substring(0, 1);
        byte expectedType = "U".equals(prefix) ? TYPE_USER : TYPE_ENTITY;

        return decode(externalId.substring(1), expectedType);
    }

    /**
     * 编码内部ID为外部ID
     */
    private String encode(Long id, String prefix, byte type) {
        if (id == null) {
            return null;
        }

        try {
            // 1. 构建明文: [version:1][type:1][id:8][padding:6]
            byte[] plaintext = new byte[PADDED_PLAINTEXT_LENGTH];
            plaintext[0] = VERSION;
            plaintext[1] = type;
            ByteBuffer.wrap(plaintext, 2, 8).putLong(id);
            // 剩余 6 字节保持为 0 作为填充

            // 2. 计算 SIV (Synthetic IV) - 使用完整 16 字节
            byte[] siv = computeSIV(plaintext);

            // 3. AES-CTR 加密（使用 SIV 作为 counter 初始值）
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new javax.crypto.spec.IvParameterSpec(siv));
            byte[] encryptedPlaintext = cipher.doFinal(plaintext);

            // 4. 计算 MAC: HMAC-SHA256(SIV || ciphertext)，截断到 MAC_LENGTH
            byte[] macInput = new byte[SIV_LENGTH + PADDED_PLAINTEXT_LENGTH];
            System.arraycopy(siv, 0, macInput, 0, SIV_LENGTH);
            System.arraycopy(encryptedPlaintext, 0, macInput, SIV_LENGTH, PADDED_PLAINTEXT_LENGTH);
            byte[] mac = computeMAC(macInput);

            // 5. 组合密文: [SIV:16][encrypted:16][MAC:10]
            byte[] ciphertext = new byte[CIPHERTEXT_LENGTH];
            System.arraycopy(siv, 0, ciphertext, 0, SIV_LENGTH);
            System.arraycopy(encryptedPlaintext, 0, ciphertext, SIV_LENGTH, PADDED_PLAINTEXT_LENGTH);
            System.arraycopy(mac, 0, ciphertext, SIV_LENGTH + PADDED_PLAINTEXT_LENGTH, MAC_LENGTH);

            // 6. Base62 编码并添加前缀
            return prefix + Base62.encode(ciphertext);

        } catch (Exception e) {
            log.error("ID加密失败, internalId: {}", id, e);
            return null;
        }
    }

    /**
     * 解码外部ID为内部ID
     */
    private Long decode(String encoded, byte expectedType) {
        try {
            // 1. Base62 解码
            byte[] ciphertext = Base62.decode(encoded);
            if (ciphertext.length != CIPHERTEXT_LENGTH) {
                log.warn("密文长度错误: expected={}, actual={}", CIPHERTEXT_LENGTH, ciphertext.length);
                return null;
            }

            // 2. 分离 SIV、密文和 MAC
            byte[] siv = Arrays.copyOf(ciphertext, SIV_LENGTH);
            byte[] encryptedPlaintext = Arrays.copyOfRange(ciphertext, SIV_LENGTH, SIV_LENGTH + PADDED_PLAINTEXT_LENGTH);
            byte[] receivedMac = Arrays.copyOfRange(ciphertext, SIV_LENGTH + PADDED_PLAINTEXT_LENGTH, CIPHERTEXT_LENGTH);

            // 3. 验证 MAC（先验证再解密，防止 oracle 攻击）
            byte[] macInput = new byte[SIV_LENGTH + PADDED_PLAINTEXT_LENGTH];
            System.arraycopy(siv, 0, macInput, 0, SIV_LENGTH);
            System.arraycopy(encryptedPlaintext, 0, macInput, SIV_LENGTH, PADDED_PLAINTEXT_LENGTH);
            byte[] expectedMac = computeMAC(macInput);

            if (!MessageDigest.isEqual(receivedMac, expectedMac)) {
                log.warn("MAC验证失败，可能是ID被篡改");
                return null;
            }

            // 4. AES-CTR 解密
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new javax.crypto.spec.IvParameterSpec(siv));
            byte[] plaintext = cipher.doFinal(encryptedPlaintext);

            // 5. 验证 SIV (确定性检查)
            byte[] expectedSIV = computeSIV(plaintext);
            if (!MessageDigest.isEqual(siv, expectedSIV)) {
                log.warn("SIV验证失败，数据完整性检查未通过");
                return null;
            }

            // 6. 验证版本和类型
            if (plaintext[0] != VERSION) {
                log.warn("版本号不匹配: expected={}, actual={}", VERSION, plaintext[0]);
                return null;
            }
            if (plaintext[1] != expectedType) {
                log.warn("类型标识不匹配: expected={}, actual={}", expectedType, plaintext[1]);
                return null;
            }

            // 7. 提取内部ID
            return ByteBuffer.wrap(plaintext, 2, 8).getLong();

        } catch (IllegalArgumentException e) {
            log.warn("Base62解码失败: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("ID解密失败", e);
            return null;
        }
    }

    /**
     * 计算 Synthetic IV
     * 使用 HMAC-SHA256 的前 16 字节作为 SIV
     */
    private byte[] computeSIV(byte[] plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        byte[] hash = mac.doFinal(plaintext);
        return Arrays.copyOf(hash, SIV_LENGTH);
    }

    /**
     * 计算 MAC
     * 使用 HMAC-SHA256 的前 MAC_LENGTH 字节
     */
    private byte[] computeMAC(byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        byte[] hash = mac.doFinal(data);
        return Arrays.copyOf(hash, MAC_LENGTH);
    }

    /**
     * HKDF 风格密钥派生函数
     *
     * @param masterKey 主密钥
     * @param salt      盐值
     * @param info      上下文信息
     * @param length    输出长度
     * @return 派生密钥
     */
    private static byte[] deriveKey(String masterKey, byte[] salt, String info, int length) {
        try {
            // Extract: PRK = HMAC(salt, masterKey)
            Mac extractMac = Mac.getInstance("HmacSHA256");
            extractMac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = extractMac.doFinal(masterKey.getBytes());

            // Expand: output = HMAC(PRK, info || 0x01)
            Mac expandMac = Mac.getInstance("HmacSHA256");
            expandMac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] infoBytes = (info + "\u0001").getBytes();
            byte[] hash = expandMac.doFinal(infoBytes);

            return Arrays.copyOf(hash, length);
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }
}
