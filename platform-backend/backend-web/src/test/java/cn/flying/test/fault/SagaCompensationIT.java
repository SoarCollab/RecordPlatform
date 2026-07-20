package cn.flying.test.fault;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.entity.FileSagaStep;
import cn.flying.dao.entity.OutboxEvent;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.service.saga.FileUploadCommand;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.service.saga.SagaCompensationHelper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Saga 故障与历史补偿失败关闭集成测试。
 * 使用真实 MySQL（Testcontainers）验证 Saga 编排器不会删除共享内容寻址对象，
 * 并验证终态、死信和 REQUIRES_NEW 独立提交语义。
 */
class SagaCompensationIT extends FaultInjectionBaseIT {

    @Autowired
    private FileSagaOrchestrator orchestrator;

    @Autowired
    private SagaCompensationHelper compensationHelper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    // ──────────────────────────── Test 1 ────────────────────────────

    /**
     * S3 上传失败时 Saga 在链边界前失败关闭，不删除对象，也不越权回写文件业务状态。
     */
    @Test
    void executeUpload_s3Fails_sagaFailsClosedAndFileRemainsPrepare() throws Exception {
        File file = insertTestFile(TEST_USER_ID, FileUploadStatus.PREPARE.getCode());
        java.io.File tempFile = createTempFileWithContent("chunk-data");
        String requestId = UUID.randomUUID().toString();

        // storeFileChunk 返回业务错误（非 200）
        Mockito.lenient()
                .when(fileRemoteClient.storeFileChunk(any(), any()))
                .thenReturn(new Result<>(ResultEnum.FILE_SERVICE_ERROR, null));

        FileUploadCommand cmd = FileUploadCommand.builder()
                .requestId(requestId)
                .fileId(file.getId())
                .userId(TEST_USER_ID)
                .fileName("test.txt")
                .fileParam("{\"fileSize\":100}")
                .fileList(List.of(tempFile))
                .fileHashList(List.of("abc123hash"))
                .tenantId(TEST_TENANT_ID)
                .build();

        // executeUpload 应抛出异常
        assertThrows(Exception.class, () -> orchestrator.executeUpload(cmd));

        FileSaga saga = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(saga, "Saga 应已持久化到 DB");
        trackSagaId(saga.getId());

        assertEquals(FileSagaStatus.FAILED.name(), saga.getStatus(),
                "链边界前失败应持久化为 FAILED");
        Mockito.verify(fileRemoteClient, Mockito.never()).deleteStorageFile(any());

        // Saga 编排器不直接越权修改文件表，调用方按自身 claim 状态决定后续处理。
        File updatedFile = fileMapper.selectById(file.getId());
        assertEquals(FileUploadStatus.PREPARE.getCode(), updatedFile.getStatus().intValue(),
                "Saga 失败关闭不应直接覆盖 PREPARE 文件状态");
    }

    // ──────────────────────────── Test 2 ────────────────────────────

    /**
     * S3 成功后链响应失败时保留对象和现场，Saga 进入人工对账。
     */
    @Test
    void executeUpload_s3SuccessChainFails_requiresManualReconciliationWithoutDelete() throws Exception {
        File file = insertTestFile(TEST_USER_ID, FileUploadStatus.PREPARE.getCode());
        java.io.File tempFile = createTempFileWithContent("chunk-data");
        String requestId = UUID.randomUUID().toString();

        // S3 上传成功
        Mockito.lenient()
                .when(fileRemoteClient.storeFileChunk(any(), any()))
                .thenReturn(Result.success("bucket/path/to/file"));

        // 区块链返回 null（ResultUtils.getData 会抛出异常）
        Mockito.lenient()
                .when(fileRemoteClient.storeFileOnChainOnce(any()))
                .thenReturn(null);

        FileUploadCommand cmd = FileUploadCommand.builder()
                .requestId(requestId)
                .fileId(file.getId())
                .userId(TEST_USER_ID)
                .fileName("test.txt")
                .fileParam("{\"fileSize\":100}")
                .fileList(List.of(tempFile))
                .fileHashList(List.of("abc123hash"))
                .tenantId(TEST_TENANT_ID)
                .build();

        assertThrows(Exception.class, () -> orchestrator.executeUpload(cmd));

        FileSaga saga = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(saga);
        trackSagaId(saga.getId());

        assertEquals(FileSagaStatus.MANUAL_RECONCILIATION.name(), saga.getStatus(),
                "越过链调用边界后必须保留现场并进入人工对账");
        Mockito.verify(fileRemoteClient, Mockito.never()).deleteStorageFile(any());

        // payload 保留存储证据，且不能伪造已删除步骤。
        assertNotNull(saga.getPayload());
        assertTrue(saga.getPayload().contains("storedObjects"));
        assertFalse(saga.getPayload().contains("S3_DELETED"));
    }

    // ──────────────────────────── Test 3 ────────────────────────────

