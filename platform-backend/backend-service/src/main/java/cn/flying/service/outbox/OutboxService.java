package cn.flying.service.outbox;

import cn.flying.common.util.Const;
import cn.flying.dao.entity.OutboxEvent;
import cn.flying.dao.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

/**
 * Service for managing outbox events.
 * Events are written within the same transaction as business data.
 * 支持分布式追踪，自动从 MDC 获取 traceId。
 */
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxEventMapper outboxMapper;

    /**
     * Append an event to the outbox table.
     * Must be called within an existing transaction.
     * 自动从 MDC 获取当前 traceId 存入事件。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void appendEvent(String aggregateType, Long aggregateId, String eventType, String payload) {
        String traceId = MDC.get(Const.TRACE_ID);

        OutboxEvent event = new OutboxEvent()
                .setId(UUID.randomUUID().toString())
                .setTraceId(traceId)
                .setAggregateType(aggregateType)
                .setAggregateId(aggregateId)
                .setEventType(eventType)
                .setPayload(payload)
                .setStatus(OutboxEvent.STATUS_PENDING)
                .setNextAttemptAt(new Date())
                .setRetryCount(0);

        outboxMapper.insert(event);
    }
}
