package cn.flying.identity.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 流量监控配置类
 * 用于配置网关流量监控和异常检测的相关参数
 *
 * @author 王贝强
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "gateway.traffic")
public class TrafficMonitorConfig {

    /**
     * 流量监控配置
     */
    private MonitorConfig monitor = new MonitorConfig();

    /**
     * 限流配置
     */
    private RateLimitConfig rateLimit = new RateLimitConfig();

    /**
     * 异常检测配置
     */
    private AnomalyDetectionConfig anomalyDetection = new AnomalyDetectionConfig();

    /**
     * 拦截配置
     */
    private BlockingConfig blocking = new BlockingConfig();

    /**
     * 监控配置
     */
    @Data
    public static class MonitorConfig {
        /**
         * 是否启用流量监控
         */
        private boolean enabled = true;

        /**
         * 是否启用异步记录
         */
        private boolean asyncEnabled = true;

        /**
         * 监控数据保留天数
         */
        private int retentionDays = 7;

        /**
         * 统计时间窗口（秒）
         */
        private int timeWindow = 60;

        /**
         * 采样率（0.0-1.0）
         */
        private double samplingRate = 1.0;
    }

    /**
     * 限流配置
     */
    @Data
    public static class RateLimitConfig {
        /**
         * 是否启用限流
         */
        private boolean enabled = true;

        /**
         * 每分钟最大请求数（全局）
         */
        private int globalRequestsPerMinute = 10000;

        /**
         * 每分钟最大请求数（单IP）
         */
        private int ipRequestsPerMinute = 300;

        /**
         * 每分钟最大请求数（单用户）
         */
        private int userRequestsPerMinute = 600;

        /**
         * 每分钟最大请求数（单API）
         */
        private int apiRequestsPerMinute = 1000;

        /**
         * 最大并发连接数（单IP）
         */
        private int maxConcurrentConnectionsPerIp = 50;

        /**
         * 限流后的阻塞时间（秒）
         */
        private int blockDuration = 300;
    }

    /**
     * 异常检测配置
     */
    @Data
    public static class AnomalyDetectionConfig {
        /**
         * 是否启用异常检测
         */
        private boolean enabled = true;

        /**
         * 响应时间异常阈值（毫秒）
         */
        private long responseTimeThreshold = 5000;

        /**
         * 错误率异常阈值（百分比）
         */
        private double errorRateThreshold = 10.0;

        /**
         * 地理位置检测
         */
        private boolean geoAnomalyEnabled = true;

        /**
         * 时间异常检测
         */
        private boolean timeAnomalyEnabled = true;

        /**
         * 设备指纹检测
         */
        private boolean deviceAnomalyEnabled = true;

        /**
         * 机器人检测
         */
        private boolean botDetectionEnabled = true;

        /**
         * DDoS检测阈值（每秒请求数）
         */
        private int ddosThreshold = 100;

        /**
         * 风险评分阈值
         */
        private int riskScoreThreshold = 70;
    }

    /**
     * 拦截配置
     */
    @Data
    public static class BlockingConfig {
        /**
         * 是否启用自动拦截
         */
        private boolean autoBlockEnabled = true;

        /**
         * 黑名单过期时间（小时）
         */
        private int blacklistExpireHours = 24;

        /**
         * 渐进式拦截启用
         */
        private boolean progressiveBlockEnabled = true;

        /**
         * 白名单IP列表
         */
        private String[] whitelistIps = {};

        /**
         * 永久黑名单IP列表
         */
        private String[] permanentBlacklistIps = {};

        /**
         * 拦截响应码
         */
        private int blockResponseCode = 429;

        /**
         * 拦截响应消息
         */
        private String blockResponseMessage = "Request blocked due to suspicious activity";
    }

    // 常量定义
    public static final class Constants {
        // Redis键前缀
        public static final String REDIS_PREFIX = "gateway:traffic:";
        public static final String RATE_LIMIT_PREFIX = REDIS_PREFIX + "rate_limit:";
        public static final String BLACKLIST_PREFIX = REDIS_PREFIX + "blacklist:";
        public static final String STATS_PREFIX = REDIS_PREFIX + "stats:";
        public static final String ANOMALY_PREFIX = REDIS_PREFIX + "anomaly:";

        // 监控指标类型
        public static final String METRIC_REQUEST_COUNT = "request_count";
        public static final String METRIC_RESPONSE_TIME = "response_time";
        public static final String METRIC_ERROR_RATE = "error_rate";
        public static final String METRIC_CONCURRENT_CONNECTIONS = "concurrent_connections";

        // 异常类型
        public static final String ANOMALY_HIGH_FREQUENCY = "HIGH_FREQUENCY";
        public static final String ANOMALY_SUSPICIOUS_IP = "SUSPICIOUS_IP";
        public static final String ANOMALY_GEO_LOCATION = "GEO_ANOMALY";
        public static final String ANOMALY_TIME_PATTERN = "TIME_ANOMALY";
        public static final String ANOMALY_DEVICE_FINGERPRINT = "DEVICE_ANOMALY";
        public static final String ANOMALY_BOT_DETECTED = "BOT_DETECTED";
        public static final String ANOMALY_DDOS_ATTACK = "DDOS_ATTACK";

        // 拦截级别
        public static final int BLOCK_LEVEL_NONE = 0;
        public static final int BLOCK_LEVEL_RATE_LIMIT = 1;
        public static final int BLOCK_LEVEL_TEMPORARY_BLOCK = 2;
        public static final int BLOCK_LEVEL_BLACKLIST = 3;
        public static final int BLOCK_LEVEL_PERMANENT_BAN = 4;
    }
}