    /**
     * 未达到旧版重试上限的历史 PENDING_COMPENSATION 也必须失败关闭并发布死信。
     */
    @Test
    void retryCompensation_beforeRetryLimit_failsClosedWithoutDeletingSharedObject() {
        File file = insertTestFile(TEST_USER_ID, FileUploadStatus.PREPARE.getCode());
        String payloadJson = "{\"storedPaths\":{\"hash1\":\"path/to/file\"},\"compensatedSteps\":[]}";
        String requestId = UUID.randomUUID().toString();

        FileSaga saga = insertTestSaga(file.getId(), requestId,
                FileSagaStatus.PENDING_COMPENSATION.name(),
                FileSagaStep.S3_UPLOADED.name(), 1, payloadJson);
        orchestrator.retryCompensation(saga);

        FileSaga updated = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(updated);
        assertEquals(FileSagaStatus.FAILED.name(), updated.getStatus(),
                "历史物理删除补偿必须失败关闭");
        assertEquals(2, updated.getRetryCount().intValue(),
                "retryCount 应从 1 递增到 2");
        assertTrue(updated.getLastError().contains("自动物理删除补偿已停用"));
        Mockito.verify(fileRemoteClient, Mockito.never()).deleteStorageFile(any());

        OutboxEvent deadLetter = findDeadLetter(saga.getId());
        assertNotNull(deadLetter, "失败关闭后应发布死信事件");
        trackOutboxId(deadLetter.getId());
    }

    // ──────────────────────────── Test 4 ────────────────────────────

    /**
     * retryCount 已等于 maxRetries（默认 5）时补偿再次失败 → Saga 变 FAILED，outbox 有死信事件。
     */
    @Test
    void retryCompensation_maxRetriesExceeded_marksFailedAndPublishesDeadLetter() {
        File file = insertTestFile(TEST_USER_ID, FileUploadStatus.PREPARE.getCode());
        String payloadJson = "{\"storedPaths\":{\"hash1\":\"path/to/file\"},\"compensatedSteps\":[]}";
        String requestId = UUID.randomUUID().toString();

        // retryCount=5 = maxCompensationRetries 默认值
        FileSaga saga = insertTestSaga(file.getId(), requestId,
                FileSagaStatus.PENDING_COMPENSATION.name(),
                FileSagaStep.S3_UPLOADED.name(), 5, payloadJson);

        orchestrator.retryCompensation(saga);

        // Saga 应变 FAILED（超过最大重试）
        FileSaga updated = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(updated);
        assertEquals(FileSagaStatus.FAILED.name(), updated.getStatus(),
                "超过 maxRetries 后 Saga 应为 FAILED");
        assertEquals(6, updated.getRetryCount().intValue(),
                "recordError 后 retryCount 应从 5 增到 6");
        assertTrue(updated.getLastError().contains("达到重试上限"));
        Mockito.verify(fileRemoteClient, Mockito.never()).deleteStorageFile(any());

        // outbox 表应有 saga.compensation.failed 死信事件
        OutboxEvent deadLetter = findDeadLetter(saga.getId());
        assertNotNull(deadLetter, "超过最大重试后应向 outbox 发布死信事件");
        trackOutboxId(deadLetter.getId());
    }

    /**
     * 按 Saga 主键查询失败关闭时发布的死信事件。
     */
    private OutboxEvent findDeadLetter(Long sagaId) {
        return outboxMapper.selectOne(
                new LambdaQueryWrapper<OutboxEvent>()
                        .eq(OutboxEvent::getEventType, "saga.compensation.failed")
                        .eq(OutboxEvent::getAggregateId, sagaId));
    }

    // ──────────────────────────── Test 5 ────────────────────────────

    /**
     * REQUIRES_NEW 独立提交语义：外层事务回滚后，REQUIRES_NEW 提交的 payload 仍保留。
     */
    @Test
    void persistPayloadInNewTransaction_survivesOuterRollback() throws Exception {
        FileSaga saga = insertTestSaga(null, UUID.randomUUID().toString(),
                FileSagaStatus.RUNNING.name(), FileSagaStep.S3_UPLOADED.name(), 0, null);

        String testPayload = "{\"storedPaths\":{},\"compensatedSteps\":[\"S3_DELETED\"]}";

        // 开启外层事务
        TransactionStatus outerTx = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try {
            // REQUIRES_NEW：先提交 payload（独立事务，提交后释放行锁）
            // 必须在外层事务修改同一行之前执行，避免行锁冲突导致等待超时
            compensationHelper.persistPayloadInNewTransaction(saga, testPayload);

            // 外层事务修改 status（REQUIRES_NEW 已释放锁，此处可正常获取锁）
            saga.setStatus(FileSagaStatus.COMPENSATING.name());
            sagaMapper.updateById(saga);

            // 回滚外层事务（status 变更将被撤销；payload 已由 REQUIRES_NEW 独立提交）
            transactionManager.rollback(outerTx);
        } catch (Exception e) {
            transactionManager.rollback(outerTx);
            throw e;
        }

        // 查询：
        // - payload 由 REQUIRES_NEW 独立提交，不受外层回滚影响
        // - status 由外层事务修改，外层回滚后恢复为 RUNNING
        FileSaga finalSaga = sagaMapper.selectByRequestId(saga.getRequestId(), TEST_TENANT_ID);
        assertNotNull(finalSaga);
        // payload 经过 MyBatis Plus / Jackson 反序列化后可能格式化（加空格），用 JSON 语义比较
        JsonNode expectedJson = objectMapper.readTree(testPayload);
        JsonNode actualJson = objectMapper.readTree(finalSaga.getPayload());
        assertEquals(expectedJson, actualJson,
                "REQUIRES_NEW 提交的 payload 不应被外层回滚撤销");
        assertEquals(FileSagaStatus.RUNNING.name(), finalSaga.getStatus(),
                "外层事务对 status 的修改应被回滚");
    }
}
