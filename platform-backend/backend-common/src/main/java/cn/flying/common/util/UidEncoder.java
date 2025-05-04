package cn.flying.common.util;

import cn.flying.common.exception.GeneralException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * @program: RecordPlatform
 * @description: Uid编码工具，用于将Uid转换为固定长度的字符串
 * @author: flyingcoding
 * @create: 2025-05-05 03:12
 */
public class UidEncoder {
    // 输出编码字符集
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    // 固定输出长度
    private static final int OUTPUT_LENGTH = 12;
    // 加盐
    private static final String SALT = "RecordPlatform_Uid_Key";
    // 密钥
    private static final String KEY = "RecordPlatform_Client_Key";

    /**
     * 将UID编码为固定长度字符串
     */
    public static String encodeUid(String uid) {
        if (uid == null || uid.isEmpty()) {
            throw new GeneralException("UID不能为空");
        }

        try {
            // 将UID与盐值组合
            String input = uid + SALT;

            // 使用SHA-256生成哈希
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // 将哈希转换为字符
            StringBuilder result = new StringBuilder(OUTPUT_LENGTH);

            // 每4位哈希值映射到一个字母
            for (int i = 0; i < OUTPUT_LENGTH; i++) {
                // 从哈希中提取值（每次取一个字节，循环使用哈希值）
                int hashValue = hashBytes[i % hashBytes.length] & 0xFF;
                // 映射到字母表
                result.append(ALPHABET[hashValue % ALPHABET.length]);
            }

            return result.toString();

        } catch (NoSuchAlgorithmException e) {
            throw new GeneralException("哈希算法不可用");
        }
    }

    /**
     * 将加密之后的UID编码为固定长度字符串
     */
    public static String encodeCid(String SUID) {
        if (SUID == null || SUID.isEmpty()) {
            throw new IllegalArgumentException("SUID不能为空");
        }

        // 添加密钥混合
        String mixed = SUID + KEY;

        // 生成初始数值
        int value = 0;
        for (int i = 0; i < mixed.length(); i++) {
            value = 31 * value + mixed.charAt(i);
        }

        // 确保为正数
        value = Math.abs(value);

        // 转换为字母字符串
        char[] result = new char[OUTPUT_LENGTH];
        Arrays.fill(result, ALPHABET[0]); // 默认填充第一个字母

        // 填充实际编码
        int index = 0;
        while (value > 0 && index < OUTPUT_LENGTH) {
            result[index] = ALPHABET[value % ALPHABET.length];
            value /= ALPHABET.length;
            index++;
        }

        return new String(result);
    }

    /**
     * 验证字符串是否是特定CID的编码
     * 注意：由于使用的是单向哈希混合，此方法只能验证，不能还原
     * 但可以验证某个CID是否匹配这个编码
     */
    public static boolean verifyCid(String encoded, String cid) {
        if (encoded == null || cid == null) {
            return false;
        }

        return encoded.equals(encodeCid(cid));
    }
}
