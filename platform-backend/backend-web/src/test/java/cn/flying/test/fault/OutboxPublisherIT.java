package cn.flying.test.fault;

import cn.flying.dao.entity.OutboxEvent;
import cn.flying.dao.entity.Tenant;
import cn.flying.service.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.AmqpException;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

/**
 * OutboxPublisher 故障注入集成测试。
 * 使用真实 MySQL + mock RabbitTemplate 验证：
 * - RabbitMQ 不可用时 markFailed 的 DB 状态
 * - 指数退避 nextAttemptAt 计算
 * - 多租户发布隔离
 */
class OutboxPublisherIT extends FaultInjectionBaseIT {

    @Autowired
    private OutboxPublisher outboxPublisher;

    private Long secondTenantId;

    @AfterEach
    void cleanUpSecondTenant() {
        if (secondTenantId != null) {
            try {
                tenantMapper.deleteById(secondTenantId);
            } catch (Exception ignored) {}
            secondTenantId = null;
        }
    }

    // ──────────────────────────── Test 1 ────────────────────────────

    /**
     * RabbitMQ send 成功 → outbox 状态变 SENT，sentTime 已设置。
     */
    @Test
    void publishSingleEvent_success_marksEventSent() {
        // 默认 mock：rabbitTemplate.send() 不抛异常（返回 void）
        OutboxEvent event = insertTestOutboxEvent("file.stored", 100L, 0);

        outboxPublisher.publishSingleEvent(event);

        OutboxEvent updated = outboxMapper.selectById(event.getId());
        assertNotNull(updated);
        assertEquals(OutboxEvent.STATUS_SENT, updated.getStatus(),
                "发布成功后状态应为 SENT");
        assertNotNull(updated.getSentTime(), "发布成功后 sentTime 应已设置");
    }

    // ──────────────────────────── Test 2 ────────────────────────────

    /**
     * RabbitMQ 不可达（AmqpException）→ outbox 状态变 FAILED，retryCount+1，
     * nextAttemptAt ≈ now + 5s（BACKOFF_SECONDS[0]）。
     */
    @Test
    void publishSingleEvent_rabbitMqUnavailable_marksFailedWithBackoff5s() {
        OutboxEvent event = insertTestOutboxEvent("file.stored", 101L, 0);

        Mockito.doThrow(new AmqpException("connection refused"))
                .when(rabbitTemplate).send(anyString(), anyString(), any());

        long beforeMs = System.currentTimeMillis();
        outboxPublisher.publishSingleEvent(event);
        long afterMs = System.currentTimeMillis();

        OutboxEvent updated = outboxMapper.selectById(event.getId());
        assertNotNull(updated);
        assertEquals(OutboxEvent.STATUS_FAILED, updated.getStatus(),
                "RabbitMQ 不可达时状态应为 FAILED");
        assertEquals(1, updated.getRetryCount().intValue(),
                "retryCount 应从 0 递增到 1");

        assertNotNull(updated.getNextAttemptAt(), "nextAttemptAt 应已设置");
        long nextMs = updated.getNextAttemptAt().getTime();
        long expectedApproxMs = beforeMs + 5_000L;
        // 允许 ±10s 误差
        assertTrue(Math.abs(nextMs - expectedApproxMs) < 10_000L,
                "nextAttemptAt 应约为 now+5s，实际差值: " + (nextMs - expectedApproxMs) + "ms");
    }

    // ──────────────────────────── Test 3 ────────────────────────────

    /**
     * retryCount=3 时失败 → nextAttemptAt ≈ now + 600s（BACKOFF_SECONDS[3]）。
     */
    @Test
    void publishSingleEvent_retryCount3_appliesExponentialBackoff600s() {
        OutboxEvent event = insertTestOutboxEvent("file.stored", 102L, 3);

        Mockito.doThrow(new AmqpException("timeout"))
                .when(rabbitTemplate).send(anyString(), anyString(), any());

        long beforeMs = System.currentTimeMillis();
        outboxPublisher.publishSingleEvent(event);

        OutboxEvent updated = outboxMapper.selectById(event.getId());
        assertNotNull(updated);
        assertEquals(OutboxEvent.STATUS_FAILED, updated.getStatus());

        long nextMs = updated.getNextAttemptAt().getTime();
        long expectedApproxMs = beforeMs + 600_000L;
        assertTrue(Math.abs(nextMs - expectedApproxMs) < 10_000L,
                "retryCount=3 时 nextAttemptAt 应约为 now+600s，实际差值: " + (nextMs - expectedApproxMs) + "ms");
    }

