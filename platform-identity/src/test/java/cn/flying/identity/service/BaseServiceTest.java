package cn.flying.identity.service;

import cn.flying.identity.util.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 基础服务单元测试
 * 测试范围：安全执行、参数验证、工具方法、JSON处理、Result处理、缓存操作、日志记录
 *
 * @author 王贝强
 * @create 2025-01-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BaseServiceTest {

    @Mock
    private CacheUtils cacheUtils;

    // 测试用的具体实现类
    private TestBaseService testBaseService;

    // 测试数据常量
    private static final String TEST_STRING = "test";
    private static final String TEST_JSON = "{\"key\":\"value\"}";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_INVALID_EMAIL = "invalid-email";
    private static final String TEST_CACHE_KEY = "test:cache:key";
    private static final String TEST_CACHE_VALUE = "test_cache_value";
    private static final String TEST_ERROR_MESSAGE = "测试错误信息";

    @BeforeEach
    void setUp() {
        testBaseService = new TestBaseService();
        testBaseService.cacheUtils = cacheUtils;
    }

    // ==================== 安全执行方法测试 ====================

    @Test
    void testSafeExecute_Success() {
        try (MockedStatic<WebContextUtils> webContextUtils = mockStatic(WebContextUtils.class)) {
            Result<String> expectedResult = Result.success("success");
            webContextUtils.when(() -> WebContextUtils.safeExecute(any(Supplier.class), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(expectedResult);

            // 执行测试
            Result<String> result = testBaseService.testSafeExecute(() -> Result.success("success"), TEST_ERROR_MESSAGE);

            // 验证结果
            assertTrue(result.isSuccess());
            assertEquals("success", result.getData());
            webContextUtils.verify(() -> WebContextUtils.safeExecute(any(Supplier.class), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testSafeExecuteVoid_Success() {
        try (MockedStatic<WebContextUtils> webContextUtils = mockStatic(WebContextUtils.class)) {
            Result<Void> expectedResult = Result.success();
            webContextUtils.when(() -> WebContextUtils.safeExecuteVoid(any(Supplier.class), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(expectedResult);

            // 执行测试
            Result<Void> result = testBaseService.testSafeExecuteVoid(() -> Result.success(), TEST_ERROR_MESSAGE);

            // 验证结果
            assertTrue(result.isSuccess());
            webContextUtils.verify(() -> WebContextUtils.safeExecuteVoid(any(Supplier.class), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testSafeExecuteData_Success() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> expectedResult = Result.success("data");
            resultUtils.when(() -> ResultUtils.safeExecute(any(Supplier.class), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(expectedResult);

            // 执行测试
            Result<String> result = testBaseService.testSafeExecuteData(() -> "data", TEST_ERROR_MESSAGE);

            // 验证结果
            assertTrue(result.isSuccess());
            assertEquals("data", result.getData());
            resultUtils.verify(() -> ResultUtils.safeExecute(any(Supplier.class), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testSafeExecuteAction_Success() {
        // 执行测试 - 正常操作
        Result<Void> result = testBaseService.testSafeExecuteAction(() -> {
            // 模拟正常操作
        }, TEST_ERROR_MESSAGE);

        // 验证结果
        assertTrue(result.isSuccess());
    }

    @Test
    void testSafeExecuteAction_Exception() {
        // 执行测试 - 异常操作
        Result<Void> result = testBaseService.testSafeExecuteAction(() -> {
            throw new RuntimeException("操作失败");
        }, TEST_ERROR_MESSAGE);

        // 验证结果
        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains(TEST_ERROR_MESSAGE));
        assertTrue(result.getMessage().contains("操作失败"));
    }

    // ==================== 参数验证方法测试 ====================

    @Test
    void testValidateParam_Success() {
        try (MockedStatic<WebContextUtils> webContextUtils = mockStatic(WebContextUtils.class)) {
            webContextUtils.when(() -> WebContextUtils.validateParam(eq(true), eq(ResultEnum.PARAM_IS_INVALID)))
                    .thenReturn(null);

            // 执行测试
            Result<String> result = testBaseService.testValidateParam(true, ResultEnum.PARAM_IS_INVALID);

            // 验证结果
            assertNull(result);
            webContextUtils.verify(() -> WebContextUtils.validateParam(eq(true), eq(ResultEnum.PARAM_IS_INVALID)));
        }
    }

    @Test
    void testRequireNonNull_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            validationUtils.when(() -> ValidationUtils.requireNonNull(eq(TEST_STRING), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(null);

            // 执行测试
            Result<String> result = testBaseService.testRequireNonNull(TEST_STRING, TEST_ERROR_MESSAGE);

            // 验证结果
            assertNull(result);
            validationUtils.verify(() -> ValidationUtils.requireNonNull(eq(TEST_STRING), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testRequireNonBlank_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            validationUtils.when(() -> ValidationUtils.requireNonBlank(eq(TEST_STRING), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(null);

            // 执行测试
            Result<String> result = testBaseService.testRequireNonBlank(TEST_STRING, TEST_ERROR_MESSAGE);

            // 验证结果
            assertNull(result);
            validationUtils.verify(() -> ValidationUtils.requireNonBlank(eq(TEST_STRING), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testRequireNonEmpty_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            List<String> testList = new ArrayList<>();
            testList.add("item");
            validationUtils.when(() -> ValidationUtils.requireNonEmpty(eq(testList), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(null);

            // 执行测试
            Result<List<String>> result = testBaseService.testRequireNonEmpty(testList, TEST_ERROR_MESSAGE);

            // 验证结果
            assertNull(result);
            validationUtils.verify(() -> ValidationUtils.requireNonEmpty(eq(testList), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testRequireTrue_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            validationUtils.when(() -> ValidationUtils.requireTrue(eq(true), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(null);

            // 执行测试
            Result<Boolean> result = testBaseService.testRequireTrue(true, TEST_ERROR_MESSAGE);

            // 验证结果
            assertNull(result);
            validationUtils.verify(() -> ValidationUtils.requireTrue(eq(true), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testRequireValidEmail_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            validationUtils.when(() -> ValidationUtils.requireValidEmail(eq(TEST_EMAIL), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(null);

            // 执行测试
            Result<String> result = testBaseService.testRequireValidEmail(TEST_EMAIL, TEST_ERROR_MESSAGE);

            // 验证结果
            assertNull(result);
            validationUtils.verify(() -> ValidationUtils.requireValidEmail(eq(TEST_EMAIL), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testRequireCondition_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            Predicate<String> predicate = s -> s.length() > 0;
            validationUtils.when(() -> ValidationUtils.requireCondition(eq(TEST_STRING), eq(predicate), eq(TEST_ERROR_MESSAGE)))
                    .thenReturn(null);

            // 执行测试
            Result<String> result = testBaseService.testRequireCondition(TEST_STRING, predicate, TEST_ERROR_MESSAGE);

            // 验证结果
            assertNull(result);
            validationUtils.verify(() -> ValidationUtils.requireCondition(eq(TEST_STRING), eq(predicate), eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testValidateAll_Success() {
        try (MockedStatic<ValidationUtils> validationUtils = mockStatic(ValidationUtils.class)) {
            Result<Void> expectedResult = Result.success();
            validationUtils.when(() -> ValidationUtils.validateAll(any(Result[].class)))
                    .thenReturn(expectedResult);

            // 执行测试
            Result<String> result1 = Result.success("test1");
            Result<String> result2 = Result.success("test2");
            Result<Void> result = testBaseService.testValidateAll(result1, result2);

            // 验证结果
            assertTrue(result.isSuccess());
            validationUtils.verify(() -> ValidationUtils.validateAll(any(Result[].class)));
        }
    }

    // ==================== 工具方法测试 ====================

    @Test
    void testIsBlank_True() {
        try (MockedStatic<CommonUtils> commonUtils = mockStatic(CommonUtils.class)) {
            commonUtils.when(() -> CommonUtils.isBlank(eq(""))).thenReturn(true);

            // 执行测试
            boolean result = testBaseService.testIsBlank("");

            // 验证结果
            assertTrue(result);
            commonUtils.verify(() -> CommonUtils.isBlank(eq("")));
        }
    }

    @Test
    void testIsNotBlank_True() {
        try (MockedStatic<CommonUtils> commonUtils = mockStatic(CommonUtils.class)) {
            commonUtils.when(() -> CommonUtils.isNotBlank(eq(TEST_STRING))).thenReturn(true);

            // 执行测试
            boolean result = testBaseService.testIsNotBlank(TEST_STRING);

            // 验证结果
            assertTrue(result);
            commonUtils.verify(() -> CommonUtils.isNotBlank(eq(TEST_STRING)));
        }
    }

    @Test
    void testIsEmpty_True() {
        // 直接测试真实行为 - null应该返回true
        boolean result = testBaseService.testIsEmpty(null);
        assertTrue(result);
    }

    @Test
    void testIsNotEmpty_True() {
        // 直接测试真实行为 - TEST_STRING应该返回true
        boolean result = testBaseService.testIsNotEmpty(TEST_STRING);
        assertTrue(result);
    }

    @Test
    void testGetOrElse_WithValue() {
        try (MockedStatic<CommonUtils> commonUtils = mockStatic(CommonUtils.class)) {
            commonUtils.when(() -> CommonUtils.getOrElse(eq(TEST_STRING), eq("default"))).thenReturn(TEST_STRING);

            // 执行测试
            String result = testBaseService.testGetOrElse(TEST_STRING, "default");

            // 验证结果
            assertEquals(TEST_STRING, result);
            commonUtils.verify(() -> CommonUtils.getOrElse(eq(TEST_STRING), eq("default")));
        }
    }

    @Test
    void testGenerateVerifyCode() {
        try (MockedStatic<CommonUtils> commonUtils = mockStatic(CommonUtils.class)) {
            String expectedCode = "123456";
            commonUtils.when(() -> CommonUtils.genRandomNumbers(eq(6))).thenReturn(expectedCode);

            // 执行测试
            String result = testBaseService.testGenerateVerifyCode(6);

            // 验证结果
            assertEquals(expectedCode, result);
            commonUtils.verify(() -> CommonUtils.genRandomNumbers(eq(6)));
        }
    }

    @Test
    void testFormatDateTime() {
        try (MockedStatic<CommonUtils> commonUtils = mockStatic(CommonUtils.class)) {
            LocalDateTime now = LocalDateTime.now();
            String pattern = "yyyy-MM-dd HH:mm:ss";
            String expectedFormatted = "2024-01-14 12:00:00";
            commonUtils.when(() -> CommonUtils.formatDateTime(eq(now), eq(pattern))).thenReturn(expectedFormatted);

            // 执行测试
            String result = testBaseService.testFormatDateTime(now, pattern);

            // 验证结果
            assertEquals(expectedFormatted, result);
            commonUtils.verify(() -> CommonUtils.formatDateTime(eq(now), eq(pattern)));
        }
    }

    @Test
    void testGetCurrentClientIp() {
        try (MockedStatic<WebContextUtils> webContextUtils = mockStatic(WebContextUtils.class)) {
            String expectedIp = "192.168.1.100";
            webContextUtils.when(WebContextUtils::getCurrentClientIp).thenReturn(expectedIp);

            // 执行测试
            String result = testBaseService.testGetCurrentClientIp();

            // 验证结果
            assertEquals(expectedIp, result);
            webContextUtils.verify(WebContextUtils::getCurrentClientIp);
        }
    }

    // ==================== JSON工具方法测试 ====================

    @Test
    void testToJson() {
        try (MockedStatic<JsonUtils> jsonUtils = mockStatic(JsonUtils.class)) {
            Object testObject = new TestData("test");
            jsonUtils.when(() -> JsonUtils.toJson(eq(testObject))).thenReturn(TEST_JSON);

            // 执行测试
            String result = testBaseService.testToJson(testObject);

            // 验证结果
            assertEquals(TEST_JSON, result);
            jsonUtils.verify(() -> JsonUtils.toJson(eq(testObject)));
        }
    }

    @Test
    void testFromJson() {
        try (MockedStatic<JsonUtils> jsonUtils = mockStatic(JsonUtils.class)) {
            TestData expectedObject = new TestData("test");
            jsonUtils.when(() -> JsonUtils.fromJson(eq(TEST_JSON), eq(TestData.class))).thenReturn(expectedObject);

            // 执行测试
            TestData result = testBaseService.testFromJson(TEST_JSON, TestData.class);

            // 验证结果
            assertEquals(expectedObject, result);
            jsonUtils.verify(() -> JsonUtils.fromJson(eq(TEST_JSON), eq(TestData.class)));
        }
    }

    @Test
    void testIsValidJson_True() {
        try (MockedStatic<JsonUtils> jsonUtils = mockStatic(JsonUtils.class)) {
            jsonUtils.when(() -> JsonUtils.isValidJson(eq(TEST_JSON))).thenReturn(true);

            // 执行测试
            boolean result = testBaseService.testIsValidJson(TEST_JSON);

            // 验证结果
            assertTrue(result);
            jsonUtils.verify(() -> JsonUtils.isValidJson(eq(TEST_JSON)));
        }
    }

    // ==================== Result工具方法测试 ====================

    @Test
    void testSuccess_WithData() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> expectedResult = Result.success(TEST_STRING);
            resultUtils.when(() -> ResultUtils.success(eq(TEST_STRING))).thenReturn(expectedResult);

            // 执行测试
            Result<String> result = testBaseService.testSuccess(TEST_STRING);

            // 验证结果
            assertTrue(result.isSuccess());
            assertEquals(TEST_STRING, result.getData());
            resultUtils.verify(() -> ResultUtils.success(eq(TEST_STRING)));
        }
    }

    @Test
    void testSuccess_WithoutData() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<Void> expectedResult = Result.success();
            resultUtils.when(ResultUtils::success).thenReturn(expectedResult);

            // 执行测试
            Result<Void> result = testBaseService.testSuccessVoid();

            // 验证结果
            assertTrue(result.isSuccess());
            resultUtils.verify(ResultUtils::success);
        }
    }

    @Test
    void testError_WithMessage() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> expectedResult = Result.error(TEST_ERROR_MESSAGE);
            resultUtils.when(() -> ResultUtils.error(eq(TEST_ERROR_MESSAGE))).thenReturn(expectedResult);

            // 执行测试
            Result<String> result = testBaseService.testError(TEST_ERROR_MESSAGE);

            // 验证结果
            assertFalse(result.isSuccess());
            resultUtils.verify(() -> ResultUtils.error(eq(TEST_ERROR_MESSAGE)));
        }
    }

    @Test
    void testError_WithEnum() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> expectedResult = Result.error(ResultEnum.PARAM_IS_INVALID);
            resultUtils.when(() -> ResultUtils.error(eq(ResultEnum.PARAM_IS_INVALID))).thenReturn(expectedResult);

            // 执行测试
            Result<String> result = testBaseService.testError(ResultEnum.PARAM_IS_INVALID);

            // 验证结果
            assertFalse(result.isSuccess());
            resultUtils.verify(() -> ResultUtils.error(eq(ResultEnum.PARAM_IS_INVALID)));
        }
    }

    @Test
    void testIsSuccess() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> successResult = Result.success(TEST_STRING);
            resultUtils.when(() -> ResultUtils.isSuccess(eq(successResult))).thenReturn(true);

            // 执行测试
            boolean result = testBaseService.testIsSuccess(successResult);

            // 验证结果
            assertTrue(result);
            resultUtils.verify(() -> ResultUtils.isSuccess(eq(successResult)));
        }
    }

    @Test
    void testGetDataOrDefault() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> successResult = Result.success(TEST_STRING);
            String defaultValue = "default";
            resultUtils.when(() -> ResultUtils.getDataOrDefault(eq(successResult), eq(defaultValue)))
                    .thenReturn(TEST_STRING);

            // 执行测试
            String result = testBaseService.testGetDataOrDefault(successResult, defaultValue);

            // 验证结果
            assertEquals(TEST_STRING, result);
            resultUtils.verify(() -> ResultUtils.getDataOrDefault(eq(successResult), eq(defaultValue)));
        }
    }

    @Test
    void testMapResult() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> inputResult = Result.success(TEST_STRING);
            Result<Integer> expectedResult = Result.success(4); // TEST_STRING.length()
            Function<String, Integer> mapper = String::length;
            resultUtils.when(() -> ResultUtils.map(eq(inputResult), eq(mapper))).thenReturn(expectedResult);

            // 执行测试
            Result<Integer> result = testBaseService.testMapResult(inputResult, mapper);

            // 验证结果
            assertTrue(result.isSuccess());
            assertEquals(4, result.getData());
            resultUtils.verify(() -> ResultUtils.map(eq(inputResult), eq(mapper)));
        }
    }

    @Test
    void testFlatMapResult() {
        try (MockedStatic<ResultUtils> resultUtils = mockStatic(ResultUtils.class)) {
            Result<String> inputResult = Result.success(TEST_STRING);
            Result<Integer> expectedResult = Result.success(4);
            Function<String, Result<Integer>> mapper = s -> Result.success(s.length());
            resultUtils.when(() -> ResultUtils.flatMap(eq(inputResult), eq(mapper))).thenReturn(expectedResult);

            // 执行测试
            Result<Integer> result = testBaseService.testFlatMapResult(inputResult, mapper);

            // 验证结果
            assertTrue(result.isSuccess());
            assertEquals(4, result.getData());
            resultUtils.verify(() -> ResultUtils.flatMap(eq(inputResult), eq(mapper)));
        }
    }

    // ==================== 缓存工具方法测试 ====================

    @Test
    void testGetFromCacheOrLoad() {
        // Mock缓存工具
        when(cacheUtils.getOrSet(eq(TEST_CACHE_KEY), any(Supplier.class), eq(3600L), eq(TimeUnit.SECONDS)))
                .thenReturn(TEST_CACHE_VALUE);

        // 执行测试
        String result = testBaseService.testGetFromCacheOrLoad(TEST_CACHE_KEY, () -> TEST_CACHE_VALUE, 3600L);

        // 验证结果
        assertEquals(TEST_CACHE_VALUE, result);
        verify(cacheUtils).getOrSet(eq(TEST_CACHE_KEY), any(Supplier.class), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void testSetCache() {
        // 执行测试
        testBaseService.testSetCache(TEST_CACHE_KEY, TEST_CACHE_VALUE, 3600L);

        // 验证缓存操作
        verify(cacheUtils).set(TEST_CACHE_KEY, TEST_CACHE_VALUE, 3600L, TimeUnit.SECONDS);
    }

    @Test
    void testDeleteCache() {
        // 执行测试
        testBaseService.testDeleteCache(TEST_CACHE_KEY);

        // 验证缓存删除操作
        verify(cacheUtils).delete(TEST_CACHE_KEY);
    }

    @Test
    void testExistsCache_True() {
        // Mock缓存存在
        when(cacheUtils.exists(TEST_CACHE_KEY)).thenReturn(true);

        // 执行测试
        boolean result = testBaseService.testExistsCache(TEST_CACHE_KEY);

        // 验证结果
        assertTrue(result);
        verify(cacheUtils).exists(TEST_CACHE_KEY);
    }

    @Test
    void testExistsCache_False() {
        // Mock缓存不存在
        when(cacheUtils.exists(TEST_CACHE_KEY)).thenReturn(false);

        // 执行测试
        boolean result = testBaseService.testExistsCache(TEST_CACHE_KEY);

        // 验证结果
        assertFalse(result);
        verify(cacheUtils).exists(TEST_CACHE_KEY);
    }

    // ==================== 日志方法测试 ====================

    @Test
    void testLogMethods() {
        // 测试各种日志方法（主要验证方法调用，不抛异常即可）
        assertDoesNotThrow(() -> {
            testBaseService.testLogDebug("调试信息: {}", "参数");
            testBaseService.testLogInfo("信息: {}", "参数");
            testBaseService.testLogWarn("警告: {}", "参数");
            testBaseService.testLogError("错误: {}", "参数");
            testBaseService.testLogError("错误信息", new RuntimeException("测试异常"), "参数");
        });
    }

    // 测试用的具体BaseService实现类
    private static class TestBaseService extends BaseService {

        public <T> Result<T> testSafeExecute(Supplier<Result<T>> operation, String errorMessage) {
            return safeExecute(operation, errorMessage);
        }

        public Result<Void> testSafeExecuteVoid(Supplier<Result<Void>> operation, String errorMessage) {
            return safeExecuteVoid(operation, errorMessage);
        }

        public <T> Result<T> testSafeExecuteData(Supplier<T> operation, String errorMessage) {
            return safeExecuteData(operation, errorMessage);
        }

        public Result<Void> testSafeExecuteAction(Runnable operation, String errorMessage) {
            return safeExecuteAction(operation, errorMessage);
        }

        public <T> Result<T> testValidateParam(boolean condition, ResultEnum errorEnum) {
            return validateParam(condition, errorEnum);
        }

        public <T> Result<T> testRequireNonNull(T value, String message) {
            return requireNonNull(value, message);
        }

        public Result<String> testRequireNonBlank(String value, String message) {
            return requireNonBlank(value, message);
        }

        public <T extends java.util.Collection<?>> Result<T> testRequireNonEmpty(T collection, String message) {
            return requireNonEmpty(collection, message);
        }

        public Result<Boolean> testRequireTrue(boolean condition, String message) {
            return requireTrue(condition, message);
        }

        public Result<String> testRequireValidEmail(String email, String message) {
            return requireValidEmail(email, message);
        }

        public <T> Result<T> testRequireCondition(T value, Predicate<T> predicate, String message) {
            return requireCondition(value, predicate, message);
        }

        public Result<Void> testValidateAll(Result<?>... validations) {
            return validateAll(validations);
        }

        public boolean testIsBlank(String str) {
            return isBlank(str);
        }

        public boolean testIsNotBlank(String str) {
            return isNotBlank(str);
        }

        public boolean testIsEmpty(Object obj) {
            return isEmpty(obj);
        }

        public boolean testIsNotEmpty(Object obj) {
            return isNotEmpty(obj);
        }

        public <T> T testGetOrElse(T value, T defaultValue) {
            return getOrElse(value, defaultValue);
        }

        public String testGenerateVerifyCode(int length) {
            return generateVerifyCode(length);
        }

        public String testFormatDateTime(LocalDateTime dateTime, String pattern) {
            return formatDateTime(dateTime, pattern);
        }

        public String testGetCurrentClientIp() {
            return getCurrentClientIp();
        }

        public String testToJson(Object object) {
            return toJson(object);
        }

        public <T> T testFromJson(String json, Class<T> clazz) {
            return fromJson(json, clazz);
        }

        public boolean testIsValidJson(String json) {
            return isValidJson(json);
        }

        public <T> Result<T> testSuccess(T data) {
            return success(data);
        }

        public <T> Result<T> testSuccessVoid() {
            return success();
        }

        public <T> Result<T> testError(String message) {
            return error(message);
        }

        public <T> Result<T> testError(ResultEnum errorEnum) {
            return error(errorEnum);
        }

        public boolean testIsSuccess(Result<?> result) {
            return isSuccess(result);
        }

        public <T> T testGetDataOrDefault(Result<T> result, T defaultValue) {
            return getDataOrDefault(result, defaultValue);
        }

        public <T, R> Result<R> testMapResult(Result<T> result, Function<T, R> mapper) {
            return mapResult(result, mapper);
        }

        public <T, R> Result<R> testFlatMapResult(Result<T> result, Function<T, Result<R>> mapper) {
            return flatMapResult(result, mapper);
        }

        public String testGetFromCacheOrLoad(String key, Supplier<String> dataSupplier, long timeout) {
            return getFromCacheOrLoad(key, dataSupplier, timeout);
        }

        public void testSetCache(String key, String value, long timeout) {
            setCache(key, value, timeout);
        }

        public void testDeleteCache(String key) {
            deleteCache(key);
        }

        public boolean testExistsCache(String key) {
            return existsCache(key);
        }

        public void testLogDebug(String message, Object... args) {
            logDebug(message, args);
        }

        public void testLogInfo(String message, Object... args) {
            logInfo(message, args);
        }

        public void testLogWarn(String message, Object... args) {
            logWarn(message, args);
        }

        public void testLogError(String message, Object... args) {
            logError(message, args);
        }

        public void testLogError(String message, Throwable throwable, Object... args) {
            logError(message, throwable, args);
        }
    }

    // 测试用的数据类
    private static class TestData {
        private String value;

        public TestData(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestData testData = (TestData) o;
            return java.util.Objects.equals(value, testData.value);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(value);
        }
    }
}