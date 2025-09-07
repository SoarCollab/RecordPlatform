package cn.flying.identity.util;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 通用工具类
 * 提供字符串验证、对象验证、数据转换、密码安全性检查等实用方法
 * 
 * @author 王贝强
 */
@Slf4j
public abstract class CommonUtils {

    private CommonUtils() {}

    /**
     * 检查对象是否为null，如果为null则抛出异常
     */
    public static void assertNotNull(final Object object, final String message) {
        if (object == null) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 检查字符串是否为空，如果为空则抛出异常
     */
    public static void assertNotEmpty(final String value, final String message) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 检查集合是否为空，如果为空则抛出异常
     */
    public static void assertNotEmpty(final Collection<?> c, final String message) {
        assertNotNull(c, message);
        if (c.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 断言条件为真，否则抛出异常
     */
    public static void assertTrue(final boolean cond, final String message) {
        if (!cond) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * 判断字符串是否为空
     */
    public static boolean isEmpty(final String s) {
        return s == null || s.isEmpty();
    }

    /**
     * 判断对象是否为空（支持String, Collection, Array, Map等）
     */
    public static boolean isEmpty(final Object o) {
        if (o == null) return true;
        if (o instanceof String) return isEmpty((String) o);
        else if (o instanceof Collection) return ((Collection<?>) o).isEmpty();
        else if (o.getClass().isArray()) return ((Object[]) o).length == 0;
        else if (o instanceof Map) return ((Map<?, ?>) o).isEmpty();
        else return false;
    }

    public static boolean isNotEmpty(final Object o) {
        return !isEmpty(o);
    }

    public static boolean isNotEmpty(final String s) {
        return !isEmpty(s);
    }

    /**
     * 判断字符串是否为空白（null、空字符串或只包含空格）
     */
    public static boolean isBlank(final String s) {
        return isEmpty(s) || s.trim().isEmpty();
    }

    public static boolean isNotBlank(final String s) {
        return !isBlank(s);
    }

    /**
     * 对象相等性判断
     */
    public static boolean equals(Object o1, Object o2) {
        if (o1 == o2) {
            return true;
        } else {
            return o1 != null && o1.equals(o2);
        }
    }

    public static boolean notEquals(Object o1, Object o2) {
        return !equals(o1, o2);
    }

    /**
     * 获取值或默认值
     */
    public static <P> P getOrElse(P value, P defaultValue) {
        if (isEmpty(value)) {
            value = defaultValue;
        }
        return value;
    }

    /**
     * 将null值转换为空字符串
     */
    public static String getOrEmpty(String value) {
        if (value == null) {
            value = "";
        }
        return value;
    }

    /**
     * 字符串转BigDecimal
     */
    public static BigDecimal convert2BigDecimal(String value) {
        BigDecimal result = BigDecimal.ZERO;
        if (isNotEmpty(value)) {
            try {
                result = new BigDecimal(value.trim());
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * 格式化日期
     */
    public static String formatDate(Date date, String format) {
        if (date == null) {
            return null;
        }
        return new SimpleDateFormat(format).format(date);
    }

    /**
     * 格式化LocalDateTime
     */
    public static String formatDateTime(LocalDateTime dateTime, String pattern) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ofPattern(pattern));
    }

    /**
     * 解析日期字符串
     */
    public static Date parseDate(String dateStr, String format) {
        if (isNotEmpty(dateStr)) {
            try {
                return new SimpleDateFormat(format).parse(dateStr);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 解析LocalDateTime字符串
     */
    public static LocalDateTime parseDateTime(String dateTimeStr, String pattern) {
        if (isNotEmpty(dateTimeStr)) {
            try {
                return LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern(pattern));
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 缩略文字
     */
    public static String thumbnailText(String text, Integer prefix, Integer suffix) {
        if (isEmpty(text) || prefix + suffix >= text.length()) {
            return text;
        }
        String replaceText = text.substring(prefix, text.length() - suffix);
        return text.replace(replaceText, "**");
    }

    public static String thumbnailText(String text) {
        return thumbnailText(text, 1, 1);
    }

    /**
     * 校验密码的安全性
     * 0-弱口令, 1-弱口令, 2-一般, 3-中, 4-高
     */
    public static int passwordSecurityLevel(String password) {
        if (isEmpty(password) || password.length() < 6) {
            return 0;
        }
        
        int[] securityLevelFlags = new int[]{0, 0, 0, 0};
        for (int i = 0; i < password.length(); i++) {
            int asciiNumber = Character.codePointAt(password.substring(i, i + 1), 0);
            if (asciiNumber >= 48 && asciiNumber <= 57) {
                securityLevelFlags[0] = 1;  // 数字
            } else if (asciiNumber >= 97 && asciiNumber <= 122) {
                securityLevelFlags[1] = 1;  // 小写字母
            } else if (asciiNumber >= 65 && asciiNumber <= 90) {
                securityLevelFlags[2] = 1;  // 大写字母
            } else {
                securityLevelFlags[3] = 1;  // 特殊字符
            }
        }

        int securityLevelFlag = 0;
        for (int levelFlag : securityLevelFlags) {
            if (levelFlag == 1) {
                securityLevelFlag++;
            }
        }
        return securityLevelFlag;
    }

    /**
     * 密码复杂度计算
     * 1-大于8位，2-包含数字，4-包含大小写字母，8=包含特殊字符
     */
    public static int passwordComplexityCompute(String password) {
        if (isEmpty(password) || password.length() < 8) {
            return 0;
        }
        
        int[] passwordComplexityFactors = new int[]{1, 0, 0, 0};
        char[] charSequence = password.toCharArray();
        for (char c : charSequence) {
            int asciiNumber = Character.codePointAt(String.valueOf(c), 0);
            if (asciiNumber >= 48 && asciiNumber <= 57) {
                passwordComplexityFactors[1] = 2;  // 包含数字
            } else if ((asciiNumber >= 97 && asciiNumber <= 122) || (asciiNumber >= 65 && asciiNumber <= 90)) {
                passwordComplexityFactors[2] = 4;  // 包含大小写
            } else {
                passwordComplexityFactors[3] = 8;  // 包含特殊字符
            }
        }
        return Arrays.stream(passwordComplexityFactors).sum();
    }

    /**
     * 生成数字随机数，可用于生成验证码
     */
    public static String genRandomNumbers(int... digit) {
        if (digit.length == 0) {
            digit = new int[]{6};
        }
        return RandomUtil.randomNumbers(digit[0]);
    }

    /**
     * 生成随机字符串（包含字母和数字）
     */
    public static String genRandomString(int length) {
        return RandomUtil.randomString(length);
    }

    /**
     * 使用gzip压缩字符串
     */
    public static String compress(String str) {
        if (isEmpty(str)) {
            return str;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(str.getBytes());
        } catch (IOException e) {
            log.warn("字符串压缩失败: {}", e.getMessage());
        }
        return Base64.encodeBase64String(out.toByteArray());
    }

    /**
     * 使用gzip解压缩
     */
    public static String decompress(String compressedStr) {
        if (isEmpty(compressedStr)) {
            return compressedStr;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = null;
        GZIPInputStream ginzip = null;
        try {
            byte[] compressed = Base64.decodeBase64(compressedStr);
            in = new ByteArrayInputStream(compressed);
            ginzip = new GZIPInputStream(in);
            byte[] buffer = new byte[1024];
            int offset;
            while ((offset = ginzip.read(buffer)) != -1) {
                out.write(buffer, 0, offset);
            }
            return out.toString();
        } catch (IOException e) {
            log.warn("字符串解压缩失败: {}", e.getMessage());
            return null;
        } finally {
            if (ginzip != null) {
                try {
                    ginzip.close();
                } catch (IOException ignored) {}
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {}
            }
            try {
                out.close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 将字节数组转换为十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * 计算文件的SHA-256哈希值并返回Hex字符串
     */
    public static String calculateFileSha256Hex(java.nio.file.Path filePath) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            try (java.io.InputStream fis = java.nio.file.Files.newInputStream(filePath)) {
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hashBytes = digest.digest();
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException | java.io.IOException e) {
            log.error("无法计算文件哈希: {}", filePath, e);
            return null;
        }
    }

    /**
     * 计算字符串的SHA-256哈希值
     */
    public static String calculateStringSha256Hex(String input) {
        if (isEmpty(input)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes());
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("无法计算字符串哈希: {}", input, e);
            return null;
        }
    }

    /**
     * 安全地转换字符串为整数
     */
    public static Integer safeParseInt(String str, Integer defaultValue) {
        if (isEmpty(str)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 安全地转换字符串为长整数
     */
    public static Long safeParseLong(String str, Long defaultValue) {
        if (isEmpty(str)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(str.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 判断是否为有效的邮箱地址
     */
    public static boolean isValidEmail(String email) {
        if (isEmpty(email)) {
            return false;
        }
        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";
        return email.matches(emailRegex);
    }

    /**
     * 判断是否为有效的手机号码
     */
    public static boolean isValidPhoneNumber(String phone) {
        if (isEmpty(phone)) {
            return false;
        }
        String phoneRegex = "^1[3-9]\\d{9}$";
        return phone.matches(phoneRegex);
    }
}
