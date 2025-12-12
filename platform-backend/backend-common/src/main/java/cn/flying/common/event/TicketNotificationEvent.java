package cn.flying.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * 工单通知事件
 * 用于事务提交后异步发送 SSE 通知，避免在事务内执行外部调用导致死锁风险
 */
@Getter
public class TicketNotificationEvent extends ApplicationEvent {

    public enum NotificationType {
        TICKET_REPLY,       // 工单回复通知
        TICKET_STATUS_UPDATE // 工单状态更新通知
    }

    private final NotificationType notificationType;
    private final Long tenantId;
    private final Long targetUserId;
    private final Map<String, Object> payload;

    public TicketNotificationEvent(Object source, NotificationType notificationType,
                                   Long tenantId, Long targetUserId,
                                   Map<String, Object> payload) {
        super(source);
        this.notificationType = notificationType;
        this.tenantId = tenantId;
        this.targetUserId = targetUserId;
        this.payload = payload;
    }

    /**
     * 创建工单回复通知事件
     */
    public static TicketNotificationEvent replyEvent(Object source, Long tenantId, Long targetUserId,
                                                      String ticketId, String ticketNo,
                                                      String replierName, String preview) {
        return new TicketNotificationEvent(source, NotificationType.TICKET_REPLY, tenantId, targetUserId,
                Map.of(
                        "ticketId", ticketId,
                        "ticketNo", ticketNo,
                        "replierName", replierName,
                        "preview", preview
                ));
    }

    /**
     * 创建工单状态更新通知事件
     */
    public static TicketNotificationEvent statusUpdateEvent(Object source, Long tenantId, Long targetUserId,
                                                             String ticketId, String ticketNo,
                                                             String oldStatus, String newStatus) {
        return new TicketNotificationEvent(source, NotificationType.TICKET_STATUS_UPDATE, tenantId, targetUserId,
                Map.of(
                        "ticketId", ticketId,
                        "ticketNo", ticketNo,
                        "oldStatus", oldStatus,
                        "newStatus", newStatus
                ));
    }
}
