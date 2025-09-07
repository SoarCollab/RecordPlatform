package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Token监控实体类
 * 用于记录Token的使用情况和监控信息
 * 
 * @author flying
 * @date 2024
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("token_monitor")
public class TokenMonitor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * Token ID
     */
    @TableField("token_id")
    private String tokenId;

    /**
     * Token类型
     */
    @TableField("token_type")
    private String tokenType;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 用户名
     */
    @TableField("username")
    private String username;

    /**
     * 客户端ID
     */
    @TableField("client_id")
    private String clientId;

    /**
     * 事件类型
     */
    @TableField("event_type")
    private String eventType;

    /**
     * 事件描述
     */
    @TableField("event_desc")
    private String eventDesc;

    /**
     * 客户端IP
     */
    @TableField("client_ip")
    private String clientIp;

    /**
     * 用户代理
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 请求URL
     */
    @TableField("request_url")
    private String requestUrl;

    /**
     * 请求方法
     */
    @TableField("request_method")
    private String requestMethod;

    /**
     * 地理位置
     */
    @TableField("location")
    private String location;

    /**
     * 设备指纹
     */
    @TableField("device_fingerprint")
    private String deviceFingerprint;

    /**
     * 风险评分
     * 0-100，分数越高风险越大
     */
    @TableField("risk_score")
    private Integer riskScore;

    /**
     * 风险原因
     */
    @TableField("risk_reason")
    private String riskReason;

    /**
     * 是否异常
     */
    @TableField("is_abnormal")
    private Boolean isAbnormal;

    /**
     * 异常类型
     */
    @TableField("abnormal_type")
    private String abnormalType;

    /**
     * 处理状态
     */
    @TableField("handle_status")
    private String handleStatus;

    /**
     * 处理结果
     */
    @TableField("handle_result")
    private String handleResult;

    /**
     * 处理备注
     */
    @TableField("handle_remark")
    private String handleRemark;

    /**
     * 处理人ID
     */
    @TableField("handler_id")
    private Long handlerId;

    /**
     * 处理时间
     */
    @TableField("handle_time")
    private LocalDateTime handleTime;

    /**
     * 令牌创建时间
     */
    @TableField("token_create_time")
    private LocalDateTime tokenCreateTime;

    /**
     * 令牌过期时间
     */
    @TableField("token_expire_time")
    private LocalDateTime tokenExpireTime;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 额外信息（JSON格式）
     */
    @TableField("extra_info")
    private String extraInfo;

    /**
     * 事件时间
     */
    @TableField(value = "event_time", fill = FieldFill.INSERT)
    private LocalDateTime eventTime;

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
     * 令牌类型枚举
     */
    public enum TokenType {
        ACCESS_TOKEN("ACCESS_TOKEN", "访问令牌"),
        REFRESH_TOKEN("REFRESH_TOKEN", "刷新令牌"),
        CLIENT_TOKEN("CLIENT_TOKEN", "客户端令牌"),
        AUTH_CODE("AUTH_CODE", "授权码");

        private final String code;
        private final String desc;

        TokenType(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public String getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }
    }

    /**
     * 事件类型枚举
     */
    public enum EventType {
        CREATE("CREATE", "创建"),
        USE("USE", "使用"),
        REFRESH("REFRESH", "刷新"),
        REVOKE("REVOKE", "撤销"),
        EXPIRE("EXPIRE", "过期"),
        INVALID("INVALID", "无效"),
        SUSPICIOUS("SUSPICIOUS", "可疑"),
        ABNORMAL("ABNORMAL", "异常");

        private final String code;
        private final String desc;

        EventType(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public String getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }
    }

    /**
     * 异常类型枚举
     */
    public enum AnomalyType {
        IP_CHANGE("IP_CHANGE", "IP变更"),
        LOCATION_CHANGE("LOCATION_CHANGE", "地理位置变更"),
        DEVICE_CHANGE("DEVICE_CHANGE", "设备变更"),
        FREQUENCY_ANOMALY("FREQUENCY_ANOMALY", "频率异常"),
        TIME_ANOMALY("TIME_ANOMALY", "时间异常"),
        CONCURRENT_LOGIN("CONCURRENT_LOGIN", "并发登录"),
        BRUTE_FORCE("BRUTE_FORCE", "暴力破解"),
        TOKEN_REUSE("TOKEN_REUSE", "令牌重用");

        private final String code;
        private final String desc;

        AnomalyType(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public String getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }
    }

    /**
     * 处理状态枚举
     */
    public enum ProcessStatus {
        PENDING("PENDING", "待处理"),
        PROCESSING("PROCESSING", "处理中"),
        PROCESSED("PROCESSED", "已处理"),
        IGNORED("IGNORED", "已忽略");

        private final String code;
        private final String desc;

        ProcessStatus(String code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        public String getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }
    }
}