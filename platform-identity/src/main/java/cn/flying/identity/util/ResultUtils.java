package cn.flying.identity.util;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Result 结果处理工具类
 * 提供 Result 类型的常用操作和处理方法
 * 
 * @author 王贝强
 */
public class ResultUtils {

    private ResultUtils() {}

    /**
     * 判断结果是否成功
     * 
     * @param result Result 对象
     * @return 是否成功
     */
    public static boolean isSuccess(Result<?> result) {
        return result != null && result.isSuccess();
    }

    /**
     * 判断结果是否失败
     * 
     * @param result Result 对象
     * @return 是否失败
     */
    public static boolean isFailure(Result<?> result) {
        return !isSuccess(result);
    }

    /**
     * 从 Result 中获取数据，如果成功返回数据，失败则抛出异常
     * 
     * @param result Result 对象
     * @param <T> 数据类型
     * @return 数据
     * @throws RuntimeException 当结果不成功时抛出异常
     */
    public static <T> T getData(Result<T> result) {
        if (isSuccess(result)) {
            return result.getData();
        }
        throw new RuntimeException("操作失败: " + result.getMessage() + ", 错误码: " + result.getCode());
    }

    /**
     * 从 Result 中获取数据，如果成功返回数据，失败则返回默认值
     * 
     * @param result Result 对象
     * @param defaultValue 默认值
     * @param <T> 数据类型
     * @return 成功时返回数据，失败时返回默认值
     */
    public static <T> T getDataOrDefault(Result<T> result, T defaultValue) {
        return isSuccess(result) ? result.getData() : defaultValue;
    }

    /**
     * 从 Result 中获取数据，如果成功返回数据，失败则通过 Supplier 获取默认值
     * 
     * @param result Result 对象
     * @param defaultSupplier 默认值提供者
     * @param <T> 数据类型
     * @return 成功时返回数据，失败时返回默认值
     */
    public static <T> T getDataOrElse(Result<T> result, Supplier<T> defaultSupplier) {
        return isSuccess(result) ? result.getData() : defaultSupplier.get();
    }

    /**
     * 处理结果：成功不做处理，失败则抛出包含错误信息的异常
     * 
     * @param result Result 对象
     * @throws RuntimeException 当结果不成功时抛出异常
     */
    public static void checkResult(Result<?> result) {
        if (isFailure(result)) {
            throw new RuntimeException("操作失败: " + result.getMessage() + ", 错误码: " + result.getCode());
        }
    }

    /**
     * 从 Result 中提取错误信息，并生成新的 Result
     * 
     * @param result 原始 Result 对象
     * @param <T> 原始数据类型
     * @param <R> 新数据类型
     * @return 如果原始结果成功则返回 null，否则返回包含相同错误信息的新 Result
     */
    @SuppressWarnings("unchecked")
    public static <T, R> Result<R> extractError(Result<T> result) {
        if (isSuccess(result)) {
            return null;
        }
        return (Result<R>) Result.error(result.getMessage());
    }

    /**
     * 转换 Result 中的数据类型，保留成功/失败状态和消息
     * 
     * @param result 原始 Result 对象
     * @param data 新数据
     * @param <T> 原始数据类型
     * @param <R> 新数据类型
     * @return 包含新数据但保留原始状态和消息的 Result
     */
    public static <T, R> Result<R> transform(Result<T> result, R data) {
        return new Result<>(result.getCode(), result.getMessage(), data);
    }

