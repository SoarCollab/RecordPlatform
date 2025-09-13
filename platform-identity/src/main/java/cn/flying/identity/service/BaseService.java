package cn.flying.identity.service;

import cn.flying.identity.util.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 基础服务类
 * 为所有服务提供通用的方法和工具，减少重复代码
 * 集成了常用的工具类和方法，提供统一的编程模式
 *
 * @author 王贝强
 */
@Slf4j
public abstract class BaseService {

    @Resource
    protected CacheUtils cacheUtils;

    /**
     * 安全执行操作，统一异常处理
     *
     * @param <T>          返回值类型
     * @param operation    要执行的操作
     * @param errorMessage 错误信息
     * @return 操作结果
     */
    protected <T> Result<T> safeExecute(Supplier<Result<T>> operation, String errorMessage) {
        return WebContextUtils.safeExecute(operation, errorMessage);
    }

    /**
     * 安全执行操作（无返回值）
     *
     * @param operation    要执行的操作
     * @param errorMessage 错误信息
     * @return 操作结果
     */
    protected Result<Void> safeExecuteVoid(Supplier<Result<Void>> operation, String errorMessage) {
        return WebContextUtils.safeExecuteVoid(operation, errorMessage);
    }

    /**
     * 安全执行操作（直接返回数据）
     *
     * @param <T>          返回值类型
     * @param operation    要执行的操作
     * @param errorMessage 错误信息
     * @return 操作结果
     */
    protected <T> Result<T> safeExecuteData(Supplier<T> operation, String errorMessage) {
        return ResultUtils.safeExecute(operation, errorMessage);
    }

    /**
     * 安全执行无返回值操作
     *
     * @param operation    要执行的操作
     * @param errorMessage 错误信息
     * @return 操作结果
     */
    protected Result<Void> safeExecuteAction(Runnable operation, String errorMessage) {
        try {
            operation.run();
            return Result.success();
        } catch (Exception e) {
            logError(errorMessage + ": {}", e, e.getMessage());
            return new Result<>(ResultEnum.SYSTEM_ERROR.getCode(), errorMessage + ": " + e.getMessage(), null);
        }
    }

    // ==================== 参数验证方法 ====================

    /**
     * 记录错误日志
     *
     * @param message   日志信息
     * @param throwable 异常信息
     * @param args      参数
     */
    protected void logError(String message, Throwable throwable, Object... args) {
        if (args.length > 0) {
            log.error(String.format(message, args), throwable);
        } else {
            log.error(message, throwable);
        }
    }

    /**
     * 参数验证
     *
     * @param condition 验证条件
     * @param errorEnum 错误枚举
     * @param <T>       返回类型
     * @return 验证失败时的错误结果，成功时返回null
     */
    protected <T> Result<T> validateParam(boolean condition, ResultEnum errorEnum) {
        return WebContextUtils.validateParam(condition, errorEnum);
    }

    /**
     * 验证参数不为空
     *
     * @param <T>     参数类型
     * @param value   参数值
     * @param message 错误消息
     * @return 验证结果
     */
    protected <T> Result<T> requireNonNull(T value, String message) {
        return ValidationUtils.requireNonNull(value, message);
    }

    /**
     * 验证字符串不为空白
     *
     * @param value   字符串值
     * @param message 错误消息
     * @return 验证结果
     */
    protected Result<String> requireNonBlank(String value, String message) {
        return ValidationUtils.requireNonBlank(value, message);
    }

    /**
     * 验证集合不为空
     *
     * @param <T>        集合类型
     * @param collection 集合
     * @param message    错误消息
     * @return 验证结果
     */
    protected <T extends Collection<?>> Result<T> requireNonEmpty(T collection, String message) {
        return ValidationUtils.requireNonEmpty(collection, message);
    }

    /**
     * 验证条件为真
     *
     * @param condition 条件
     * @param message   错误消息
     * @return 验证结果
     */
    protected Result<Boolean> requireTrue(boolean condition, String message) {
        return ValidationUtils.requireTrue(condition, message);
    }

