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
 * <p>基于 AES-128-ECB + HMAC-SHA256 (SIV风格) 实现无损、确定性的ID转换。</p>
 *
 * <h3>特点：</h3>
 * <ul>
 *   <li>无需 Redis 缓存，纯算法转换</li>
 *   <li>确定性：相同输入始终产生相同输出</li>
 *   <li>安全：AES-128 加密 + HMAC 完整性验证</li>
 *   <li>紧凑：输出约 25 字符</li>
 * </ul>
 *
 * <h3>数据结构：</h3>
 * <pre>
 * 明文 (10 bytes): [version:1][type:1][id:8]
 * 密文 (18 bytes): [AES(SIV || plaintext[:8]):16][XOR(plaintext[8:10]):2]
 * 输出: prefix + Base62(密文) ≈ 25 chars
 * </pre>
 */
@Slf4j
@Component
public class SecureIdCodec {

    /** 版本号，用于未来格式升级 */
    private static final byte VERSION = 0x01;

    /** 实体类型标识 */
    private static final byte TYPE_ENTITY = 0x45;  // 'E'
    private static final byte TYPE_USER = 0x55;    // 'U'

    /** SIV 长度 (Synthetic IV) */
    private static final int SIV_LENGTH = 8;

    /** AES 块大小 */
    private static final int AES_BLOCK_SIZE = 16;

    /** 明文总长度: version(1) + type(1) + id(8) = 10 bytes */
    private static final int PLAINTEXT_LENGTH = 10;

    /** 密文总长度: AES块(16) + 剩余异或加密(2) = 18 bytes */
    private static final int CIPHERTEXT_LENGTH = 18;

    /** AES 加密密钥 */
    private final SecretKeySpec aesKey;

    /** HMAC 签名密钥 */
    private final SecretKeySpec hmacKey;

    /** XOR 混淆密钥 (2 bytes) */
    private final byte[] xorKey;

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

        // 使用 HKDF 风格的密钥派生
        this.aesKey = new SecretKeySpec(deriveKey(jwtKey, "ID_AES_KEY", 16), "AES");
        this.hmacKey = new SecretKeySpec(deriveKey(jwtKey, "ID_HMAC_KEY", 32), "HmacSHA256");
        this.xorKey = deriveKey(jwtKey, "ID_XOR_KEY", 2);

        SecureIdCodec.instance = this;
        log.info("SecureIdCodec initialized successfully");
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
            // 1. 构建明文: [version:1][type:1][id:8]
            byte[] plaintext = new byte[PLAINTEXT_LENGTH];
            plaintext[0] = VERSION;
            plaintext[1] = type;
            ByteBuffer.wrap(plaintext, 2, 8).putLong(id);

            // 2. 计算 SIV (Synthetic IV)
            byte[] siv = computeSIV(plaintext);

            // 3. 构建 AES 输入块: [SIV:8][plaintext[:8]:8]
            byte[] aesInput = new byte[AES_BLOCK_SIZE];
            System.arraycopy(siv, 0, aesInput, 0, SIV_LENGTH);
            System.arraycopy(plaintext, 0, aesInput, SIV_LENGTH, 8);

            // 4. AES-ECB 加密
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey);
            byte[] aesOutput = cipher.doFinal(aesInput);

            // 5. XOR 加密剩余 2 字节
            byte[] xorOutput = new byte[2];
            xorOutput[0] = (byte) (plaintext[8] ^ xorKey[0] ^ siv[0]);
            xorOutput[1] = (byte) (plaintext[9] ^ xorKey[1] ^ siv[1]);

            // 6. 组合密文: [AES输出:16][XOR输出:2]
            byte[] ciphertext = new byte[CIPHERTEXT_LENGTH];
            System.arraycopy(aesOutput, 0, ciphertext, 0, AES_BLOCK_SIZE);
            System.arraycopy(xorOutput, 0, ciphertext, AES_BLOCK_SIZE, 2);

            // 7. Base62 编码并添加前缀
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

            // 2. 分离 AES 密文和 XOR 密文
            byte[] aesOutput = Arrays.copyOf(ciphertext, AES_BLOCK_SIZE);
            byte[] xorOutput = Arrays.copyOfRange(ciphertext, AES_BLOCK_SIZE, CIPHERTEXT_LENGTH);

            // 3. AES-ECB 解密
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey);
            byte[] aesInput = cipher.doFinal(aesOutput);

            // 4. 提取 SIV 和部分明文
            byte[] siv = Arrays.copyOf(aesInput, SIV_LENGTH);
            byte[] plaintextPart = Arrays.copyOfRange(aesInput, SIV_LENGTH, AES_BLOCK_SIZE);

            // 5. XOR 解密剩余 2 字节
            byte[] remainingPlaintext = new byte[2];
            remainingPlaintext[0] = (byte) (xorOutput[0] ^ xorKey[0] ^ siv[0]);
            remainingPlaintext[1] = (byte) (xorOutput[1] ^ xorKey[1] ^ siv[1]);

            // 6. 重建完整明文
            byte[] plaintext = new byte[PLAINTEXT_LENGTH];
            System.arraycopy(plaintextPart, 0, plaintext, 0, 8);
            System.arraycopy(remainingPlaintext, 0, plaintext, 8, 2);

            // 7. 验证 SIV (关键安全步骤)
            byte[] expectedSIV = computeSIV(plaintext);
            if (!MessageDigest.isEqual(siv, expectedSIV)) {
                log.warn("SIV验证失败，可能是ID被篡改");
                return null;
            }

            // 8. 验证版本和类型
            if (plaintext[0] != VERSION) {
                log.warn("版本号不匹配: expected={}, actual={}", VERSION, plaintext[0]);
                return null;
            }
            if (plaintext[1] != expectedType) {
                log.warn("类型标识不匹配: expected={}, actual={}", expectedType, plaintext[1]);
                return null;
            }

            // 9. 提取内部ID
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
     * 使用 HMAC-SHA256 的前 8 字节作为 SIV
     */
    private byte[] computeSIV(byte[] plaintext) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(hmacKey);
        byte[] hash = mac.doFinal(plaintext);
        return Arrays.copyOf(hash, SIV_LENGTH);
    }

    /**
     * 密钥派生函数 (简化的 HKDF)
     *
     * @param masterKey 主密钥
     * @param info 上下文信息
     * @param length 输出长度
     * @return 派生密钥
     */
    private static byte[] deriveKey(String masterKey, String info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey.getBytes(), "HmacSHA256"));
            byte[] hash = mac.doFinal(info.getBytes());
            return Arrays.copyOf(hash, length);
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }
}
