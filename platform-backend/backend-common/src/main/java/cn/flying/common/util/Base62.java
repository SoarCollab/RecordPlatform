package cn.flying.common.util;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Base62 编码/解码工具类
 * 使用字符集: 0-9, A-Z, a-z (共62个字符)
 * URL安全，无特殊字符
 */
public final class Base62 {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(62);
    private static final int[] DECODE_TABLE = new int[128];

    static {
        // 初始化解码表
        Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE_TABLE[ALPHABET.charAt(i)] = i;
        }
    }

    private Base62() {
        // 工具类禁止实例化
    }

    /**
     * 将字节数组编码为 Base62 字符串
     *
     * @param data 原始字节数组
     * @return Base62 编码字符串
     */
    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }

        // 转为正整数（添加前导 0x01 避免符号问题）
        byte[] withSign = new byte[data.length + 1];
        withSign[0] = 0x01;
        System.arraycopy(data, 0, withSign, 1, data.length);
        BigInteger number = new BigInteger(withSign);

        StringBuilder result = new StringBuilder();
        while (number.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = number.divideAndRemainder(BASE);
            result.insert(0, ALPHABET.charAt(divRem[1].intValue()));
            number = divRem[0];
        }

        return result.toString();
    }

    /**
     * 将 Base62 字符串解码为字节数组
     *
     * @param encoded Base62 编码字符串
     * @return 原始字节数组
     */
    public static byte[] decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return new byte[0];
        }

        // 转换为 BigInteger
        BigInteger number = BigInteger.ZERO;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c >= DECODE_TABLE.length || DECODE_TABLE[c] == -1) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            number = number.multiply(BASE).add(BigInteger.valueOf(DECODE_TABLE[c]));
        }

        // 转为字节数组
        byte[] decoded = number.toByteArray();

        // 移除前导符号字节（如果存在）
        int offset = 0;
        if (decoded.length > 1 && decoded[0] == 0x01) {
            offset = 1;
        } else if (decoded.length > 1 && decoded[0] == 0x00) {
            offset = 1;
        }

        // 移除编码时附加的前导符号字节，返回原始字节数组
        return Arrays.copyOfRange(decoded, offset, decoded.length);
    }

    /**
     * 将 long 值编码为 Base62 字符串
     *
     * @param value long 值
     * @return Base62 编码字符串
     */
    public static String encodeLong(long value) {
        if (value == 0) {
            return String.valueOf(ALPHABET.charAt(0));
        }

        // 处理负数：转为无符号表示
        BigInteger number = value >= 0
            ? BigInteger.valueOf(value)
            : BigInteger.valueOf(value).add(BigInteger.ONE.shiftLeft(64));

        StringBuilder result = new StringBuilder();
        while (number.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divRem = number.divideAndRemainder(BASE);
            result.insert(0, ALPHABET.charAt(divRem[1].intValue()));
            number = divRem[0];
        }

        return result.toString();
    }

    /**
     * 将 Base62 字符串解码为 long 值
     *
     * @param encoded Base62 编码字符串
     * @return long 值
     */
    public static long decodeLong(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return 0;
        }

        BigInteger number = BigInteger.ZERO;
        for (int i = 0; i < encoded.length(); i++) {
            char c = encoded.charAt(i);
            if (c >= DECODE_TABLE.length || DECODE_TABLE[c] == -1) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            number = number.multiply(BASE).add(BigInteger.valueOf(DECODE_TABLE[c]));
        }

        return number.longValue();
    }
}
