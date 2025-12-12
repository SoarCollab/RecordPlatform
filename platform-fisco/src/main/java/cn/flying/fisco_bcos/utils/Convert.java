package cn.flying.fisco_bcos.utils;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 数据转换工具类
 * 提供十六进制字符串与字节数组的相互转换，以及时间戳格式化
 */
public final class Convert {

    private static final DateTimeFormatter DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private Convert() {
        // 工具类禁止实例化
    }

    /**
     * 十六进制字符串转 Byte 包装类数组
     *
     * @param hexString 十六进制字符串 (可带 0x/0X 前缀)
     * @return Byte 包装类数组
     * @throws IllegalArgumentException 如果输入为 null、空字符串或长度为奇数
     */
    public static Byte[] hexToByte(String hexString) {
        byte[] primitiveBytes = hexTobyte(hexString);
        Byte[] bytes = new Byte[primitiveBytes.length];
        for (int i = 0; i < primitiveBytes.length; i++) {
            bytes[i] = primitiveBytes[i];
        }
        return bytes;
    }

    /**
     * 十六进制字符串转 byte 原始类型数组
     *
     * @param hexString 十六进制字符串 (可带 0x/0X 前缀)
     * @return byte 原始类型数组
     * @throws IllegalArgumentException 如果输入为 null、空字符串或长度为奇数
     */
    public static byte[] hexTobyte(String hexString) {
        if (hexString == null || hexString.isEmpty()) {
            throw new IllegalArgumentException("十六进制字符串不能为空");
        }

        // 去掉 0x 或 0X 前缀
        if (hexString.length() >= 2 &&
            (hexString.startsWith("0x") || hexString.startsWith("0X"))) {
            hexString = hexString.substring(2);
        }

        // 空内容检查 (如输入仅为 "0x")
        if (hexString.isEmpty()) {
            return new byte[0];
        }

        // 检查字符串长度是否为偶数
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("十六进制字符串的长度必须是偶数");
        }

        // 创建 byte 数组
        byte[] bytes = new byte[hexString.length() / 2];

        // 每两个字符转换为一个 byte
        for (int i = 0; i < bytes.length; i++) {
            String byteStr = hexString.substring(i * 2, i * 2 + 2);
            bytes[i] = (byte) Integer.parseInt(byteStr, 16);
        }

        return bytes;
    }

    /**
     * Byte 包装类数组转十六进制字符串
     *
     * @param bytes Byte 包装类数组
     * @return 带 0x 前缀的十六进制字符串
     */
    public static String bytesToHex(Byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "0x";
        }
        StringBuilder result = new StringBuilder("0x");
        for (Byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }

    /**
     * byte 原始类型数组转十六进制字符串
     *
     * @param bytes byte 原始类型数组
     * @return 带 0x 前缀的十六进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "0x";
        }
        StringBuilder result = new StringBuilder("0x");
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }

    /**
     * 时间戳转日期字符串
     *
     * @param uploadTimeMilli 毫秒级时间戳
     * @return 格式化的日期字符串 (yyyy-MM-dd HH:mm:ss)，如果输入为 null 则返回空字符串
     */
    public static String timeStampToDate(Long uploadTimeMilli) {
        if (uploadTimeMilli == null) {
            return "";
        }
        return DATE_FORMATTER.format(Instant.ofEpochMilli(uploadTimeMilli));
    }
}