    /**
     * 转换 Result 中的数据类型，使用转换函数
     * 
     * @param result 原始 Result 对象
     * @param mapper 数据转换函数
     * @param <T> 原始数据类型
     * @param <R> 新数据类型
     * @return 转换后的 Result 对象
     */
    public static <T, R> Result<R> map(Result<T> result, Function<T, R> mapper) {
        if (isFailure(result)) {
            return new Result<>(result.getCode(), result.getMessage(), null);
        }
        try {
            R newData = mapper.apply(result.getData());
            return Result.success(newData);
        } catch (Exception e) {
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), "数据转换失败: " + e.getMessage(), null);
        }
    }

    /**
     * 链式处理 Result，当前一个 Result 成功时执行下一个操作
     * 
     * @param result 原始 Result 对象
     * @param mapper 链式操作函数
     * @param <T> 原始数据类型
     * @param <R> 新数据类型
     * @return 链式操作后的 Result 对象
     */
    public static <T, R> Result<R> flatMap(Result<T> result, Function<T, Result<R>> mapper) {
        if (isFailure(result)) {
            return new Result<>(result.getCode(), result.getMessage(), null);
        }
        try {
            return mapper.apply(result.getData());
        } catch (Exception e) {
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), "链式操作失败: " + e.getMessage(), null);
        }
    }

    /**
     * 处理嵌套的 Result 对象
     * 
     * @param result 嵌套的 Result 对象
     * @param <T> 数据类型
     * @return 解除嵌套后的 Result 对象
     */
    public static <T> Result<T> flatten(Result<Result<T>> result) {
        if (isFailure(result)) {
            return new Result<>(result.getCode(), result.getMessage(), null);
        }
        return result.getData();
    }

    /**
     * 条件执行：当 Result 成功时执行操作
     * 
     * @param result Result 对象
     * @param action 要执行的操作
     * @param <T> 数据类型
     */
    public static <T> void ifSuccess(Result<T> result, Runnable action) {
        if (isSuccess(result)) {
            action.run();
        }
    }

    /**
     * 条件执行：当 Result 失败时执行操作
     * 
     * @param result Result 对象
     * @param action 要执行的操作
     * @param <T> 数据类型
     */
    public static <T> void ifFailure(Result<T> result, Runnable action) {
        if (isFailure(result)) {
            action.run();
        }
    }

    /**
     * 创建成功的 Result
     * 
     * @param data 数据
     * @param <T> 数据类型
     * @return 成功的 Result 对象
     */
    public static <T> Result<T> success(T data) {
        return Result.success(data);
    }

    /**
     * 创建成功的空 Result
     * 
     * @param <T> 数据类型
     * @return 成功的空 Result 对象
     */
    public static <T> Result<T> success() {
        return Result.success();
    }

    /**
     * 创建失败的 Result
     * 
     * @param message 错误消息
     * @param <T> 数据类型
     * @return 失败的 Result 对象
     */
    @SuppressWarnings("unchecked")
    public static <T> Result<T> error(String message) {
        return (Result<T>) Result.error(message);
    }

    /**
     * 创建失败的 Result
     * 
     * @param resultEnum 结果枚举
     * @param <T> 数据类型
     * @return 失败的 Result 对象
     */
    @SuppressWarnings("unchecked")
    public static <T> Result<T> error(ResultEnum resultEnum) {
        return (Result<T>) Result.error(resultEnum);
    }

    /**
     * 安全执行操作，自动包装异常为 Result
     * 
     * @param supplier 要执行的操作
     * @param <T> 返回数据类型
     * @return 操作结果
     */
    public static <T> Result<T> safeExecute(Supplier<T> supplier) {
        try {
            T result = supplier.get();
            return Result.success(result);
        } catch (Exception e) {
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), "操作执行失败: " + e.getMessage(), null);
        }
    }

    /**
     * 安全执行操作，自动包装异常为 Result，支持自定义错误消息
     * 
     * @param supplier 要执行的操作
     * @param errorMessage 自定义错误消息
     * @param <T> 返回数据类型
     * @return 操作结果
     */
    public static <T> Result<T> safeExecute(Supplier<T> supplier, String errorMessage) {
        try {
            T result = supplier.get();
            return Result.success(result);
        } catch (Exception e) {
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), errorMessage + ": " + e.getMessage(), null);
        }
    }
}
