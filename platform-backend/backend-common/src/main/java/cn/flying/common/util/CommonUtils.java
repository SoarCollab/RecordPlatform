package cn.flying.common.util;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
public abstract class CommonUtils {

    private CommonUtils() {
    }

    /**
     * check whether the object is null or not, if it is, throw an exception and display the message.
     *
     * @param object
     * @param message
     */
    public static void assertNotNull(final Object object, final String message) {
        if (object == null) {
            throw new IllegalArgumentException(message);
        }
    }

    public static void assertNotEmpty(final String value, final String message) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * check whether the collection is null or empty. if it is, throw an exception and display the message.
     *
     * @param c
     * @param message
     */
    public static void assertNotEmpty(final Collection<?> c, final String message) {
        assertNotNull(c, message);

        if (c.isEmpty())
            throw new IllegalArgumentException(message);
    }

    /**
     * assert that the statement is true, otherwise throw an exception with the provided message.
     *
     * @param cond
     * @param message
     */
    public static void assertTrue(final boolean cond, final String message) {
        if (!cond) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * determines whether the string is null or of length 0
     *
     * @param s
     * @return
     */
    public static boolean isEmpty(final String s) {
        return s == null || s.isEmpty();
    }

    /**
     * determines whether Map,Collection,String,Array,Long is null or of size 0
     *
     * @param o
     * @return
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

    /**
     * determines if the string is not empty. a string is not empty if it is not null and has a length > 0.
     *
     * @param s
     * @return
     */
    public static boolean isNotEmpty(final String s) {
        return !isEmpty(s);
    }

    /**
     * determines if a string is blank or not. a string is blank if its empty or if it only contains spaces.
     *
     * @param s
     * @return
     */
    public static boolean isBlank(final String s) {
        return isEmpty(s) || s.trim().isEmpty();
    }

    public static boolean isNotBlank(final String s) {
        return !isBlank(s);
    }

    /**
     * 实例必须实现equals方法
     *
     * @param o1
     * @param o2
     * @return
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
     * 校验值是否为空，为空则返回默认值
     * @param value
     * @param defaultValue
     * @param <P>
     * @return
     */
    public static <P> P getOrElse(P value, P defaultValue){
        if(isEmpty(value)){
            value = defaultValue;
        }

        return value;
    }

    /**
     * 校验值是否为null，null值转换成空字符串
     * @param value
     * @return
     */
    public static String getOrEmpty(String value){
        if(value == null){
            value = "";
        }

        return value;
    }

    public static BigDecimal convert2BigDecimal(String value){
        BigDecimal result = BigDecimal.ZERO;
        if(isNotEmpty(value)){
            try {
                result = new BigDecimal(value.trim());
            } catch (Exception ignored) {}
        }

        return result;
    }

    /**
     * 格式化日期字符串
     * @param date
     * @param format
     * @return
     */
    public static String formatDate(Date date, String format){
        if(date == null){
            return null;
        }

        return new SimpleDateFormat(format).format(date);
    }

    /**
     * 解析日期字符串
     * @param dateStr
     * @param format
     * @return
     */
    public static Date parseDate(String dateStr, String format) {
        if (isNotEmpty(dateStr)) {
            try {
                return new SimpleDateFormat(format).parse(dateStr);
            } catch (Exception ignored) {

            }
        }

        return null;
    }

    /**
     * 缩略文字
     *
     * @param text
     * @param prefix 显示保留前缀
     * @param suffix 显示保留后缀
     * @return
     */
    public static String thumbnailText(String text, Integer prefix, Integer suffix) {
        if (prefix + suffix >= text.length()) {
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
     * 0-弱口令
     * 1-弱口令
     * 2-一般
     * 3-中
     * 4-高
     *
     * @param password
     * @return
     */
    public static int passwordSecurityLevel(String password) {
        int securityLevelFlag = 0;
        if (password.length() < 6) {
            return 0;
        } else {
            int[] securityLevelFlags = new int[]{0, 0, 0, 0};
            for (int i = 0; i < password.length(); i++) {
                int asciiNumber = Character.codePointAt(password.substring(i, i + 1), 0);
                if (asciiNumber >= 48 && asciiNumber <= 57) {
                    securityLevelFlags[0] = 1;  //digital
                } else if (asciiNumber >= 97 && asciiNumber <= 122) {
                    securityLevelFlags[1] = 1;  //lowercase
                } else if (asciiNumber >= 65 && asciiNumber <= 90) {
                    securityLevelFlags[2] = 1;  //uppercase
                } else {
                    securityLevelFlags[3] = 1;  //specialize
                }
            }

            for (int levelFlag : securityLevelFlags) {
                if (levelFlag == 1) {
                    securityLevelFlag++;
                }
            }

            return securityLevelFlag;
        }
    }

    /**
     * 密码复杂度计算
     * 1-大于8位，2-包含数字，4-包含大小写字母，8=包含特殊字符
     * 计算公式：f(x+y),
     * eg：f(3) = 1 + 2, f(7) = 1 + 2 + 4
     *
     * @param password 原始密码
     * @return f(n)
     */
    public static int passwordComplexityCompute(String password) {
        int f = 0;
        if (password.length() >= 8) {
            int[] passwordComplexityFactors = new int[]{1, 0, 0, 0};
            char[] charSequence = password.toCharArray();
            for (char c : charSequence) {
                int asciiNumber = Character.codePointAt(String.valueOf(c), 0);
                if (asciiNumber >= 48 && asciiNumber <= 57) {
                    passwordComplexityFactors[1] = 2;  //包含数字
                } else if ((asciiNumber >= 97 && asciiNumber <= 122) || (asciiNumber >= 65 && asciiNumber <= 90)) {
                    passwordComplexityFactors[2] = 4;  //包含大小写
                } else {
                    passwordComplexityFactors[3] = 8;  //包含特殊字符
                }
            }

            f = Arrays.stream(passwordComplexityFactors).sum();
        }

        return f;
    }

    /**
     * 生成数字随机数, 可用于生产验证码
     *
     * @param digit 位数
     * @return
     */
    public static String genRandomNumbers(int... digit) {
        if (digit.length == 0) {
            digit = new int[]{6};
        }
        return RandomUtil.randomNumbers(digit[0]);
    }

    /**
     * 使用gzip压缩字符串
     * @param str 要压缩的字符串
     * @return
     */
    public static String compress(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GZIPOutputStream gzip = null;
        try {
            gzip = new GZIPOutputStream(out);
            gzip.write(str.getBytes());
        } catch (IOException e) {
            log.warn( "Failed to compress string: {}", e.getMessage());
        } finally {
            if (gzip != null) {
                try {
                    gzip.close();
                } catch (IOException e) {
                    log.warn( "Failed to close gzip stream: {}", e.getMessage());
                }
            }
        }
        return Base64.encodeBase64String(out.toByteArray());
    }

    /**
     * 使用gzip解压缩
     * @param compressedStr 压缩字符串
     * @return
     */
    public static String uncompressed(String compressedStr) {
        if (compressedStr == null) {
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayInputStream in = null;
        GZIPInputStream ginzip = null;
        byte[] compressed;
        String decompressed = null;
        try {
            compressed = Base64.decodeBase64(compressedStr);
            in = new ByteArrayInputStream(compressed);
            ginzip = new GZIPInputStream(in);
            byte[] buffer = new byte[1024];
            int offset;
            while ((offset = ginzip.read(buffer)) != -1) {
                out.write(buffer, 0, offset);
            }
            decompressed = out.toString();
        } catch (IOException e) {
            log.warn( "Failed to uncompressed string: {}", e.getMessage());
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
        return decompressed;
    }


}
