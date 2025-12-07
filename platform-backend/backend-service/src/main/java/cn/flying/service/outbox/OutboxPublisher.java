package cn.flying.service.outbox;

import cn.flying.dao.entity.OutboxEvent;
import cn.flying.dao.mapper.OutboxEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Scheduled publisher for outbox events.
 * Polls the outbox table and publishes events to RabbitMQ.
 * 支持分布式追踪，将 traceId 通过消息 header 传播。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private final OutboxEventMapper outboxMapper;
    private final RabbitTemplate rabbitTemplate;

    private static final String FILE_EXCHANGE = "file.exchange";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 5;
    private static final int[] BACKOFF_SECONDS = {5, 30, 120, 600, 3600};
    public static final String HEADER_TRACE_ID = "X-Trace-ID";

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxMapper.fetchPendingEvents(new Date(), BATCH_SIZE);

        if (events.isEmpty()) {
            return;
        }

        log.debug("Publishing {} outbox events", events.size());

        for (OutboxEvent event : events) {
            try {
                publishEvent(event);
                outboxMapper.markSent(event.getId());
                log.debug("Published event: type={}, id={}", event.getEventType(), event.getId());
            } catch (Exception ex) {
                log.error("Failed to publish event: id={}", event.getId(), ex);
                Date nextAttempt = calculateBackoff(event.getRetryCount());
                outboxMapper.markFailed(event.getId(), nextAttempt);

                if (event.getRetryCount() >= MAX_RETRIES) {
                    log.error("Event exceeded max retries, moved to FAILED: id={}", event.getId());
                }
            }
        }
    }

    private void publishEvent(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.getId());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        // 将 traceId 传播到消息 header，支持分布式追踪
        if (event.getTraceId() != null) {
            props.setHeader(HEADER_TRACE_ID, event.getTraceId());
        }

        Message message = new Message(event.getPayload().getBytes(), props);
        rabbitTemplate.send(FILE_EXCHANGE, event.getEventType(), message);
    }

    private Date calculateBackoff(int retryCount) {
        int index = Math.min(retryCount, BACKOFF_SECONDS.length - 1);
        int seconds = BACKOFF_SECONDS[index];

        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.SECOND, seconds);
        return cal.getTime();
    }
}
