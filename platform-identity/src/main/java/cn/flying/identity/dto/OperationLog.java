package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志实体类
 * 整合了原 AuditLog 的功能，用于记录用户的操作行为和系统事件
 * 支持风险评估、地理位置、设备信息等高级功能
 *
 * @author 王贝强
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("operation_log")
public class OperationLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

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
     * 用户角色
     */
    @TableField("user_role")
    private String userRole;

    /**
     * 操作模块
     * USER-用户管理, ROLE-角色管理, PERMISSION-权限管理, OAUTH-OAuth管理,
     * RECORD-存证管理, FILE-文件管理, SYSTEM-系统管理, AUTH-认证管理
     */
    @TableField("module")
    private String module;

    /**
     * 操作类型
     * LOGIN-登录, LOGOUT-登出, CREATE-创建, UPDATE-更新, DELETE-删除,
     * VIEW-查看, EXPORT-导出, IMPORT-导入, UPLOAD-上传, DOWNLOAD-下载
     */
    @TableField("operation_type")
    private String operationType;

    /**
     * 操作描述
     */
    @TableField("description")
    private String description;

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
     * 请求参数
     */
    @TableField("request_param")
    private String requestParam;

    /**
     * 响应结果
     */
    @TableField("response_result")
    private String responseResult;

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
     * 类名
     */
    @TableField("class_name")
    private String className;

    /**
     * 方法名
     */
    @TableField("method_name")
    private String methodName;

    /**
     * 操作状态（0-失败，1-成功）
     */
    @TableField("status")
    private Integer status;

    /**
     * 错误信息
     */
    @TableField("error_msg")
    private String errorMsg;

    /**
     * 执行时间（毫秒）
     */
    @TableField("execution_time")
    private Long executionTime;

    /**
     * 风险等级
     * LOW-低风险, MEDIUM-中风险, HIGH-高风险, CRITICAL-严重风险
     */
    @TableField("risk_level")
    private String riskLevel;

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
     * 是否为敏感操作
     */
    @TableField("sensitive")
    private Boolean sensitive;

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
     * 获取操作是否成功
     *
     * @return 是否成功
     */
    public boolean getIsSuccess() {
        return this.status != null && this.status == 1;
    }

    /**
     * 设置操作是否成功
     *
     * @param success 是否成功
     */
    public void setIsSuccess(boolean success) {
        this.status = success ? 1 : 0;
    }

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