    /**
     * 验证邮箱格式
     *
     * @param email   邮箱地址
     * @param message 错误消息
     * @return 验证结果
     */
    protected Result<String> requireValidEmail(String email, String message) {
        return ValidationUtils.requireValidEmail(email, message);
    }

    /**
     * 自定义验证
     *
     * @param <T>       值类型
     * @param value     待验证的值
     * @param predicate 验证谓词
     * @param message   错误消息
     * @return 验证结果
     */
    protected <T> Result<T> requireCondition(T value, Predicate<T> predicate, String message) {
        return ValidationUtils.requireCondition(value, predicate, message);
    }

    // ==================== 工具方法 ====================

    /**
     * 批量验证
     *
     * @param validations 验证结果数组
     * @return 综合验证结果
     */
    @SafeVarargs
    protected final Result<Void> validateAll(Result<?>... validations) {
        return ValidationUtils.validateAll(validations);
    }

    /**
     * 检查字符串是否为空
     *
     * @param str 待检查的字符串
     * @return 是否为空
     */
    protected boolean isBlank(String str) {
        return CommonUtils.isBlank(str);
    }

    /**
     * 检查字符串是否不为空
     *
     * @param str 待检查的字符串
     * @return 是否不为空
     */
    protected boolean isNotBlank(String str) {
        return CommonUtils.isNotBlank(str);
    }

    /**
     * 判断对象是否为空
     *
     * @param obj 待检查的对象
     * @return 是否为空
     */
    protected boolean isEmpty(Object obj) {
        return CommonUtils.isEmpty(obj);
    }

    /**
     * 判断对象是否非空
     *
     * @param obj 待检查的对象
     * @return 是否非空
     */
    protected boolean isNotEmpty(Object obj) {
        return CommonUtils.isNotEmpty(obj);
    }

    /**
     * 获取值或默认值
     *
     * @param <T>          值类型
     * @param value        值
     * @param defaultValue 默认值
     * @return 值或默认值
     */
    protected <T> T getOrElse(T value, T defaultValue) {
        return CommonUtils.getOrElse(value, defaultValue);
    }

    /**
     * 生成随机数字验证码
     *
     * @param length 长度
     * @return 验证码
     */
    protected String generateVerifyCode(int length) {
        return CommonUtils.genRandomNumbers(length);
    }

    /**
     * 格式化日期时间
     *
     * @param dateTime 日期时间
     * @param pattern  格式模式
     * @return 格式化后的字符串
     */
    protected String formatDateTime(LocalDateTime dateTime, String pattern) {
        return CommonUtils.formatDateTime(dateTime, pattern);
    }

    // ==================== JSON 工具方法 ====================

    /**
     * 获取当前客户端IP
     *
     * @return 客户端IP地址
     */
    protected String getCurrentClientIp() {
        return WebContextUtils.getCurrentClientIp();
    }

    /**
     * 对象转JSON字符串
     *
     * @param object 对象
     * @return JSON字符串
     */
    protected String toJson(Object object) {
        return JsonUtils.toJson(object);
    }

    /**
     * JSON字符串转对象
     *
     * @param <T>   对象类型
     * @param json  JSON字符串
     * @param clazz 目标类型
     * @return 对象
     */
    protected <T> T fromJson(String json, Class<T> clazz) {
        return JsonUtils.fromJson(json, clazz);
    }

    // ==================== Result 工具方法 ====================

    /**
     * 判断字符串是否为有效JSON
     *
     * @param json JSON字符串
     * @return 是否有效
     */
    protected boolean isValidJson(String json) {
        return JsonUtils.isValidJson(json);
    }

    /**
     * 生成成功结果
     *
     * @param <T>  数据类型
     * @param data 返回数据
     * @return 成功结果
     */
    protected <T> Result<T> success(T data) {
        return ResultUtils.success(data);
    }

