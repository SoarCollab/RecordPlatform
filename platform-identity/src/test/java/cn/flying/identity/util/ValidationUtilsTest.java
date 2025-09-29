package cn.flying.identity.util;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationUtils 工具类单元测试
 * 测试所有参数验证和数据验证方法
 *
 * @author 王贝强
 */
class ValidationUtilsTest {

    @Test
    void testRequireNonNull_Success() {
        String value = "test";
        Result<String> result = ValidationUtils.requireNonNull(value, "参数不能为空");

        assertTrue(result.isSuccess());
        assertEquals(value, result.getData());
    }

    @Test
    void testRequireNonNull_Failure() {
        Result<String> result = ValidationUtils.requireNonNull(null, "参数不能为空");

        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
        assertEquals("参数不能为空", result.getMessage());
    }

    @Test
    void testRequireNonBlank_Success() {
        Result<String> result = ValidationUtils.requireNonBlank("test", "字符串不能为空");

        assertTrue(result.isSuccess());
        assertEquals("test", result.getData());
    }

    @Test
    void testRequireNonBlank_Failure() {
        Result<String> result1 = ValidationUtils.requireNonBlank(null, "字符串不能为空");
        Result<String> result2 = ValidationUtils.requireNonBlank("", "字符串不能为空");
        Result<String> result3 = ValidationUtils.requireNonBlank("   ", "字符串不能为空");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
        assertFalse(result3.isSuccess());
    }

    @Test
    void testRequireNonEmptyCollection_Success() {
        List<String> list = Arrays.asList("a", "b", "c");
        Result<List<String>> result = ValidationUtils.requireNonEmpty(list, "集合不能为空");

        assertTrue(result.isSuccess());
        assertEquals(list, result.getData());
    }

