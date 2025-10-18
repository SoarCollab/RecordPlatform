package cn.flying.monitor.common.security;

/**
 * 系统权限枚举
 */
public enum Permission {
    
    // 系统管理权限
    SYSTEM_ADMIN("system:admin", "系统管理员权限"),
    SYSTEM_CONFIG("system:config", "系统配置权限"),
    SYSTEM_AUDIT("system:audit", "系统审计权限"),
    
    // 用户管理权限
    USER_CREATE("user:create", "创建用户"),
    USER_READ("user:read", "查看用户"),
    USER_UPDATE("user:update", "更新用户"),
    USER_DELETE("user:delete", "删除用户"),
    USER_ROLE_ASSIGN("user:role:assign", "分配用户角色"),
    
    // 监控数据权限
    MONITOR_DATA_READ("monitor:data:read", "查看监控数据"),
    MONITOR_DATA_WRITE("monitor:data:write", "写入监控数据"),
    MONITOR_DATA_DELETE("monitor:data:delete", "删除监控数据"),
    MONITOR_DATA_EXPORT("monitor:data:export", "导出监控数据"),
    
    // 告警管理权限
    ALERT_CREATE("alert:create", "创建告警规则"),
    ALERT_READ("alert:read", "查看告警"),
    ALERT_UPDATE("alert:update", "更新告警规则"),
    ALERT_DELETE("alert:delete", "删除告警规则"),
    ALERT_ACKNOWLEDGE("alert:acknowledge", "确认告警"),
    
    // 服务器管理权限
    SERVER_CREATE("server:create", "添加服务器"),
    SERVER_READ("server:read", "查看服务器"),
    SERVER_UPDATE("server:update", "更新服务器"),
    SERVER_DELETE("server:delete", "删除服务器"),
    SERVER_CONTROL("server:control", "控制服务器"),
    
    // 客户端管理权限
    CLIENT_CREATE("client:create", "添加客户端"),
    CLIENT_READ("client:read", "查看客户端"),
    CLIENT_UPDATE("client:update", "更新客户端"),
    CLIENT_DELETE("client:delete", "删除客户端"),
    CLIENT_CERT_MANAGE("client:cert:manage", "管理客户端证书"),
    
    // 通知管理权限
    NOTIFICATION_CREATE("notification:create", "创建通知"),
    NOTIFICATION_READ("notification:read", "查看通知"),
    NOTIFICATION_UPDATE("notification:update", "更新通知"),
    NOTIFICATION_DELETE("notification:delete", "删除通知"),
    NOTIFICATION_SEND("notification:send", "发送通知"),
    
    // 报表权限
    REPORT_CREATE("report:create", "创建报表"),
    REPORT_READ("report:read", "查看报表"),
    REPORT_UPDATE("report:update", "更新报表"),
    REPORT_DELETE("report:delete", "删除报表"),
    REPORT_SCHEDULE("report:schedule", "调度报表"),
    
    // WebSocket权限
    WEBSOCKET_CONNECT("websocket:connect", "WebSocket连接"),
    WEBSOCKET_TERMINAL("websocket:terminal", "终端访问"),
    WEBSOCKET_MONITOR("websocket:monitor", "实时监控"),
    
    // 安全审计权限
    SECURITY_AUDIT_READ("security:audit:read", "查看安全审计日志"),
    SECURITY_CERT_MANAGE("security:cert:manage", "管理安全证书"),
    SECURITY_MFA_MANAGE("security:mfa:manage", "管理多因子认证");

    private final String code;
    private final String description;

    Permission(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据权限代码获取权限枚举
     */
    public static Permission fromCode(String code) {
        for (Permission permission : values()) {
            if (permission.code.equals(code)) {
                return permission;
            }
        }
        throw new IllegalArgumentException("Unknown permission code: " + code);
    }
}