    /**
     * 创建成功结果（无数据）
     *
     * @param <T> 数据类型
     * @return 成功结果
     */
    protected <T> Result<T> success() {
        return ResultUtils.success();
    }

    /**
     * 创建错误结果
     *
     * @param <T>     数据类型
     * @param message 错误消息
     * @return 错误结果
     */
    protected <T> Result<T> error(String message) {
        return ResultUtils.error(message);
    }

    /**
     * 生成错误结果
     *
     * @param <T>       数据类型
     * @param errorEnum 错误枚举
     * @return 错误结果
     */
    protected <T> Result<T> error(ResultEnum errorEnum) {
        return ResultUtils.error(errorEnum);
    }

    /**
     * 判断结果是否成功
     *
     * @param result 结果
     * @return 是否成功
     */
    protected boolean isSuccess(Result<?> result) {
        return ResultUtils.isSuccess(result);
    }

    /**
     * 从Result中获取数据或默认值
     *
     * @param <T>          数据类型
     * @param result       结果
     * @param defaultValue 默认值
     * @return 数据或默认值
     */
    protected <T> T getDataOrDefault(Result<T> result, T defaultValue) {
        return ResultUtils.getDataOrDefault(result, defaultValue);
    }

    /**
     * 转换Result中的数据类型
     *
     * @param <T>    原始数据类型
     * @param <R>    新数据类型
     * @param result 原始结果
     * @param mapper 转换函数
     * @return 转换后的结果
     */
    protected <T, R> Result<R> mapResult(Result<T> result, Function<T, R> mapper) {
        return ResultUtils.map(result, mapper);
    }

    // ==================== 缓存工具方法 ====================

    /**
     * 链式处理Result
     *
     * @param <T>    原始数据类型
     * @param <R>    新数据类型
     * @param result 原始结果
     * @param mapper 链式操作函数
     * @return 处理后的结果
     */
    protected <T, R> Result<R> flatMapResult(Result<T> result, Function<T, Result<R>> mapper) {
        return ResultUtils.flatMap(result, mapper);
    }

    /**
     * 从缓存获取数据，如果不存在则从数据源获取并缓存
     *
     * @param key          缓存键
     * @param dataSupplier 数据提供者
     * @param timeout      过期时间（秒）
     * @return 数据
     */
    protected String getFromCacheOrLoad(String key, Supplier<String> dataSupplier, long timeout) {
        return cacheUtils.getOrSet(key, dataSupplier, timeout, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 设置缓存
     *
     * @param key     缓存键
     * @param value   缓存值
     * @param timeout 过期时间（秒）
     */
    protected void setCache(String key, String value, long timeout) {
        cacheUtils.set(key, value, timeout, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * 删除缓存
     *
     * @param key 缓存键
     */
    protected void deleteCache(String key) {
        cacheUtils.delete(key);
    }

    // ==================== 日志方法 ====================

    /**
     * 检查缓存是否存在
     *
     * @param key 缓存键
     * @return 是否存在
     */
    protected boolean existsCache(String key) {
        return cacheUtils.exists(key);
    }

    /**
     * 记录调试日志
     *
     * @param message 日志信息
     * @param args    参数
     */
    protected void logDebug(String message, Object... args) {
        if (log.isDebugEnabled()) {
            log.debug(message, args);
        }
    }

    /**
     * 记录信息日志
     *
     * @param message 日志信息
     * @param args    参数
     */
    protected void logInfo(String message, Object... args) {
        log.info(message, args);
    }

    /**
     * 记录警告日志
     *
     * @param message 日志信息
     * @param args    参数
     */
    protected void logWarn(String message, Object... args) {
        log.warn(message, args);
    }

    /**
     * 记录错误日志（仅消息）
     *
     * @param message 日志信息
     * @param args    参数
     */
    protected void logError(String message, Object... args) {
        log.error(message, args);
    }
}
