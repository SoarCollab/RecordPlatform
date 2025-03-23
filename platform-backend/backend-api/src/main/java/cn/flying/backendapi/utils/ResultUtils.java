package cn.flying.backendapi.utils;


import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;

/**
 * Result 结果处理工具类
 * 用于处理 Result 类型的返回值判断和错误传递
 */
public class ResultUtils {

    /**
     * 判断结果是否成功
     * @param result Result 对象
     * @return 是否成功
     */
    public static boolean isSuccess(Result<?> result) {
        return result != null && result.getCode() == ResultEnum.SUCCESS.getCode();
    }
    
    /**
     * 从 Result 中获取数据，如果成功返回数据，失败则抛出异常
     * @param result Result 对象
     * @param <T> 数据类型
     * @return 数据
     * @throws GeneralException 当结果不成功时抛出异常
     */
    public static <T> T getData(Result<T> result) {
        if (isSuccess(result)) {
            return result.getData();
        }
        throw new GeneralException(result.getMessage());
    }
    
    /**
     * 从 Result 中获取数据，如果成功返回数据，失败则返回默认值
     * @param result Result 对象
     * @param defaultValue 默认值
     * @param <T> 数据类型
     * @return 成功时返回数据，失败时返回默认值
     */
    public static <T> T getDataOrDefault(Result<T> result, T defaultValue) {
        return isSuccess(result) ? result.getData() : defaultValue;
    }
    
    /**
     * 处理结果：成功返回数据，失败则抛出包含错误信息的异常
     * @param result Result 对象
     * @param <T> 数据类型
     * @return 数据
     * @throws GeneralException 当结果不成功时抛出异常
     */
    public static <T> T handleResult(Result<T> result) {
        if (isSuccess(result)) {
            return result.getData();
        }
        throw new GeneralException("操作失败: " + result.getMessage() + ", 错误码: " + result.getCode());
    }
    
    /**
     * 处理结果：成功不做处理，失败则抛出包含错误信息的异常
     * @param result Result 对象
     * @throws GeneralException 当结果不成功时抛出异常
     */
    public static void checkResult(Result<?> result) {
        if (!isSuccess(result)) {
            throw new GeneralException("操作失败: " + result.getMessage() + ", 错误码: " + result.getCode());
        }
    }
    
    /**
     * 从 Result 中提取错误信息，并生成新的 Result
     * @param result 原始 Result 对象
     * @param <T> 原始数据类型
     * @param <R> 新数据类型
     * @return 如果原始结果成功则返回 null，否则返回包含相同错误信息的新 Result
     */
    public static <T, R> cn.flying.common.constant.Result<R> extractError(Result<T> result) {
        if (isSuccess(result)) {
            return null;
        }
        return new cn.flying.common.constant.Result<>(result.getCode(), result.getMessage(), null);
    }
    
    /**
     * 转换 Result 中的数据类型，保留成功/失败状态和消息
     * @param result 原始 Result 对象
     * @param data 新数据
     * @param <T> 原始数据类型
     * @param <R> 新数据类型
     * @return 包含新数据但保留原始状态和消息的 Result
     */
    public static <T, R> cn.flying.common.constant.Result<R> transform(Result<T> result, R data) {
        return new cn.flying.common.constant.Result<>(result.getCode(), result.getMessage(), data);
    }
    
    /**
     * 处理嵌套的 Result 对象
     * @param result 嵌套的 Result 对象
     * @param <T> 数据类型
     * @return 解除嵌套后的 Result 对象
     */
    public static <T> cn.flying.common.constant.Result<T> unwrap(Result<Result<T>> result) {
        if (!isSuccess(result)) {
            return new cn.flying.common.constant.Result<>(result.getCode(), result.getMessage(), null);
        }
        Result<T> result_old = result.getData();
        return new cn.flying.common.constant.Result<>(result_old.getCode(), result_old.getMessage(), result_old.getData());
    }
} 