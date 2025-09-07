package cn.flying.identity.event;

import cn.flying.identity.dto.AuditLog;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 审计日志事件
 * 用于异步处理审计日志记录
 *
 * @author flying
 * @date 2024
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEvent {

    /**
     * 审计日志对象
     */
    private AuditLog auditLog;

    /**
     * 事件类型
     */
    private String eventType;

    /**
     * 事件时间戳
     */
    private Long timestamp;

    /**
     * 构造函数
     *
     * @param auditLog 审计日志
     */
    public AuditLogEvent(AuditLog auditLog) {
        this.auditLog = auditLog;
        this.eventType = "AUDIT_LOG";
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构造函数
     *
     * @param auditLog  审计日志
     * @param eventType 事件类型
     */
    public AuditLogEvent(AuditLog auditLog, String eventType) {
        this.auditLog = auditLog;
        this.eventType = eventType;
        this.timestamp = System.currentTimeMillis();
    }
}