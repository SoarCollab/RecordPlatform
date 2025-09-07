package cn.flying.fisco_bcos.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @program: fisco_bcos
 * @description:
 * @author: 王贝强
 * @create: 2025-01-12 19:41
 */
public class Convert {
    public static Byte[] hexToByte(String hexString) {
        // 去掉0x前缀
        if (hexString.startsWith("0x")) {
            hexString = hexString.substring(2);
        }

        // 检查字符串长度是否为偶数
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("16进制字符串的长度必须是偶数");
        }

        // 创建Byte数组
        Byte[] bytes = new Byte[hexString.length() / 2];

        // 每两个字符转换为一个Byte
        for (int i = 0; i < bytes.length; i++) {
            String byteStr = hexString.substring(i * 2, i * 2 + 2);
            bytes[i] = (byte) Integer.parseInt(byteStr, 16);
        }

        return bytes;
    }
    public static byte[] hexTobyte(String hexString) {
        // 去掉0x前缀
        if (hexString.startsWith("0x")) {
            hexString = hexString.substring(2);
        }

        // 检查字符串长度是否为偶数
        if (hexString.length() % 2 != 0) {
            throw new IllegalArgumentException("16进制字符串的长度必须是偶数");
        }

        // 创建Byte数组
        byte[] bytes = new byte[hexString.length() / 2];

        // 每两个字符转换为一个Byte
        for (int i = 0; i < bytes.length; i++) {
            String byteStr = hexString.substring(i * 2, i * 2 + 2);
            bytes[i] = (byte) Integer.parseInt(byteStr, 16);
        }

        return bytes;
    }

    public static String bytesToHex(Byte[] bytes) {
        StringBuilder result = new StringBuilder("0x");
        for (Byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }
    public static String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder("0x");
        for (byte b : bytes) {
            result.append(String.format("%02X", b));
        }
        return result.toString();
    }

    public static String timeStampToDate(Long uploadTimeMilli) {
        String date = "";
        if (null != uploadTimeMilli) {
            long uploadTime = uploadTimeMilli;
            Date uploadDate = new Date(uploadTime);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            date = sdf.format(uploadDate);
        }
        return date;
    }
}
