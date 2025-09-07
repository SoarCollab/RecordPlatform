package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作审计日志实体类
 * 用于记录用户的操作行为和系统事件
 * 
 * @author flying
 * @date 2024
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("audit_log")
public class AuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    /**
     * 操作用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 操作用户名
     */
    @TableField("username")
    private String username;

    /**
     * 操作类型
     * LOGIN-登录, LOGOUT-登出, CREATE-创建, UPDATE-更新, DELETE-删除, 
     * VIEW-查看, EXPORT-导出, IMPORT-导入, UPLOAD-上传, DOWNLOAD-下载
     */
    @TableField("operation_type")
    private String operationType;

    /**
     * 操作模块
     * USER-用户管理, ROLE-角色管理, PERMISSION-权限管理, OAUTH-OAuth管理,
     * RECORD-存证管理, FILE-文件管理, SYSTEM-系统管理
     */
    @TableField("module")
    private String module;

    /**
     * 操作描述
     */
    @TableField("operation_desc")
    private String operationDesc;

    /**
     * 请求方法
     */
    @TableField("request_method")
    private String requestMethod;

    /**
     * 请求URL
     */
    @TableField("request_url")
    private String requestUrl;

    /**
     * 请求参数
     */
    @TableField("request_params")
    private String requestParams;

    /**
     * 响应结果
     */
    @TableField("response_result")
    private String responseResult;

    /**
     * 操作状态
     * 0-失败, 1-成功
     */
    @TableField("operation_status")
    private Integer operationStatus;

    /**
     * 设置操作是否成功
     * 
     * @param success 是否成功
     */
    public void setIsSuccess(boolean success) {
        this.operationStatus = success ? 1 : 0;
    }

    /**
     * 获取操作是否成功
     * 
     * @return 是否成功
     */
    public boolean getIsSuccess() {
        return this.operationStatus != null && this.operationStatus == 1;
    }

    /**
     * 错误信息
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * 客户端IP地址
     */
    @TableField("client_ip")
    private String clientIp;

    /**
     * 用户代理
     */
    @TableField("user_agent")
    private String userAgent;

    /**
     * 操作耗时（毫秒）
     */
    @TableField("execution_time")
    private Long executionTime;

    /**
     * 会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 令牌ID
     */
    @TableField("token_id")
    private String tokenId;

    /**
     * 业务ID
     */
    @TableField("business_id")
    private String businessId;

    /**
     * 业务类型
     */
    @TableField("business_type")
    private String businessType;

    /**
     * 风险等级
     * LOW-低风险, MEDIUM-中风险, HIGH-高风险, CRITICAL-严重风险
     */
    @TableField("risk_level")
    private String riskLevel;

    /**
     * 地理位置
     */
    @TableField("location")
    private String location;

    /**
     * 设备信息
     */
    @TableField("device_info")
    private String deviceInfo;

    /**
     * 操作时间
     */
    @TableField(value = "operation_time", fill = FieldFill.INSERT)
    private LocalDateTime operationTime;

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
     * 操作类型枚举
     */
    public enum OperationType {
        LOGIN("LOGIN", "登录"),
        LOGOUT("LOGOUT", "登出"),
        CREATE("CREATE", "创建"),
        UPDATE("UPDATE", "更新"),
        DELETE("DELETE", "删除"),
        VIEW("VIEW", "查看"),
        EXPORT("EXPORT", "导出"),
        IMPORT("IMPORT", "导入"),
        UPLOAD("UPLOAD", "上传"),
        DOWNLOAD("DOWNLOAD", "下载"),
        AUTHORIZE("AUTHORIZE", "授权"),
        REVOKE("REVOKE", "撤销");

        private final String code;
        private final String desc;

        OperationType(String code, String desc) {
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
     * 操作模块枚举
     */
    public enum Module {
        USER("USER", "用户管理"),
        ROLE("ROLE", "角色管理"),
        PERMISSION("PERMISSION", "权限管理"),
        OAUTH("OAUTH", "OAuth管理"),
        RECORD("RECORD", "存证管理"),
        FILE("FILE", "文件管理"),
        SYSTEM("SYSTEM", "系统管理"),
        AUTH("AUTH", "认证管理");

        private final String code;
        private final String desc;

        Module(String code, String desc) {
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
     * 风险等级枚举
     */
    public enum RiskLevel {
        LOW("LOW", "低风险"),
        MEDIUM("MEDIUM", "中风险"),
        HIGH("HIGH", "高风险"),
        CRITICAL("CRITICAL", "严重风险");

        private final String code;
        private final String desc;

        RiskLevel(String code, String desc) {
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