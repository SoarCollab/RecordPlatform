package cn.flying.fisco_bcos.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class ConvertTest {

    @Test
    @DisplayName("Test hexToByte with 0x prefix")
    void testHexToByteWithPrefix() {
        String hexString = "0x48656c6c6f"; // "Hello" in hex
        Byte[] result = Convert.hexToByte(hexString);
        
        assertNotNull(result);
        assertEquals(5, result.length);
        assertEquals((byte) 0x48, result[0]);
        assertEquals((byte) 0x65, result[1]);
        assertEquals((byte) 0x6c, result[2]);
        assertEquals((byte) 0x6c, result[3]);
        assertEquals((byte) 0x6f, result[4]);
    }

    @Test
    @DisplayName("Test hexToByte without 0x prefix")
    void testHexToByteWithoutPrefix() {
        String hexString = "48656c6c6f"; // "Hello" in hex
        Byte[] result = Convert.hexToByte(hexString);
        
        assertNotNull(result);
        assertEquals(5, result.length);
        assertEquals((byte) 0x48, result[0]);
        assertEquals((byte) 0x65, result[1]);
    }

    @Test
    @DisplayName("Test hexToByte with odd length should throw exception")
    void testHexToByteOddLength() {
        String hexString = "0x123"; // Odd length
        
        assertThrows(IllegalArgumentException.class, () -> {
            Convert.hexToByte(hexString);
        }, "16进制字符串的长度必须是偶数");
    }

    @Test
    @DisplayName("Test hexTobyte (primitive) with 0x prefix")
    void testHexTobytePrimitiveWithPrefix() {
        String hexString = "0xDEADBEEF";
        byte[] result = Convert.hexTobyte(hexString);
        
        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals((byte) 0xDE, result[0]);
        assertEquals((byte) 0xAD, result[1]);
        assertEquals((byte) 0xBE, result[2]);
        assertEquals((byte) 0xEF, result[3]);
    }

    @Test
    @DisplayName("Test hexTobyte (primitive) without 0x prefix")
    void testHexTobytePrimitiveWithoutPrefix() {
        String hexString = "DEADBEEF";
        byte[] result = Convert.hexTobyte(hexString);
        
        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals((byte) 0xDE, result[0]);
        assertEquals((byte) 0xAD, result[1]);
    }

    @Test
    @DisplayName("Test hexTobyte (primitive) with odd length should throw exception")
    void testHexTobytePrimitiveOddLength() {
        String hexString = "DEADBEE"; // Odd length
        
        assertThrows(IllegalArgumentException.class, () -> {
            Convert.hexTobyte(hexString);
        }, "16进制字符串的长度必须是偶数");
    }

    @Test
    @DisplayName("Test bytesToHex with Byte array")
    void testBytesToHexByteArray() {
        Byte[] bytes = new Byte[]{(byte) 0x48, (byte) 0x65, (byte) 0x6c, (byte) 0x6c, (byte) 0x6f};
        String result = Convert.bytesToHex(bytes);
        
        assertNotNull(result);
        assertEquals("0x48656C6C6F", result);
        assertTrue(result.startsWith("0x"));
    }

    @Test
    @DisplayName("Test bytesToHex with primitive byte array")
    void testBytesToHexPrimitiveArray() {
        byte[] bytes = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
        String result = Convert.bytesToHex(bytes);
        
        assertNotNull(result);
        assertEquals("0xDEADBEEF", result);
        assertTrue(result.startsWith("0x"));
    }

    @Test
    @DisplayName("Test bytesToHex with empty Byte array")
    void testBytesToHexEmptyByteArray() {
        Byte[] bytes = new Byte[]{};
        String result = Convert.bytesToHex(bytes);
        
        assertNotNull(result);
        assertEquals("0x", result);
    }

    @Test
    @DisplayName("Test bytesToHex with empty primitive array")
    void testBytesToHexEmptyPrimitiveArray() {
        byte[] bytes = new byte[]{};
        String result = Convert.bytesToHex(bytes);
        
        assertNotNull(result);
        assertEquals("0x", result);
    }

    @Test
    @DisplayName("Test round trip conversion - Byte array")
    void testRoundTripByteArray() {
        String original = "0x1234567890ABCDEF";
        Byte[] bytes = Convert.hexToByte(original);
        String result = Convert.bytesToHex(bytes);
        
        assertEquals(original.toUpperCase(), result);
    }

    @Test
    @DisplayName("Test round trip conversion - primitive array")
    void testRoundTripPrimitiveArray() {
        String original = "FEDCBA0987654321";
        byte[] bytes = Convert.hexTobyte(original);
        String result = Convert.bytesToHex(bytes);
        
        assertEquals("0x" + original, result);
    }

    @Test
    @DisplayName("Test timeStampToDate with valid timestamp")
    void testTimeStampToDateValid() {
        // Use a specific timestamp: 2024-01-01 00:00:00
        Long timestamp = 1704067200000L;
        String result = Convert.timeStampToDate(timestamp);
        
        assertNotNull(result);
        assertTrue(result.contains("2024"));
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    @DisplayName("Test timeStampToDate with null timestamp")
    void testTimeStampToDateNull() {
        Long timestamp = null;
        String result = Convert.timeStampToDate(timestamp);
        
        assertNotNull(result);
        assertEquals("", result);
    }

    @Test
    @DisplayName("Test timeStampToDate with zero timestamp")
    void testTimeStampToDateZero() {
        Long timestamp = 0L;
        String result = Convert.timeStampToDate(timestamp);
        
        assertNotNull(result);
        assertTrue(result.contains("1970")); // Unix epoch
    }

    @Test
    @DisplayName("Test timeStampToDate with current timestamp")
    void testTimeStampToDateCurrent() {
        Long timestamp = System.currentTimeMillis();
        String result = Convert.timeStampToDate(timestamp);
        
        assertNotNull(result);
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
    }

    @Test
    @DisplayName("Test hexToByte with mixed case")
    void testHexToByteWithMixedCase() {
        String hexString = "0xAbCdEf";
        Byte[] result = Convert.hexToByte(hexString);
        
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals((byte) 0xAB, result[0]);
        assertEquals((byte) 0xCD, result[1]);
        assertEquals((byte) 0xEF, result[2]);
    }

    @Test
    @DisplayName("Test hexTobyte with mixed case")
    void testHexTobyteWithMixedCase() {
        String hexString = "aBcDeF";
        byte[] result = Convert.hexTobyte(hexString);
        
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals((byte) 0xAB, result[0]);
        assertEquals((byte) 0xCD, result[1]);
        assertEquals((byte) 0xEF, result[2]);
    }

    @Test
    @DisplayName("Test bytesToHex with negative bytes")
    void testBytesToHexNegativeBytes() {
        byte[] bytes = new byte[]{-1, -128, 127, 0};
        String result = Convert.bytesToHex(bytes);
        
        assertNotNull(result);
        assertEquals("0xFF807F00", result);
    }

    @Test
    @DisplayName("Test hexToByte with maximum byte values")
    void testHexToByteMaxValues() {
        String hexString = "0xFF00FF00";
        Byte[] result = Convert.hexToByte(hexString);
        
        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals((byte) 0xFF, result[0]);
        assertEquals((byte) 0x00, result[1]);
        assertEquals((byte) 0xFF, result[2]);
        assertEquals((byte) 0x00, result[3]);
    }
}
