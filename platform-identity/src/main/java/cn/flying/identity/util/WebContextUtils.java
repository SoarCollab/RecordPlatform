package cn.flying.identity.util;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.Supplier;

/**
 * Web上下文工具类
 * 提供统一的Web上下文操作工具方法，减少重复代码
 * 
 * @author 王贝强
 */
@Slf4j
public class WebContextUtils {
    
    /**
     * 获取当前请求的客户端IP地址
     * 统一处理IP地址获取，避免在多个地方重复相同的逻辑
     * 
     * @return 客户端IP地址，获取失败时返回默认值"127.0.0.1"
     */
    public static String getCurrentClientIp() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletRequest request = attributes.getRequest();
                return IpUtils.getClientIp(request);
            }
        } catch (Exception e) {
            log.warn("获取客户端IP失败", e);
        }
        return "127.0.0.1";
    }
    
    /**
     * 获取当前HTTP请求对象
     * 
     * @return HttpServletRequest对象，获取失败时返回null
     */
    public static HttpServletRequest getCurrentRequest() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                return attributes.getRequest();
            }
        } catch (Exception e) {
            log.warn("获取当前请求对象失败", e);
        }
        return null;
    }
    
    /**
     * 安全执行服务操作，统一处理异常和结果返回
     * 用于减少重复的try-catch块和异常处理代码
     * 
     * @param <T> 返回值类型
     * @param operation 要执行的操作
     * @param errorMessage 出现异常时的错误信息
     * @return 操作结果
     */
    public static <T> Result<T> safeExecute(Supplier<Result<T>> operation, String errorMessage) {
        try {
            return operation.get();
        } catch (Exception e) {
            log.error(errorMessage, e);
            return Result.error(ResultEnum.SYSTEM_ERROR, null);
        }
    }
    
    /**
     * 安全执行服务操作（无返回值）
     * 
     * @param operation 要执行的操作
     * @param errorMessage 出现异常时的错误信息
     * @return 操作结果
     */
    public static Result<Void> safeExecuteVoid(Supplier<Result<Void>> operation, String errorMessage) {
        return safeExecute(operation, errorMessage);
    }
    
    /**
     * 安全执行操作，返回对象结果
     * 
     * @param operation 要执行的操作
     * @param errorMessage 出现异常时的错误信息
     * @return 操作结果
     */
    public static Result<Object> safeExecuteObject(Supplier<Result<Object>> operation, String errorMessage) {
        return safeExecute(operation, errorMessage);
    }
    
    /**
     * 参数验证工具方法
     * 
     * @param condition 验证条件
     * @param errorEnum 错误枚举
     * @param <T> 返回类型
     * @return 验证失败时的结果
     */
    public static <T> Result<T> validateParam(boolean condition, ResultEnum errorEnum) {
        if (!condition) {
            return Result.error(errorEnum, null);
        }
        return null; // 验证通过，返回null表示无错误
    }
    
    /**
     * 检查字符串是否为空
     * 
     * @param str 待检查的字符串
     * @return 是否为空
     */
    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }
    
    /**
     * 检查字符串是否不为空
     * 
     * @param str 待检查的字符串
     * @return 是否不为空
     */
    public static boolean isNotBlank(String str) {
        return !isBlank(str);
    }
}
