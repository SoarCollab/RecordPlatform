package cn.flying.listener;

import cn.flying.common.event.TicketNotificationEvent;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 工单事件监听器
 * 在事务提交后异步发送 SSE 通知，避免事务内外部调用导致的死锁风险
 */
@Component
@Slf4j
public class TicketEventListener {

    @Resource
    private SseEmitterManager sseEmitterManager;

    /**
     * 处理工单通知事件
     * 使用 @TransactionalEventListener 确保在事务提交后执行
     * 使用 @Async 确保异步执行，不阻塞主线程
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void handleTicketNotification(TicketNotificationEvent event) {
        try {
            SseEventType eventType = switch (event.getNotificationType()) {
                case TICKET_REPLY -> SseEventType.TICKET_REPLY;
                case TICKET_STATUS_UPDATE -> SseEventType.TICKET_UPDATE;
            };

            sseEmitterManager.sendToUser(
                    event.getTenantId(),
                    event.getTargetUserId(),
                    SseEvent.of(eventType, event.getPayload())
            );

            log.debug("工单通知发送成功: type={}, targetUser={}, ticketNo={}",
                    event.getNotificationType(),
                    event.getTargetUserId(),
                    event.getPayload().get("ticketNo"));

        } catch (Exception e) {
            // SSE 发送失败不应影响业务，仅记录警告日志
            log.warn("工单通知发送失败: type={}, targetUser={}, ticketNo={}, error={}",
                    event.getNotificationType(),
                    event.getTargetUserId(),
                    event.getPayload().get("ticketNo"),
                    e.getMessage());
        }
    }
}
