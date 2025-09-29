package cn.flying.identity.util;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

/**
 * 安全日志工具类
 * 防止日志注入攻击，确保敏感信息不被记录
 */
@Slf4j
public class SecureLogger {

    // 敏感字段名称列表
    private static final String[] SENSITIVE_FIELDS = {
            "password", "secret", "token", "key", "authorization",
            "credit_card", "ssn", "api_key", "private_key"
    };

    /**
     * 记录安全的调试日志
     *
     * @param message 日志消息
     * @param params  参数
     */
    public static void debug(String message, Object... params) {
        if (log.isDebugEnabled()) {
            log.debug(sanitizeMessage(message), sanitizeParams(params));
        }
    }

    /**
     * 记录安全的信息日志
     *
     * @param message 日志消息
     * @param params  参数
     */
    public static void info(String message, Object... params) {
        log.info(sanitizeMessage(message), sanitizeParams(params));
    }

    /**
     * 记录安全的警告日志
     *
     * @param message 日志消息
     * @param params  参数
     */
    public static void warn(String message, Object... params) {
        log.warn(sanitizeMessage(message), sanitizeParams(params));
    }

    /**
     * 记录安全的错误日志
     *
     * @param message 日志消息
     * @param error   异常
     */
    public static void error(String message, Throwable error) {
        log.error(sanitizeMessage(message), sanitizeException(error));
    }

    /**
     * 记录安全的错误日志（带参数）
     *
     * @param message 日志消息
     * @param params  参数
     */
    public static void error(String message, Object... params) {
        log.error(sanitizeMessage(message), sanitizeParams(params));
    }

    /**
     * 清理日志消息，防止日志注入
     *
     * @param message 原始消息
     * @return 清理后的消息
     */
    private static String sanitizeMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return "";
        }

        // 移除换行符和回车符，防止日志注入
        String cleaned = message.replaceAll("[\r\n]", "_");

        // 限制长度
        if (cleaned.length() > 1000) {
            cleaned = cleaned.substring(0, 1000) + "...(truncated)";
        }

        return cleaned;
    }

    /**
     * 清理日志参数
     *
     * @param params 参数数组
     * @return 清理后的参数
     */
    private static Object[] sanitizeParams(Object[] params) {
        if (params == null || params.length == 0) {
            return new Object[0];
        }

        Object[] sanitized = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            sanitized[i] = sanitizeParam(params[i]);
        }
        return sanitized;
    }

    /**
     * 清理单个参数
     *
     * @param param 参数
     * @return 清理后的参数
     */
    private static Object sanitizeParam(Object param) {
        if (param == null) {
            return "null";
        }

        String str = param.toString();

        // 检查是否包含敏感信息
        String lowerStr = str.toLowerCase();
        for (String sensitive : SENSITIVE_FIELDS) {
            if (lowerStr.contains(sensitive)) {
                // 如果包含敏感字段，返回脱敏信息
                return maskSensitiveData(str);
            }
        }

        // 移除控制字符
        str = str.replaceAll("[\r\n\t]", " ");

        // 限制长度
        if (str.length() > 200) {
            str = str.substring(0, 200) + "...";
        }

        return str;
    }

    /**
     * 脱敏敏感数据
     *
     * @param data 原始数据
     * @return 脱敏后的数据
     */
    private static String maskSensitiveData(String data) {
        if (StrUtil.isBlank(data)) {
            return "";
        }

        int length = data.length();
        if (length <= 4) {
            return "****";
        } else if (length <= 8) {
            return data.substring(0, 2) + "****";
        } else {
            // 只显示前3位和后3位
            return data.substring(0, 3) + "***" + data.substring(length - 3);
        }
    }

    /**
     * 清理异常信息
     *
     * @param error 异常
     * @return 清理后的异常
     */
    private static Throwable sanitizeException(Throwable error) {
        if (error == null) {
            return new RuntimeException("Unknown error");
        }

        // 创建一个新的异常，只包含必要信息
        RuntimeException sanitized = new RuntimeException(
                sanitizeMessage(error.getMessage()),
                error.getCause() != null ? sanitizeException(error.getCause()) : null
        );

        // 设置堆栈跟踪（限制深度）
        StackTraceElement[] originalStack = error.getStackTrace();
        if (originalStack != null && originalStack.length > 0) {
            int maxDepth = Math.min(originalStack.length, 10);
            StackTraceElement[] limitedStack = new StackTraceElement[maxDepth];
            System.arraycopy(originalStack, 0, limitedStack, 0, maxDepth);
            sanitized.setStackTrace(limitedStack);
        }

        return sanitized;
    }

    /**
     * 记录认证失败事件（安全审计）
     *
     * @param username  用户名
     * @param ipAddress IP地址
     * @param reason    失败原因
     */
    public static void logAuthenticationFailure(String username, String ipAddress, String reason) {
        warn("Authentication failed - User: {}, IP: {}, Reason: {}",
                sanitizeParam(username),
                sanitizeParam(ipAddress),
                sanitizeParam(reason));
    }

    /**
     * 记录认证成功事件（安全审计）
     *
     * @param username  用户名
     * @param ipAddress IP地址
     */
    public static void logAuthenticationSuccess(String username, String ipAddress) {
        info("Authentication successful - User: {}, IP: {}",
                sanitizeParam(username),
                sanitizeParam(ipAddress));
    }

    /**
     * 记录可疑活动
     *
     * @param activity    活动描述
     * @param ipAddress   IP地址
     * @param userAgent   User-Agent
     */
    public static void logSuspiciousActivity(String activity, String ipAddress, String userAgent) {
        warn("Suspicious activity detected - Activity: {}, IP: {}, UserAgent: {}",
                sanitizeParam(activity),
                sanitizeParam(ipAddress),
                sanitizeParam(userAgent));
    }

    /**
     * 记录访问拒绝事件
     *
     * @param resource   资源
     * @param username   用户名
     * @param reason     原因
     */
    public static void logAccessDenied(String resource, String username, String reason) {
        warn("Access denied - Resource: {}, User: {}, Reason: {}",
                sanitizeParam(resource),
                sanitizeParam(username),
                sanitizeParam(reason));
    }
}