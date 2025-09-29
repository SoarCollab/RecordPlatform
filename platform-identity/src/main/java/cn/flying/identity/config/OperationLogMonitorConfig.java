package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 操作日志监控配置类
 * 提供操作日志开关、异步开关、保留天数、参数/结果记录与敏感操作配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "operation.log")
public class OperationLogMonitorConfig {

    /**
     * 操作日志配置
     */
    private OperationLogConfig operationLog = new OperationLogConfig();

    /**
     * 操作日志配置项
     */
    @Data
    public static class OperationLogConfig {

        /**
         * 是否启用操作日志
         */
        private boolean enabled = true;

        /**
         * 是否启用异步记录
         */
        private boolean asyncEnabled = true;

        /**
         * 数据保留天数
         */
        private int retentionDays = 90;

        /**
         * 是否记录请求参数
         */
        private boolean logRequestParams = true;

        /**
         * 是否记录响应结果
         */
        private boolean logResponseResult = false;

        /**
         * 敏感操作列表
         */
        private String[] sensitiveOperations = {
                "DELETE", "UPDATE", "CREATE", "EXPORT"
        };

        /**
         * 高风险模块列表（预留）
         */
        private String[] highRiskModules = {
                "USER", "ROLE", "PERMISSION", "OAUTH", "SYSTEM"
        };
    }

    /**
     * 常量定义
     */
    public static final class Constants {

        // 操作类型
        public static final String OPERATION_LOGIN = "LOGIN";
        public static final String OPERATION_LOGOUT = "LOGOUT";
        public static final String OPERATION_CREATE = "CREATE";
        public static final String OPERATION_UPDATE = "UPDATE";
        public static final String OPERATION_DELETE = "DELETE";
        public static final String OPERATION_VIEW = "VIEW";
        public static final String OPERATION_EXPORT = "EXPORT";
        public static final String OPERATION_IMPORT = "IMPORT";

        // 模块
        public static final String MODULE_AUTH = "AUTH";
        public static final String MODULE_USER = "USER";
        public static final String MODULE_ROLE = "ROLE";
        public static final String MODULE_PERMISSION = "PERMISSION";
        public static final String MODULE_OAUTH = "OAUTH";
        public static final String MODULE_RECORD = "RECORD";
        public static final String MODULE_FILE = "FILE";
        public static final String MODULE_SYSTEM = "SYSTEM";

        // 风险等级
        public static final String RISK_LEVEL_LOW = "LOW";
        public static final String RISK_LEVEL_MEDIUM = "MEDIUM";
        public static final String RISK_LEVEL_HIGH = "HIGH";
        public static final String RISK_LEVEL_CRITICAL = "CRITICAL";

        // Token事件类型
        public static final String TOKEN_EVENT_CREATED = "CREATED";
        public static final String TOKEN_EVENT_USED = "USED";
        public static final String TOKEN_EVENT_REFRESHED = "REFRESHED";
        public static final String TOKEN_EVENT_REVOKED = "REVOKED";
        public static final String TOKEN_EVENT_EXPIRED = "EXPIRED";
        public static final String TOKEN_EVENT_ABNORMAL = "ABNORMAL";

        // Token类型
        public static final String TOKEN_TYPE_ACCESS = "ACCESS_TOKEN";
        public static final String TOKEN_TYPE_REFRESH = "REFRESH_TOKEN";
        public static final String TOKEN_TYPE_AUTHORIZATION_CODE = "AUTHORIZATION_CODE";
        public static final String TOKEN_TYPE_CLIENT = "CLIENT_TOKEN";

        // 异常类型
        public static final String ABNORMAL_TYPE_SUSPICIOUS_IP = "SUSPICIOUS_IP";
        public static final String ABNORMAL_TYPE_UNUSUAL_LOCATION = "UNUSUAL_LOCATION";
        public static final String ABNORMAL_TYPE_HIGH_FREQUENCY = "HIGH_FREQUENCY";
        public static final String ABNORMAL_TYPE_CONCURRENT_USAGE = "CONCURRENT_USAGE";
        public static final String ABNORMAL_TYPE_DEVICE_MISMATCH = "DEVICE_MISMATCH";
        public static final String ABNORMAL_TYPE_TIME_ANOMALY = "TIME_ANOMALY";
    }
}