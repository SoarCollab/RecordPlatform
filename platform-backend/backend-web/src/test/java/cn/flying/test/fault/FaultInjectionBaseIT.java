package cn.flying.test.fault;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.OutboxEvent;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.dao.mapper.OutboxEventMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.test.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 故障注入集成测试基类。
 * 继承 BaseIntegrationTest（Testcontainers + Mockito mock），
 * 额外提供：
 * - TenantContext 初始化
 * - 测试数据插入 + 按 ID 清理 helpers
 * - 多租户 outbox 清理支持
 *
 * 注意：不加 @Transactional，因为 Saga 补偿依赖 REQUIRES_NEW 独立提交，
 * 外层事务会干扰内层事务语义的验证。
 */
abstract class FaultInjectionBaseIT extends BaseIntegrationTest {

    @Autowired
    protected FileSagaMapper sagaMapper;

    @Autowired
    protected OutboxEventMapper outboxMapper;

    @Autowired
    protected FileMapper fileMapper;

    @Autowired
    protected TenantMapper tenantMapper;

    protected static final Long TEST_TENANT_ID = 1L;
    protected static final Long TEST_USER_ID = 1L;

    // Track IDs inserted by helpers – base @AfterEach deletes them with TEST_TENANT_ID
    private final List<Long> insertedSagaIds = new ArrayList<>();
    private final List<Long> insertedFileIds = new ArrayList<>();
    private final List<String> insertedOutboxIds = new ArrayList<>();

    // Multi-tenant outbox tracking: id → tenantId
    private final Map<String, Long> insertedOutboxIdsWithTenant = new LinkedHashMap<>();

    @BeforeEach
    void setUpTenantContext() {
        TenantContext.setTenantId(TEST_TENANT_ID);
        ensureTestTenantExists();
    }

    @AfterEach
    void cleanUpTestData() {
        TenantContext.setTenantId(TEST_TENANT_ID);
        try {
            insertedSagaIds.forEach(id -> {
                try { sagaMapper.deleteById(id); } catch (Exception ignored) {}
            });
            insertedFileIds.forEach(id -> {
                try { fileMapper.deleteById(id); } catch (Exception ignored) {}
            });
            insertedOutboxIds.forEach(id -> {
                try { outboxMapper.deleteById(id); } catch (Exception ignored) {}
            });
            // Multi-tenant outbox cleanup: switch tenant context per entry
            insertedOutboxIdsWithTenant.forEach((id, tenantId) -> {
                try {
                    TenantContext.setTenantId(tenantId);
                    outboxMapper.deleteById(id);
                } catch (Exception ignored) {}
            });
        } finally {
            insertedSagaIds.clear();
            insertedFileIds.clear();
            insertedOutboxIds.clear();
            insertedOutboxIdsWithTenant.clear();
            TenantContext.clear();
        }
    }

    // ──────────────────────────── helpers ────────────────────────────

    /**
     * 插入测试用文件记录（使用当前 TenantContext，tenantId 由 MyBatis Plus fill 自动填充）。
     */
    protected File insertTestFile(Long userId, int status) {
        File file = new File()
                .setUid(userId)
                .setFileName("test-" + UUID.randomUUID() + ".txt")
                .setStatus(status);
        fileMapper.insert(file);
        insertedFileIds.add(file.getId());
        return file;
    }

    /**
     * 插入测试用 Saga 记录（使用当前 TenantContext）。
     */
    protected FileSaga insertTestSaga(Long fileId, String requestId, String status,
                                       String step, int retryCount, String payload) {
        FileSaga saga = new FileSaga()
                .setFileId(fileId)
                .setRequestId(requestId)
                .setUserId(TEST_USER_ID)
                .setFileName("test.txt")
                .setCurrentStep(step)
                .setStatus(status)
                .setRetryCount(retryCount)
                .setPayload(payload);
        sagaMapper.insert(saga);
        insertedSagaIds.add(saga.getId());
        return saga;
    }

    /**
     * 插入 PENDING 状态的 outbox 事件（使用当前 TenantContext，nextAttemptAt=epoch 确保立即可处理）。
     */
    protected OutboxEvent insertTestOutboxEvent(String eventType, Long aggregateId, int retryCount) {
        OutboxEvent event = new OutboxEvent()
                .setId(UUID.randomUUID().toString())
                .setEventType(eventType)
                .setAggregateType("FILE")
                .setAggregateId(aggregateId)
                .setPayload("{\"test\":true}")
                .setStatus(OutboxEvent.STATUS_PENDING)
                .setRetryCount(retryCount)
                .setNextAttemptAt(new Date(0)); // epoch → 过去时间，立即可处理
        outboxMapper.insert(event);
        insertedOutboxIds.add(event.getId());
        return event;
    }

    /**
     * 插入指定租户的 outbox 事件（切换 TenantContext，事后恢复为 TEST_TENANT_ID）。
     * 事件 ID 记录到多租户清理 map 中。
     */
    protected OutboxEvent insertTestOutboxEventForTenant(String eventType, Long aggregateId,
                                                          int retryCount, Long tenantId) {
        TenantContext.setTenantId(tenantId);
        try {
            OutboxEvent event = new OutboxEvent()
                    .setId(UUID.randomUUID().toString())
                    .setEventType(eventType)
                    .setAggregateType("FILE")
                    .setAggregateId(aggregateId)
                    .setPayload("{\"test\":true}")
                    .setStatus(OutboxEvent.STATUS_PENDING)
                    .setRetryCount(retryCount)
                    .setNextAttemptAt(new Date(0));
            outboxMapper.insert(event);
            insertedOutboxIdsWithTenant.put(event.getId(), tenantId);
            return event;
        } finally {
            TenantContext.setTenantId(TEST_TENANT_ID);
        }
    }

    /**
     * 手动追踪一个 saga ID（用于 orchestrator 内部创建的 saga）。
     */
    protected void trackSagaId(Long sagaId) {
        insertedSagaIds.add(sagaId);
    }

    /**
     * 手动追踪一个 outbox 事件 ID（使用 TEST_TENANT_ID 清理）。
     */
    protected void trackOutboxId(String outboxId) {
        insertedOutboxIds.add(outboxId);
    }

    /**
     * 创建带内容的临时文件（deleteOnExit 保证 JVM 退出时清理）。
     */
    protected java.io.File createTempFileWithContent(String content) throws IOException {
        java.nio.file.Path tempPath = Files.createTempFile("test-upload-", ".bin");
        Files.writeString(tempPath, content);
        java.io.File f = tempPath.toFile();
        f.deleteOnExit();
        return f;
    }

    // ──────────────────────────── private ────────────────────────────

    private void ensureTestTenantExists() {
        if (tenantMapper.selectById(TEST_TENANT_ID) == null) {
            Tenant tenant = new Tenant()
                    .setId(TEST_TENANT_ID)
                    .setName("Test Tenant")
                    .setCode("test")
                    .setStatus(1);
            tenantMapper.insert(tenant);
        }
    }
}
