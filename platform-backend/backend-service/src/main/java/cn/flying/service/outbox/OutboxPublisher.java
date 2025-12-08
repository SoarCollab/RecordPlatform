package cn.flying.service.outbox;

import cn.flying.dao.entity.OutboxEvent;
import cn.flying.dao.mapper.OutboxEventMapper;
import cn.flying.service.monitor.SagaMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * Outbox 事件定时发布器
 * 轮询 outbox 表并将事件发布到 RabbitMQ。
 * 每条记录独立事务，避免长时间锁定。
 * 集成 Prometheus 监控指标。
 */
@Slf4j
@Component
public class OutboxPublisher {

    @Resource
    private OutboxEventMapper outboxMapper;

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private SagaMetrics sagaMetrics;

    // 自注入，用于在同类中调用事务方法（解决 Spring AOP 代理问题）
    @Lazy
    @Resource
    private OutboxPublisher self;

    private static final String FILE_EXCHANGE = "file.exchange";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRIES = 5;
    private static final int[] BACKOFF_SECONDS = {5, 30, 120, 600, 3600};
    public static final String HEADER_TRACE_ID = "X-Trace-ID";

    /**
     * 定时发布待处理事件
     * 注意：不再使用事务注解，每条记录由 publishSingleEvent 独立处理
     */
    @Scheduled(fixedDelay = 2000)
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxMapper.fetchPendingEvents(new Date(), BATCH_SIZE);

        if (events.isEmpty()) {
            return;
        }

        log.debug("开始发布 {} 条 outbox 事件", events.size());

        for (OutboxEvent event : events) {
            try {
                // 通过 self 调用以触发 Spring 事务代理
                self.publishSingleEvent(event);
            } catch (Exception ex) {
                // 单条记录失败不影响其他记录的处理
                log.error("发布事件失败: id={}, type={}", event.getId(), event.getEventType(), ex);
            }
        }
    }

    /**
     * 发布单条事件（独立事务）
     * 使用 REQUIRES_NEW 确保每条记录独立提交，避免长时间锁定
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishSingleEvent(OutboxEvent event) {
        Timer.Sample timerSample = sagaMetrics.startOutboxTimer();
        try {
            publishToRabbitMQ(event);
            outboxMapper.markSent(event.getId());
            sagaMetrics.recordOutboxPublished();
            log.debug("事件发布成功: type={}, id={}", event.getEventType(), event.getId());
        } catch (Exception ex) {
            log.error("发布事件到 RabbitMQ 失败: id={}", event.getId(), ex);
            sagaMetrics.recordOutboxFailed();
            Date nextAttempt = calculateBackoff(event.getRetryCount());
            outboxMapper.markFailed(event.getId(), nextAttempt);

            if (event.getRetryCount() >= MAX_RETRIES) {
                log.error("事件超过最大重试次数，标记为 FAILED: id={}", event.getId());
            }
            // 抛出异常让事务回滚 markFailed 的更新（如果需要）
            // 注意：这里不抛出异常，因为 markFailed 本身就是预期的失败处理
        } finally {
            sagaMetrics.stopOutboxTimer(timerSample);
        }
    }

    private void publishToRabbitMQ(OutboxEvent event) {
        MessageProperties props = new MessageProperties();
        props.setMessageId(event.getId());
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        // 传播 traceId 到消息 header，支持分布式追踪
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
