package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 操作日志监控配置类
 * 整合了原审计监控功能，用于配置操作日志和Token监控的相关参数
 *
 * @author 王贝强
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
     * 告警配置
     */
    private AlertConfig alert = new AlertConfig();

    /**
     * 操作日志配置
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
         * 高风险模块列表
         */
        private String[] highRiskModules = {
                "USER", "ROLE", "PERMISSION", "OAUTH", "SYSTEM"
        };

        /**
         * 异常检测配置
         */
        private AbnormalDetectionConfig abnormalDetection = new AbnormalDetectionConfig();
    }


    /**
     * 告警配置
     */
    @Data
    public static class AlertConfig {

        /**
         * 是否启用告警
         */
        private boolean enabled = true;

        /**
         * 检查间隔（秒）
         */
        private int checkInterval = 60;

        /**
         * 高风险阈值
         */
        private int highRiskThreshold = 80;

        /**
         * 异常事件阈值
         */
        private int abnormalEventThreshold = 5;

        /**
         * 通知方式
         */
        private String[] notificationMethods = {"EMAIL", "SMS"};

        /**
         * 告警邮箱
         */
        private String[] alertEmails = {"admin@example.com"};
    }

    /**
     * 异常检测配置
     */
    @Data
    public static class AbnormalDetectionConfig {

        /**
         * 是否启用异常检测
         */
        private boolean enabled = true;

        /**
         * 时间窗口（分钟）
         */
        private int timeWindow = 60;

        /**
         * 每个IP最大失败次数
         */
        private int maxFailuresPerIp = 10;

        /**
         * 每个用户最大操作次数
         */
        private int maxOperationsPerUser = 100;

        /**
         * 是否启用地理位置检测
         */
        private boolean locationDetectionEnabled = true;

        /**
         * 是否启用时间检测
         */
        private boolean timeDetectionEnabled = true;

        /**
         * 工作时间
         */
        private String workingHours = "09:00-18:00";
    }


    // 常量定义
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

        // 处理状态
        public static final String PROCESS_STATUS_PENDING = "PENDING";
        public static final String PROCESS_STATUS_PROCESSING = "PROCESSING";
        public static final String PROCESS_STATUS_PROCESSED = "PROCESSED";
        public static final String PROCESS_STATUS_IGNORED = "IGNORED";
    }
}