package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 流量监控实体类
 * 用于记录网关流量数据和异常事件
 *
 * @author 王贝强
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("traffic_monitor")
public class TrafficMonitorEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 请求ID（用于链路追踪）
     */
    @TableField("request_id")
    private String requestId;

    /**
     * 客户端IP地址
     */
    @TableField("client_ip")
    private String clientIp;

    /**
     * 用户ID（可选）
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 请求路径
     */
    @TableField("request_path")
    private String requestPath;

    /**
     * HTTP方法
     */
    @TableField("request_method")
    private String requestMethod;

    /**
     * 用户代理
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 响应状态码
     */
    @TableField("response_status")
    private Integer responseStatus;

    /**
     * 响应时间（毫秒）
     */
    @TableField("response_time")
    private Long responseTime;

    /**
     * 请求大小（字节）
     */
    @TableField("request_size")
    private Long requestSize;

    /**
     * 响应大小（字节）
     */
    @TableField("response_size")
    private Long responseSize;

    /**
     * 是否异常流量
     */
    @TableField("is_abnormal")
    private Boolean isAbnormal;

    /**
     * 异常类型
     */
    @TableField("abnormal_type")
    private String abnormalType;

    /**
     * 风险评分 (0-100)
     */
    @TableField("risk_score")
    private Integer riskScore;

    /**
     * 拦截状态 (0-正常, 1-限流, 2-拦截, 3-黑名单)
     */
    @TableField("block_status")
    private Integer blockStatus;

    /**
     * 拦截原因
     */
    @TableField("block_reason")
    private String blockReason;

    /**
     * 地理位置信息
     */
    @TableField("geo_location")
    private String geoLocation;

    /**
     * 设备指纹
     */
    @TableField("device_fingerprint")
    private String deviceFingerprint;

    /**
     * 请求时间
     */
    @TableField(value = "request_time", fill = FieldFill.INSERT)
    private LocalDateTime requestTime;

    /**
     * 创建时间
     */
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 异常类型枚举
     */
    public enum AbnormalType {
        HIGH_FREQUENCY("HIGH_FREQUENCY", "高频访问"),
        SUSPICIOUS_IP("SUSPICIOUS_IP", "可疑IP"),
        UNUSUAL_PATTERN("UNUSUAL_PATTERN", "异常模式"),
        GEO_ANOMALY("GEO_ANOMALY", "地理位置异常"),
        TIME_ANOMALY("TIME_ANOMALY", "时间异常"),
        DEVICE_ANOMALY("DEVICE_ANOMALY", "设备异常"),
        BOT_DETECTED("BOT_DETECTED", "机器人检测"),
        DDOS_ATTACK("DDOS_ATTACK", "DDoS攻击");

        private final String code;
        private final String description;

        AbnormalType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * 拦截状态枚举
     */
    public enum BlockStatus {
        NORMAL(0, "正常"),
        RATE_LIMITED(1, "限流"),
        BLOCKED(2, "拦截"),
        BLACKLISTED(3, "黑名单");

        private final Integer code;
        private final String description;

        BlockStatus(Integer code, String description) {
            this.code = code;
            this.description = description;
        }

        public Integer getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }
}
