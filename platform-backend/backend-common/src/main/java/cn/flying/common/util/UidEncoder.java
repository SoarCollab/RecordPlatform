package cn.flying.common.util;

import cn.flying.common.exception.GeneralException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * @program: RecordPlatform
 * @description: Uid编码工具，用于将Uid转换为固定长度的字符串
 * @author flyingcoding
 * @create: 2025-05-05 03:12
 */
public class UidEncoder {
    // 输出编码字符集
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    // 固定输出长度 (指核心编码部分的长度)
    private static final int OUTPUT_LENGTH = 12;
    // 新增：盐的长度
    private static final int SALT_LENGTH = 4;
    // 加盐 (用于encodeUid)
    private static final String SALT = "RecordPlatform_Uid_Key";
    // 密钥 (用于encodeCid)
    private static final String KEY = "RecordPlatform_Client_Key";
    //用于生成盐的SecureRandom实例
    private static final SecureRandom random = new SecureRandom();

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

    //内部方法，用于根据SUID和盐生成核心编码部分
    private static String encodeCidInternal(String suid, String salt) {
        if (suid == null || suid.isEmpty()) {
            throw new IllegalArgumentException("SUID 不能为空");
        }

        // 添加密钥和盐混合
        String mixed = suid + KEY + salt;

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
     * 将加密之后的UID (SUID) 编码为包含随机盐的字符串（每次调用结果都不同）
     * 返回的字符串格式为: [盐值 (SALT_LENGTH个字符)][核心编码 (OUTPUT_LENGTH个字符)]
     * 总长度为: SALT_LENGTH + OUTPUT_LENGTH
     */
    public static String encodeCid(String SUID) {
        if (SUID == null || SUID.isEmpty()) {
            throw new IllegalArgumentException("SUID 不能为空");
        }

        // 生成随机盐
        char[] saltChars = new char[SALT_LENGTH];
        for (int i = 0; i < SALT_LENGTH; i++) {
            saltChars[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        String salt = new String(saltChars);

        String baseEncodedPart = encodeCidInternal(SUID, salt);
        return salt + baseEncodedPart;
    }

    /**
     * 验证带盐的编码字符串 (encodedWithSalt) 是否是特定原始SUID (cid参数) 的有效编码
     * @param encodedWithSalt 包含盐和核心编码的完整字符串，期望长度为 SALT_LENGTH + OUTPUT_LENGTH。
     * @param cid 要验证的原始SUID
     * @return 是否验证成功
     */
    public static boolean verifyCid(String encodedWithSalt, String cid) {
        if (encodedWithSalt == null || cid == null || cid.isEmpty()) {
            return false;
        }

        // 校验总长度是否正确
        if (encodedWithSalt.length() != SALT_LENGTH + OUTPUT_LENGTH) {
            return false;
        }

        // 解析盐和核心编码部分
        String salt = encodedWithSalt.substring(0, SALT_LENGTH);
        String actualBaseEncodedPart = encodedWithSalt.substring(SALT_LENGTH);

        // 校验盐中字符的合法性，确保它们都来自 ALPHABET
         for (char c : salt.toCharArray()) {
             boolean isValidChar = false;
             for (char valid : ALPHABET) {
                 if (c == valid) {
                     isValidChar = true;
                     break;
                 }
             }
             if (!isValidChar) return false; // 盐包含非法字符
         }

        try {
            // 使用解析出的盐和原始cid重新计算期望的核心编码部分
            String expectedBaseEncodedPart = encodeCidInternal(cid, salt);
            // 比较实际的核心编码部分和期望的核心编码部分
            return actualBaseEncodedPart.equals(expectedBaseEncodedPart);
        } catch (IllegalArgumentException e) {
            // 如果 encodeCidInternal 由于 cid 为空或 salt 格式问题（理论上已通过长度校验）抛出异常，
            // 也应视为验证失败。
            return false;
        }
    }
}