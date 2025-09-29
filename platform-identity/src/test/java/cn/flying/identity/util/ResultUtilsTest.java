package cn.flying.identity.util;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResultUtils 工具类单元测试
 * 测试所有Result结果处理方法
 *
 * @author 王贝强
 */
class ResultUtilsTest {

    @Test
    void testIsSuccess_True() {
        Result<String> result = Result.success("test");
        assertTrue(ResultUtils.isSuccess(result));
    }

    @Test
    void testIsSuccess_False() {
        Result<String> result = Result.error("错误");
        assertFalse(ResultUtils.isSuccess(result));
    }

    @Test
    void testIsSuccess_Null() {
        assertFalse(ResultUtils.isSuccess(null));
    }

    @Test
    void testIsFailure_True() {
        Result<String> result = Result.error("错误");
        assertTrue(ResultUtils.isFailure(result));
    }

    @Test
    void testIsFailure_False() {
        Result<String> result = Result.success("test");
        assertFalse(ResultUtils.isFailure(result));
    }

    @Test
    void testGetData_Success() {
        Result<String> result = Result.success("test");
        String data = ResultUtils.getData(result);

        assertEquals("test", data);
    }

    @Test
    void testGetData_Failure() {
        Result<String> result = Result.error("错误");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ResultUtils.getData(result);
        });

        assertTrue(exception.getMessage().contains("操作失败"));
        assertTrue(exception.getMessage().contains("错误"));
    }

    @Test
    void testGetDataOrDefault_Success() {
        Result<String> result = Result.success("test");
        String data = ResultUtils.getDataOrDefault(result, "default");

        assertEquals("test", data);
    }

    @Test
    void testGetDataOrDefault_Failure() {
        Result<String> result = Result.error("错误");
        String data = ResultUtils.getDataOrDefault(result, "default");

        assertEquals("default", data);
    }

    @Test
    void testGetDataOrElse_Success() {
        Result<String> result = Result.success("test");
        String data = ResultUtils.getDataOrElse(result, () -> "default");

        assertEquals("test", data);
    }

    @Test
    void testGetDataOrElse_Failure() {
        Result<String> result = Result.error("错误");
        String data = ResultUtils.getDataOrElse(result, () -> "default");

        assertEquals("default", data);
    }

    @Test
    void testCheckResult_Success() {
        Result<String> result = Result.success("test");

        assertDoesNotThrow(() -> ResultUtils.checkResult(result));
    }

    @Test
    void testCheckResult_Failure() {
        Result<String> result = Result.error("错误");

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            ResultUtils.checkResult(result);
        });

        assertTrue(exception.getMessage().contains("操作失败"));
        assertTrue(exception.getMessage().contains("错误"));
    }

    @Test
    void testExtractError_Success() {
        Result<String> result = Result.success("test");
        Result<Integer> extractedError = ResultUtils.extractError(result);

        assertNull(extractedError);
    }

    @Test
    void testExtractError_Failure() {
        Result<String> result = Result.error("错误信息");
        Result<Integer> extractedError = ResultUtils.extractError(result);

        assertNotNull(extractedError);
        assertFalse(extractedError.isSuccess());
        assertEquals("错误信息", extractedError.getMessage());
    }

    @Test
    void testTransform_Success() {
        Result<String> result = Result.success("test");
        Result<Integer> transformed = ResultUtils.transform(result, 123);

        assertTrue(transformed.isSuccess());
        assertEquals(123, transformed.getData());
    }

    @Test
    void testTransform_Failure() {
        Result<String> result = Result.error("错误");
        Result<Integer> transformed = ResultUtils.transform(result, 123);

        assertFalse(transformed.isSuccess());
        assertEquals("错误", transformed.getMessage());
        assertEquals(123, transformed.getData());
    }

    @Test
    void testMap_Success() {
        Result<String> result = Result.success("123");
        Result<Integer> mapped = ResultUtils.map(result, Integer::parseInt);

        assertTrue(mapped.isSuccess());
        assertEquals(123, mapped.getData());
    }

    @Test
    void testMap_Failure() {
        Result<String> result = Result.error("错误");
        Result<Integer> mapped = ResultUtils.map(result, Integer::parseInt);

        assertFalse(mapped.isSuccess());
        assertEquals("错误", mapped.getMessage());
    }

    @Test
    void testMap_Exception() {
        Result<String> result = Result.success("abc");
        Result<Integer> mapped = ResultUtils.map(result, Integer::parseInt);

        assertFalse(mapped.isSuccess());
        assertTrue(mapped.getMessage().contains("数据转换失败"));
    }

    @Test
    void testFlatMap_Success() {
        Result<String> result = Result.success("123");
        Result<Integer> flatMapped = ResultUtils.flatMap(result, s -> {
            int value = Integer.parseInt(s);
            return Result.success(value * 2);
        });

        assertTrue(flatMapped.isSuccess());
        assertEquals(246, flatMapped.getData());
    }

    @Test
    void testFlatMap_Failure() {
        Result<String> result = Result.error("错误");
        Result<Integer> flatMapped = ResultUtils.flatMap(result, s -> {
            int value = Integer.parseInt(s);
            return Result.success(value * 2);
        });

        assertFalse(flatMapped.isSuccess());
        assertEquals("错误", flatMapped.getMessage());
    }

    @Test
    void testFlatMap_Exception() {
        Result<String> result = Result.success("abc");
        Result<Integer> flatMapped = ResultUtils.flatMap(result, s -> {
            int value = Integer.parseInt(s);
            return Result.success(value * 2);
        });

        assertFalse(flatMapped.isSuccess());
        assertTrue(flatMapped.getMessage().contains("链式操作失败"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlatten_Success() {
        Result<String> innerResult = Result.success("test");
        // 创建嵌套的Result，使用类型转换
        Result<Result<String>> nestedResult = (Result<Result<String>>) (Result<?>) Result.success(innerResult);

        Result<String> flattened = ResultUtils.flatten(nestedResult);

        assertTrue(flattened.isSuccess());
        assertEquals("test", flattened.getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFlatten_Failure() {
        Result<Result<String>> nestedResult = (Result<Result<String>>) (Result<?>) Result.error("外层错误");

        Result<String> flattened = ResultUtils.flatten(nestedResult);

        assertFalse(flattened.isSuccess());
        assertEquals("外层错误", flattened.getMessage());
    }

    @Test
    void testIfSuccess_Execute() {
        Result<String> result = Result.success("test");
        final boolean[] executed = {false};

        ResultUtils.ifSuccess(result, () -> executed[0] = true);

        assertTrue(executed[0]);
    }

    @Test
    void testIfSuccess_NotExecute() {
        Result<String> result = Result.error("错误");
        final boolean[] executed = {false};

        ResultUtils.ifSuccess(result, () -> executed[0] = true);

        assertFalse(executed[0]);
    }

    @Test
    void testIfFailure_Execute() {
        Result<String> result = Result.error("错误");
        final boolean[] executed = {false};

        ResultUtils.ifFailure(result, () -> executed[0] = true);

        assertTrue(executed[0]);
    }

    @Test
    void testIfFailure_NotExecute() {
        Result<String> result = Result.success("test");
        final boolean[] executed = {false};

        ResultUtils.ifFailure(result, () -> executed[0] = true);

        assertFalse(executed[0]);
    }

    @Test
    void testSuccess_WithData() {
        Result<String> result = ResultUtils.success("test");

        assertTrue(result.isSuccess());
        assertEquals("test", result.getData());
    }

    @Test
    void testSuccess_Empty() {
        Result<String> result = ResultUtils.success();

        assertTrue(result.isSuccess());
    }

    @Test
    void testError_WithMessage() {
        Result<String> result = ResultUtils.error("错误信息");

        assertFalse(result.isSuccess());
        assertEquals("错误信息", result.getMessage());
    }

    @Test
    void testError_WithEnum() {
        Result<String> result = ResultUtils.error(ResultEnum.SYSTEM_ERROR);

        assertFalse(result.isSuccess());
        assertEquals(ResultEnum.SYSTEM_ERROR.getCode(), result.getCode());
    }

    @Test
    void testSafeExecute_Success() {
        Result<String> result = ResultUtils.safeExecute(() -> "test");

        assertTrue(result.isSuccess());
        assertEquals("test", result.getData());
    }

    @Test
    void testSafeExecute_Exception() {
        Result<Integer> result = ResultUtils.safeExecute(() -> {
            throw new RuntimeException("测试异常");
        });

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("操作执行失败"));
        assertTrue(result.getMessage().contains("测试异常"));
    }

    @Test
    void testSafeExecute_WithCustomErrorMessage() {
        Result<Integer> result = ResultUtils.safeExecute(() -> {
            throw new RuntimeException("测试异常");
        }, "自定义错误");

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("自定义错误"));
        assertTrue(result.getMessage().contains("测试异常"));
    }
}