    @Test
    void testRequireNonEmptyCollection_Failure() {
        List<String> emptyList = new ArrayList<>();
        Result<List<String>> result = ValidationUtils.requireNonEmpty(emptyList, "集合不能为空");

        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), result.getCode());
    }

    @Test
    void testRequireNonEmptyMap_Success() {
        Map<String, String> map = new HashMap<>();
        map.put("key", "value");
        Result<Map<String, String>> result = ValidationUtils.requireNonEmpty(map, "Map不能为空");

        assertTrue(result.isSuccess());
        assertEquals(map, result.getData());
    }

    @Test
    void testRequireNonEmptyMap_Failure() {
        Map<String, String> emptyMap = new HashMap<>();
        Result<Map<String, String>> result = ValidationUtils.requireNonEmpty(emptyMap, "Map不能为空");

        assertFalse(result.isSuccess());
    }

    @Test
    void testRequireNonEmptyArray_Success() {
        String[] array = {"a", "b", "c"};
        Result<String[]> result = ValidationUtils.requireNonEmpty(array, "数组不能为空");

        assertTrue(result.isSuccess());
        assertArrayEquals(array, result.getData());
    }

    @Test
    void testRequireNonEmptyArray_Failure() {
        String[] emptyArray = new String[0];
        Result<String[]> result = ValidationUtils.requireNonEmpty(emptyArray, "数组不能为空");

        assertFalse(result.isSuccess());
    }

    @Test
    void testRequireTrue_Success() {
        Result<Boolean> result = ValidationUtils.requireTrue(true, "条件必须为真");

        assertTrue(result.isSuccess());
        assertTrue(result.getData());
    }

    @Test
    void testRequireTrue_Failure() {
        Result<Boolean> result = ValidationUtils.requireTrue(false, "条件必须为真");

        assertFalse(result.isSuccess());
        assertEquals("条件必须为真", result.getMessage());
    }

    @Test
    void testRequireFalse_Success() {
        Result<Boolean> result = ValidationUtils.requireFalse(false, "条件必须为假");

        assertTrue(result.isSuccess());
        assertFalse(result.getData());
    }

    @Test
    void testRequireFalse_Failure() {
        Result<Boolean> result = ValidationUtils.requireFalse(true, "条件必须为假");

        assertFalse(result.isSuccess());
    }

    @Test
    void testRequireLength_Success() {
        String value = "test123";
        Result<String> result = ValidationUtils.requireLength(value, 5, 10, "长度必须在5-10之间");

        assertTrue(result.isSuccess());
        assertEquals(value, result.getData());
    }

    @Test
    void testRequireLength_Failure() {
        Result<String> result1 = ValidationUtils.requireLength("abc", 5, 10, "长度必须在5-10之间");
        Result<String> result2 = ValidationUtils.requireLength("12345678901", 5, 10, "长度必须在5-10之间");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
    }

    @Test
    void testRequireRangeInteger_Success() {
        Result<Integer> result = ValidationUtils.requireRange(50, 1, 100, "值必须在1-100之间");

        assertTrue(result.isSuccess());
        assertEquals(50, result.getData());
    }

    @Test
    void testRequireRangeInteger_Failure() {
        Result<Integer> result1 = ValidationUtils.requireRange(0, 1, 100, "值必须在1-100之间");
        Result<Integer> result2 = ValidationUtils.requireRange(101, 1, 100, "值必须在1-100之间");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
    }

    @Test
    void testRequireRangeLong_Success() {
        Result<Long> result = ValidationUtils.requireRange(50L, 1L, 100L, "值必须在1-100之间");

        assertTrue(result.isSuccess());
        assertEquals(50L, result.getData());
    }

    @Test
    void testRequireValidEmail_Success() {
        String email = "test@example.com";
        Result<String> result = ValidationUtils.requireValidEmail(email, "邮箱格式不正确");

        assertTrue(result.isSuccess());
        assertEquals(email, result.getData());
    }

    @Test
    void testRequireValidEmail_Failure() {
        Result<String> result1 = ValidationUtils.requireValidEmail("invalid-email", "邮箱格式不正确");
        Result<String> result2 = ValidationUtils.requireValidEmail("test@", "邮箱格式不正确");
        Result<String> result3 = ValidationUtils.requireValidEmail("@example.com", "邮箱格式不正确");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
        assertFalse(result3.isSuccess());
    }

    @Test
    void testRequireValidPhone_Success() {
        String phone = "13800138000";
        Result<String> result = ValidationUtils.requireValidPhone(phone, "手机号格式不正确");

        assertTrue(result.isSuccess());
        assertEquals(phone, result.getData());
    }

    @Test
    void testRequireValidPhone_Failure() {
        Result<String> result1 = ValidationUtils.requireValidPhone("12345678901", "手机号格式不正确");
        Result<String> result2 = ValidationUtils.requireValidPhone("1380013800", "手机号格式不正确");
        Result<String> result3 = ValidationUtils.requireValidPhone("23800138000", "手机号格式不正确");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
        assertFalse(result3.isSuccess());
    }

    @Test
    void testRequireValidUrl_Success() {
        String url = "https://www.example.com";
        Result<String> result = ValidationUtils.requireValidUrl(url, "URL格式不正确");

        assertTrue(result.isSuccess());
        assertEquals(url, result.getData());
    }

    @Test
    void testRequireValidUrl_Failure() {
        Result<String> result1 = ValidationUtils.requireValidUrl("not-a-url", "URL格式不正确");
        Result<String> result2 = ValidationUtils.requireValidUrl("www.example.com", "URL格式不正确");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
    }

    @Test
    void testRequireValidIPv4_Success() {
        String ip = "192.168.1.1";
        Result<String> result = ValidationUtils.requireValidIPv4(ip, "IP地址格式不正确");

        assertTrue(result.isSuccess());
        assertEquals(ip, result.getData());
    }

    @Test
    void testRequireValidIPv4_Failure() {
        Result<String> result1 = ValidationUtils.requireValidIPv4("256.1.1.1", "IP地址格式不正确");
        Result<String> result2 = ValidationUtils.requireValidIPv4("192.168.1", "IP地址格式不正确");
        Result<String> result3 = ValidationUtils.requireValidIPv4("192.168.1.256", "IP地址格式不正确");

        assertFalse(result1.isSuccess());
        assertFalse(result2.isSuccess());
        assertFalse(result3.isSuccess());
    }

    @Test
    void testRequirePatternString_Success() {
        String value = "ABC123";
        Result<String> result = ValidationUtils.requirePattern(value, "[A-Z]{3}\\d{3}", "格式不匹配");

        assertTrue(result.isSuccess());
        assertEquals(value, result.getData());
    }

    @Test
    void testRequirePatternString_Failure() {
        Result<String> result = ValidationUtils.requirePattern("abc123", "[A-Z]{3}\\d{3}", "格式不匹配");

        assertFalse(result.isSuccess());
    }

    @Test
    void testRequirePatternObject_Success() {
        Pattern pattern = Pattern.compile("[A-Z]{3}\\d{3}");
        String value = "ABC123";
        Result<String> result = ValidationUtils.requirePattern(value, pattern, "格式不匹配");

        assertTrue(result.isSuccess());
        assertEquals(value, result.getData());
    }

    @Test
    void testRequireCondition_Success() {
        String value = "test";
        Result<String> result = ValidationUtils.requireCondition(
                value,
                v -> v.length() > 3,
                "长度必须大于3"
        );

        assertTrue(result.isSuccess());
        assertEquals(value, result.getData());
    }

    @Test
    void testRequireCondition_Failure() {
        Result<String> result = ValidationUtils.requireCondition(
                "ab",
                v -> v.length() > 3,
                "长度必须大于3"
        );

        assertFalse(result.isSuccess());
    }

    @Test
    void testRequirePasswordStrength_Success() {
        String password = "Test123!";
        Result<String> result = ValidationUtils.requirePasswordStrength(
                password, 8, true, true, true, true
        );

        assertTrue(result.isSuccess());
        assertEquals(password, result.getData());
    }

    @Test
    void testRequirePasswordStrength_TooShort() {
        Result<String> result = ValidationUtils.requirePasswordStrength(
                "Test1!", 8, true, true, true, true
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("长度至少为8位"));
    }

    @Test
    void testRequirePasswordStrength_NoDigit() {
        Result<String> result = ValidationUtils.requirePasswordStrength(
                "TestTest!", 8, true, true, true, true
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("必须包含数字"));
    }

    @Test
    void testRequirePasswordStrength_NoLowercase() {
        Result<String> result = ValidationUtils.requirePasswordStrength(
                "TEST123!", 8, true, true, true, true
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("必须包含小写字母"));
    }

    @Test
    void testRequirePasswordStrength_NoUppercase() {
        Result<String> result = ValidationUtils.requirePasswordStrength(
                "test123!", 8, true, true, true, true
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("必须包含大写字母"));
    }

    @Test
    void testRequirePasswordStrength_NoSpecialChar() {
        Result<String> result = ValidationUtils.requirePasswordStrength(
                "Test1234", 8, true, true, true, true
        );

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("必须包含特殊字符"));
    }

    @Test
    void testValidateAll_AllSuccess() {
        Result<String> result1 = ValidationUtils.requireNonBlank("test", "错误1");
        Result<Integer> result2 = ValidationUtils.requireRange(50, 1, 100, "错误2");
        Result<String> result3 = ValidationUtils.requireValidEmail("test@example.com", "错误3");

        Result<Void> result = ValidationUtils.validateAll(result1, result2, result3);

        assertTrue(result.isSuccess());
    }

    @Test
    void testValidateAll_OneFailure() {
        Result<String> result1 = ValidationUtils.requireNonBlank("test", "错误1");
        Result<Integer> result2 = ValidationUtils.requireRange(150, 1, 100, "值超出范围");
        Result<String> result3 = ValidationUtils.requireValidEmail("test@example.com", "错误3");

        Result<Void> result = ValidationUtils.validateAll(result1, result2, result3);

        assertFalse(result.isSuccess());
        assertEquals("值超出范围", result.getMessage());
    }

    @Test
    void testGetEmailPattern() {
        Pattern pattern = ValidationUtils.getEmailPattern();

        assertNotNull(pattern);
        assertTrue(pattern.matcher("test@example.com").matches());
        assertFalse(pattern.matcher("invalid-email").matches());
    }

    @Test
    void testGetPhonePattern() {
        Pattern pattern = ValidationUtils.getPhonePattern();

        assertNotNull(pattern);
        assertTrue(pattern.matcher("13800138000").matches());
        assertFalse(pattern.matcher("12345678901").matches());
    }

    @Test
    void testGetUrlPattern() {
        Pattern pattern = ValidationUtils.getUrlPattern();

        assertNotNull(pattern);
        assertTrue(pattern.matcher("https://www.example.com").matches());
        assertFalse(pattern.matcher("not-a-url").matches());
    }

    @Test
    void testGetIPv4Pattern() {
        Pattern pattern = ValidationUtils.getIPv4Pattern();

        assertNotNull(pattern);
        assertTrue(pattern.matcher("192.168.1.1").matches());
        assertFalse(pattern.matcher("256.1.1.1").matches());
    }
}
