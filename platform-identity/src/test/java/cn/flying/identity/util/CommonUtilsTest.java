package cn.flying.identity.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CommonUtils 工具类单元测试
 * 测试所有通用工具方法
 *
 * @author 王贝强
 */
class CommonUtilsTest {

    @Test
    void testAssertNotNull_Success() {
        assertDoesNotThrow(() -> CommonUtils.assertNotNull("test", "不能为空"));
    }

    @Test
    void testAssertNotNull_Failure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            CommonUtils.assertNotNull(null, "参数不能为空");
        });
        assertEquals("参数不能为空", exception.getMessage());
    }

    @Test
    void testAssertNotEmpty_String_Success() {
        assertDoesNotThrow(() -> CommonUtils.assertNotEmpty("test", "不能为空"));
    }

    @Test
    void testAssertNotEmpty_String_Failure() {
        assertThrows(IllegalArgumentException.class, () -> {
            CommonUtils.assertNotEmpty("", "字符串不能为空");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            CommonUtils.assertNotEmpty((String) null, "字符串不能为空");
        });
    }

    @Test
    void testAssertNotEmpty_Collection_Success() {
        List<String> list = Arrays.asList("a", "b");
        assertDoesNotThrow(() -> CommonUtils.assertNotEmpty(list, "不能为空"));
    }

    @Test
    void testAssertNotEmpty_Collection_Failure() {
        assertThrows(IllegalArgumentException.class, () -> {
            CommonUtils.assertNotEmpty(new ArrayList<>(), "集合不能为空");
        });
    }

    @Test
    void testAssertTrue_Success() {
        assertDoesNotThrow(() -> CommonUtils.assertTrue(true, "条件必须为真"));
    }

    @Test
    void testAssertTrue_Failure() {
        assertThrows(IllegalArgumentException.class, () -> {
            CommonUtils.assertTrue(false, "条件不满足");
        });
    }

    @Test
    void testIsEmpty_String() {
        assertTrue(CommonUtils.isEmpty((String) null));
        assertTrue(CommonUtils.isEmpty(""));
        assertFalse(CommonUtils.isEmpty("test"));
    }

    @Test
    void testIsEmpty_Object() {
        assertTrue(CommonUtils.isEmpty((Object) null));
        assertTrue(CommonUtils.isEmpty(""));
        assertTrue(CommonUtils.isEmpty(new ArrayList<>()));
        assertTrue(CommonUtils.isEmpty(new Object[0]));
        assertTrue(CommonUtils.isEmpty(new HashMap<>()));
        assertFalse(CommonUtils.isEmpty("test"));
        assertFalse(CommonUtils.isEmpty(Arrays.asList("a")));
        assertFalse(CommonUtils.isEmpty(new Object[]{1}));
    }

    @Test
    void testIsNotEmpty() {
        assertTrue(CommonUtils.isNotEmpty("test"));
        assertFalse(CommonUtils.isNotEmpty((String) null));
        assertFalse(CommonUtils.isNotEmpty(""));
    }

    @Test
    void testIsBlank() {
        assertTrue(CommonUtils.isBlank(null));
        assertTrue(CommonUtils.isBlank(""));
        assertTrue(CommonUtils.isBlank("   "));
        assertTrue(CommonUtils.isBlank("\t\n"));
        assertFalse(CommonUtils.isBlank("test"));
        assertFalse(CommonUtils.isBlank(" test "));
    }

    @Test
    void testIsNotBlank() {
        assertTrue(CommonUtils.isNotBlank("test"));
        assertFalse(CommonUtils.isNotBlank(null));
        assertFalse(CommonUtils.isNotBlank("  "));
    }

    @Test
    void testEquals() {
        assertTrue(CommonUtils.equals(null, null));
        assertTrue(CommonUtils.equals("test", "test"));
        assertFalse(CommonUtils.equals("test", null));
        assertFalse(CommonUtils.equals(null, "test"));
        assertFalse(CommonUtils.equals("test1", "test2"));
    }

    @Test
    void testNotEquals() {
        assertTrue(CommonUtils.notEquals("test1", "test2"));
        assertFalse(CommonUtils.notEquals("test", "test"));
    }

    @Test
    void testGetOrElse() {
        assertEquals("test", CommonUtils.getOrElse("test", "default"));
        assertEquals("default", CommonUtils.getOrElse(null, "default"));
        assertEquals("default", CommonUtils.getOrElse("", "default"));
    }

    @Test
    void testGetOrEmpty() {
        assertEquals("test", CommonUtils.getOrEmpty("test"));
        assertEquals("", CommonUtils.getOrEmpty(null));
    }

    @Test
    void testConvert2BigDecimal() {
        assertEquals(new BigDecimal("123.45"), CommonUtils.convert2BigDecimal("123.45"));
        assertEquals(BigDecimal.ZERO, CommonUtils.convert2BigDecimal(null));
        assertEquals(BigDecimal.ZERO, CommonUtils.convert2BigDecimal(""));
        assertEquals(BigDecimal.ZERO, CommonUtils.convert2BigDecimal("invalid"));
    }

    @Test
    void testFormatDate() {
        Date date = new Date(1609459200000L); // 2021-01-01 00:00:00 UTC
        String formatted = CommonUtils.formatDate(date, "yyyy-MM-dd");
        assertNotNull(formatted);
        assertTrue(formatted.contains("2021"));
        
        assertNull(CommonUtils.formatDate(null, "yyyy-MM-dd"));
    }

    @Test
    void testFormatDateTime() {
        LocalDateTime dateTime = LocalDateTime.of(2021, 1, 1, 12, 30, 45);
        String formatted = CommonUtils.formatDateTime(dateTime, "yyyy-MM-dd HH:mm:ss");
        assertEquals("2021-01-01 12:30:45", formatted);
        
        assertNull(CommonUtils.formatDateTime(null, "yyyy-MM-dd"));
    }

    @Test
    void testParseDate() {
        Date date = CommonUtils.parseDate("2021-01-01", "yyyy-MM-dd");
        assertNotNull(date);
        
        assertNull(CommonUtils.parseDate(null, "yyyy-MM-dd"));
        assertNull(CommonUtils.parseDate("invalid", "yyyy-MM-dd"));
    }

    @Test
    void testParseDateTime() {
        LocalDateTime dateTime = CommonUtils.parseDateTime("2021-01-01 12:30:45", "yyyy-MM-dd HH:mm:ss");
        assertNotNull(dateTime);
        assertEquals(2021, dateTime.getYear());
        assertEquals(1, dateTime.getMonthValue());
        assertEquals(1, dateTime.getDayOfMonth());
        
        assertNull(CommonUtils.parseDateTime(null, "yyyy-MM-dd"));
        assertNull(CommonUtils.parseDateTime("invalid", "yyyy-MM-dd"));
    }

    @Test
    void testThumbnailText() {
        assertEquals("a**d", CommonUtils.thumbnailText("abcd", 1, 1));
        assertEquals("ab**ef", CommonUtils.thumbnailText("abcdef", 2, 2));
        assertEquals("test", CommonUtils.thumbnailText("test", 5, 5));
        assertEquals("test", CommonUtils.thumbnailText("test"));
    }

    @Test
    void testPasswordSecurityLevel() {
        assertEquals(0, CommonUtils.passwordSecurityLevel(null));
        assertEquals(0, CommonUtils.passwordSecurityLevel(""));
        assertEquals(0, CommonUtils.passwordSecurityLevel("12345"));
        assertEquals(1, CommonUtils.passwordSecurityLevel("123456")); // 只有数字
        assertEquals(2, CommonUtils.passwordSecurityLevel("abc123")); // 数字+小写
        assertEquals(3, CommonUtils.passwordSecurityLevel("Abc123")); // 数字+小写+大写
        assertEquals(4, CommonUtils.passwordSecurityLevel("Abc123!")); // 数字+小写+大写+特殊字符
    }

    @Test
    void testPasswordComplexityCompute() {
        assertEquals(0, CommonUtils.passwordComplexityCompute(null));
        assertEquals(0, CommonUtils.passwordComplexityCompute("1234567"));
        assertEquals(1, CommonUtils.passwordComplexityCompute("12345678")); // 大于8位
        assertEquals(3, CommonUtils.passwordComplexityCompute("abc12345")); // 大于8位+数字
        assertEquals(7, CommonUtils.passwordComplexityCompute("Abc12345")); // 大于8位+数字+大小写
        assertEquals(15, CommonUtils.passwordComplexityCompute("Abc123!@")); // 完整复杂度
    }

    @Test
    void testGenRandomNumbers() {
        String random1 = CommonUtils.genRandomNumbers();
        assertEquals(6, random1.length());
        assertTrue(random1.matches("\\d{6}"));
        
        String random2 = CommonUtils.genRandomNumbers(8);
        assertEquals(8, random2.length());
        assertTrue(random2.matches("\\d{8}"));
    }

    @Test
    void testGenRandomString() {
        String random = CommonUtils.genRandomString(10);
        assertEquals(10, random.length());
    }

    @Test
    void testCompressAndDecompress() {
        String original = "This is a test string for compression";
        String compressed = CommonUtils.compress(original);
        assertNotNull(compressed);
        assertNotEquals(original, compressed);
        
        String decompressed = CommonUtils.decompress(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    void testCompress_Null() {
        assertNull(CommonUtils.compress(null));
        assertEquals("", CommonUtils.compress(""));
    }

    @Test
    void testDecompress_Null() {
        assertNull(CommonUtils.decompress(null));
        assertEquals("", CommonUtils.decompress(""));
    }

    @Test
    void testBytesToHex() {
        byte[] bytes = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xab, (byte) 0xcd, (byte) 0xef};
        String hex = CommonUtils.bytesToHex(bytes);
        assertEquals("0123456789abcdef", hex);
        
        assertNull(CommonUtils.bytesToHex(null));
    }

    @Test
    void testCalculateStringSha256Hex() {
        String input = "test";
        String hash = CommonUtils.calculateStringSha256Hex(input);
        assertNotNull(hash);
        assertEquals(64, hash.length()); // SHA-256产生64位十六进制字符串
        
        assertNull(CommonUtils.calculateStringSha256Hex(null));
        assertNull(CommonUtils.calculateStringSha256Hex(""));
    }

    @Test
    void testCalculateFileSha256Hex(@TempDir Path tempDir) throws IOException {
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "test content");
        
        String hash = CommonUtils.calculateFileSha256Hex(testFile);
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void testSafeParseInt() {
        assertEquals(123, CommonUtils.safeParseInt("123", 0));
        assertEquals(0, CommonUtils.safeParseInt(null, 0));
        assertEquals(0, CommonUtils.safeParseInt("", 0));
        assertEquals(0, CommonUtils.safeParseInt("abc", 0));
        assertEquals(-1, CommonUtils.safeParseInt("invalid", -1));
        assertEquals(100, CommonUtils.safeParseInt("  100  ", 0));
    }

    @Test
    void testSafeParseLong() {
        assertEquals(123L, CommonUtils.safeParseLong("123", 0L));
        assertEquals(0L, CommonUtils.safeParseLong(null, 0L));
        assertEquals(0L, CommonUtils.safeParseLong("", 0L));
        assertEquals(0L, CommonUtils.safeParseLong("abc", 0L));
        assertEquals(-1L, CommonUtils.safeParseLong("invalid", -1L));
        assertEquals(9999999999L, CommonUtils.safeParseLong("9999999999", 0L));
    }

    @Test
    void testIsValidEmail() {
        assertTrue(CommonUtils.isValidEmail("test@example.com"));
        assertTrue(CommonUtils.isValidEmail("user.name@example.co.uk"));
        assertTrue(CommonUtils.isValidEmail("user+tag@example.com"));
        
        assertFalse(CommonUtils.isValidEmail(null));
        assertFalse(CommonUtils.isValidEmail(""));
        assertFalse(CommonUtils.isValidEmail("invalid-email"));
        assertFalse(CommonUtils.isValidEmail("@example.com"));
        assertFalse(CommonUtils.isValidEmail("test@"));
        assertFalse(CommonUtils.isValidEmail("test@.com"));
    }

    @Test
    void testIsValidPhoneNumber() {
        assertTrue(CommonUtils.isValidPhoneNumber("13800138000"));
        assertTrue(CommonUtils.isValidPhoneNumber("15912345678"));
        assertTrue(CommonUtils.isValidPhoneNumber("18612345678"));
        
        assertFalse(CommonUtils.isValidPhoneNumber(null));
        assertFalse(CommonUtils.isValidPhoneNumber(""));
        assertFalse(CommonUtils.isValidPhoneNumber("12345678901"));
        assertFalse(CommonUtils.isValidPhoneNumber("1380013800"));
        assertFalse(CommonUtils.isValidPhoneNumber("23800138000"));
    }
}
