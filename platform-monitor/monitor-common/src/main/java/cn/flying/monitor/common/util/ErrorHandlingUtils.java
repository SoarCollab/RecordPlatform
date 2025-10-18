package cn.flying.monitor.common.util;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 错误处理工具类
 * 统一处理常见的错误处理模式，减少重复代码
 */
@Slf4j
public class ErrorHandlingUtils {

    /**
     * 执行操作并处理异常，返回结果或默认值
     */
    public static <T> T executeWithErrorHandling(Supplier<T> operation, T defaultValue, String operationName) {
        try {
            return operation.get();
        } catch (Exception e) {
            log.error("{}异常", operationName, e);
            return defaultValue;
        }
    }

    /**
     * 执行操作并处理异常，在Map中添加错误信息
     */
    public static void executeWithErrorHandling(Runnable operation, Map<String, Object> resultMap, String operationName) {
        try {
            operation.run();
        } catch (Exception e) {
            log.error("{}异常", operationName, e);
            resultMap.put("error", e.getMessage());
        }
    }

    /**
     * 执行操作并处理异常，返回是否成功
     */
    public static boolean executeWithErrorHandling(Runnable operation, String operationName) {
        try {
            operation.run();
            return true;
        } catch (Exception e) {
            log.error("{}异常", operationName, e);
            return false;
        }
    }

    /**
     * 执行操作并处理异常，支持自定义错误处理
     */
    public static <T> T executeWithErrorHandling(Supplier<T> operation, T defaultValue, 
                                               String operationName, Runnable errorHandler) {
        try {
            return operation.get();
        } catch (Exception e) {
            log.error("{}异常", operationName, e);
            if (errorHandler != null) {
                errorHandler.run();
            }
            return defaultValue;
        }
    }

    /**
     * 记录业务指标计算异常的标准格式
     */
    public static void logMetricsCalculationError(String metricType, String identifier, Exception e) {
        log.error("计算{} {} 的指标异常", metricType, identifier, e);
    }

    /**
     * 记录健康检查异常的标准格式
     */
    public static void logHealthCheckError(String componentName, Exception e) {
        log.error("{}健康检查异常", componentName, e);
    }

    /**
     * 记录WebSocket异常的标准格式
     */
    public static void logWebSocketError(String operation, String sessionId, Exception e) {
        log.error("WebSocket{}异常，会话ID: {}", operation, sessionId, e);
    }

    /**
     * 记录追踪异常的标准格式
     */
    public static void logTracingError(String operation, String traceId, Exception e) {
        log.error("{}追踪异常，TraceId: {}", operation, traceId, e);
    }
}