    // ──────────────────────────── Test 4 ────────────────────────────

    /**
     * retryCount=10（超过数组长度）→ 使用最后一个退避值 3600s 上限。
     */
    @Test
    void publishSingleEvent_retryCount10_clampsToMaxBackoff3600s() {
        OutboxEvent event = insertTestOutboxEvent("file.stored", 103L, 10);

        Mockito.doThrow(new AmqpException("circuit open"))
                .when(rabbitTemplate).send(anyString(), anyString(), any());

        long beforeMs = System.currentTimeMillis();
        outboxPublisher.publishSingleEvent(event);

        OutboxEvent updated = outboxMapper.selectById(event.getId());
        assertNotNull(updated);
        assertEquals(OutboxEvent.STATUS_FAILED, updated.getStatus());

        long nextMs = updated.getNextAttemptAt().getTime();
        long expectedApproxMs = beforeMs + 3_600_000L;
        assertTrue(Math.abs(nextMs - expectedApproxMs) < 10_000L,
                "retryCount >= BACKOFF_SECONDS.length 时应使用最大退避 3600s");
    }

    // ──────────────────────────── Test 5 ────────────────────────────

    /**
     * 两个租户各有一个 PENDING 事件 → publishPendingEvents() 处理两个租户，
     * 两个事件均变 SENT。
     */
    @Test
    void publishPendingEvents_multiTenant_bothTenantsProcessed() {
        // 租户 1 的事件（TEST_TENANT_ID，由 insertTestOutboxEvent 使用当前 context）
        OutboxEvent event1 = insertTestOutboxEvent("file.stored", 200L, 0);

        // 创建租户 2
        Tenant secondTenant = new Tenant()
                .setName("Second Test Tenant")
                .setCode("test-second-" + System.currentTimeMillis())
                .setStatus(1);
        tenantMapper.insert(secondTenant);
        secondTenantId = secondTenant.getId();

        // 租户 2 的事件（切换 context 插入，insertTestOutboxEventForTenant 负责记录清理）
        OutboxEvent event2 = insertTestOutboxEventForTenant("file.stored", 201L, 0, secondTenantId);

        // rabbitTemplate 默认 mock（不抛异常）→ 发布成功
        // publishPendingEvents 轮询所有活跃租户
        outboxPublisher.publishPendingEvents();

        // 恢复 context 以便后续查询
        // 查询租户 1 的事件
        OutboxEvent updated1 = outboxMapper.selectById(event1.getId());
        assertNotNull(updated1);
        assertEquals(OutboxEvent.STATUS_SENT, updated1.getStatus(),
                "租户 1 的事件应被处理并标记为 SENT");

        // 查询租户 2 的事件（需切换 context）
        cn.flying.common.tenant.TenantContext.setTenantId(secondTenantId);
        OutboxEvent updated2 = outboxMapper.selectById(event2.getId());
        cn.flying.common.tenant.TenantContext.setTenantId(TEST_TENANT_ID);

        assertNotNull(updated2);
        assertEquals(OutboxEvent.STATUS_SENT, updated2.getStatus(),
                "租户 2 的事件应被处理并标记为 SENT");
    }

    // ──────────────────────────── helpers ────────────────────────────

    /**
     * 覆盖 nextAttemptAt 为过去时间，确保 fetchPendingEvents 查询能命中。
     * （insertTestOutboxEvent 已设 nextAttemptAt=epoch，本方法仅为文档说明目的）
     */
    @SuppressWarnings("unused")
    private void ensureEventDue(OutboxEvent event) {
        event.setNextAttemptAt(new Date(0));
        outboxMapper.updateById(event);
    }
}
