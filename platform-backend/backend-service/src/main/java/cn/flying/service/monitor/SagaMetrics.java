package cn.flying.service.monitor;

import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.dao.mapper.OutboxEventMapper;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Saga 和 Outbox 业务指标监控。
 * 提供分布式事务和事件发布的 Prometheus 指标。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaMetrics {

    private final MeterRegistry registry;
    private final FileSagaMapper sagaMapper;
    private final OutboxEventMapper outboxMapper;

    @Value("${outbox.publisher.max-retries:5}")
    private int maxRetries;

    // Saga 计数器
    private Counter sagaStartedCounter;
    private Counter sagaCompletedCounter;
    private Counter sagaCompensatedCounter;
    private Counter sagaFailedCounter;

    // Saga 计时器
    private Timer sagaDurationTimer;
    private Timer compensationDurationTimer;

    // Outbox 计数器
    private Counter outboxPublishedCounter;
    private Counter outboxFailedCounter;

    // Outbox 计时器
    private Timer outboxPublishLatencyTimer;

    // 状态仪表盘
    private final AtomicLong sagaRunningCount = new AtomicLong(0);
    private final AtomicLong sagaPendingCompensationCount = new AtomicLong(0);
    private final AtomicLong outboxPendingCount = new AtomicLong(0);
    private final AtomicLong outboxExhaustedCount = new AtomicLong(0);

    @PostConstruct
    public void init() {
        // Saga 计数器
        sagaStartedCounter = Counter.builder("saga.total")
                .description("Saga 启动总数")
                .tag("status", "started")
                .register(registry);

        sagaCompletedCounter = Counter.builder("saga.total")
                .description("Saga 完成总数")
                .tag("status", "completed")
                .register(registry);

        sagaCompensatedCounter = Counter.builder("saga.total")
                .description("Saga 补偿总数")
                .tag("status", "compensated")
                .register(registry);

        sagaFailedCounter = Counter.builder("saga.total")
                .description("Saga 失败总数")
                .tag("status", "failed")
                .register(registry);

        // Saga 计时器
        sagaDurationTimer = Timer.builder("saga.duration")
                .description("Saga 执行耗时")
                .tag("phase", "execution")
                .register(registry);

        compensationDurationTimer = Timer.builder("saga.duration")
                .description("Saga 补偿耗时")
                .tag("phase", "compensation")
                .register(registry);

        // Outbox 计数器
        outboxPublishedCounter = Counter.builder("outbox.events.total")
                .description("Outbox 事件发布成功总数")
                .tag("status", "published")
                .register(registry);

        outboxFailedCounter = Counter.builder("outbox.events.total")
                .description("Outbox 事件发布失败总数")
                .tag("status", "failed")
                .register(registry);

        // Outbox 计时器
        outboxPublishLatencyTimer = Timer.builder("outbox.publish.latency")
                .description("Outbox 事件发布延迟")
                .register(registry);

        // 状态仪表盘
        Gauge.builder("saga.running", sagaRunningCount, AtomicLong::get)
                .description("运行中的 Saga 数量")
                .register(registry);

        Gauge.builder("saga.pending_compensation", sagaPendingCompensationCount, AtomicLong::get)
                .description("待补偿的 Saga 数量")
                .register(registry);

        Gauge.builder("outbox.pending", outboxPendingCount, AtomicLong::get)
                .description("待发送的 Outbox 事件数量")
                .register(registry);

        Gauge.builder("outbox.exhausted", outboxExhaustedCount, AtomicLong::get)
                .description("超过最大重试次数的 Outbox 事件数量")
                .register(registry);

        log.info("Saga/Outbox Prometheus 监控指标已初始化");
    }

    // ==================== Saga 指标记录方法 ====================

    /**
     * 记录 Saga 启动
     */
    public void recordSagaStarted() {
        sagaStartedCounter.increment();
    }

    /**
     * 记录 Saga 完成
     */
    public void recordSagaCompleted() {
        sagaCompletedCounter.increment();
    }

    /**
     * 记录 Saga 补偿成功
     */
    public void recordSagaCompensated() {
        sagaCompensatedCounter.increment();
    }

    /**
     * 记录 Saga 失败
     */
    public void recordSagaFailed() {
        sagaFailedCounter.increment();
    }

    /**
     * 开始 Saga 执行计时
     */
    public Timer.Sample startSagaTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止 Saga 执行计时
     */
    public void stopSagaTimer(Timer.Sample sample) {
        sample.stop(sagaDurationTimer);
    }

    /**
     * 开始补偿计时
     */
    public Timer.Sample startCompensationTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止补偿计时
     */
    public void stopCompensationTimer(Timer.Sample sample) {
        sample.stop(compensationDurationTimer);
    }

    // ==================== Outbox 指标记录方法 ====================

    /**
     * 记录 Outbox 事件发布成功
     */
    public void recordOutboxPublished() {
        outboxPublishedCounter.increment();
    }

    /**
     * 记录 Outbox 事件发布失败
     */
    public void recordOutboxFailed() {
        outboxFailedCounter.increment();
    }

    /**
     * 开始 Outbox 发布计时
     */
    public Timer.Sample startOutboxTimer() {
        return Timer.start(registry);
    }

    /**
     * 停止 Outbox 发布计时
     */
    public void stopOutboxTimer(Timer.Sample sample) {
        sample.stop(outboxPublishLatencyTimer);
    }

    // ==================== 状态刷新方法 ====================

    /**
     * 刷新 Saga/Outbox 状态指标。
     * 建议定时调用（如每分钟一次）。
     */
    public void refreshStatus() {
        try {
            // Saga 状态
            sagaRunningCount.set(sagaMapper.countByStatus(FileSagaStatus.RUNNING.name()));
            sagaPendingCompensationCount.set(sagaMapper.countByStatus(FileSagaStatus.PENDING_COMPENSATION.name()));

            // Outbox 状态
            outboxPendingCount.set(outboxMapper.countByStatus("PENDING"));
            outboxExhaustedCount.set(outboxMapper.countExhaustedRetries(maxRetries));

        } catch (Exception e) {
            log.warn("刷新 Saga/Outbox 状态指标失败: {}", e.getMessage());
        }
    }
}
