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
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;

/**
 * Saga 故障补偿集成测试。
 * 使用真实 MySQL（Testcontainers）验证 Saga 编排器在外部服务故障下的数据库状态转换，
 * 以及 REQUIRES_NEW 事务独立提交语义。
 */
class SagaCompensationIT extends FaultInjectionBaseIT {

    @Autowired
    private FileSagaOrchestrator orchestrator;

    @Autowired
    private SagaCompensationHelper compensationHelper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // ──────────────────────────── Test 1 ────────────────────────────

    /**
     * S3 上传失败 → 补偿：文件状态变 FAIL，Saga 状态变 COMPENSATED，无 outbox 成功事件。
     */
    @Test
    void executeUpload_s3Fails_sagaCompensatedAndFileMarkedFail() throws Exception {
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

        // S3 未成功上传（未到达 S3_UPLOADED 步骤）→ 无需 S3 补偿 → 直接 COMPENSATED
        assertEquals(FileSagaStatus.COMPENSATED.name(), saga.getStatus(),
                "S3 失败时补偿应成功完成（无需删除 S3 数据）");

        // 文件应被标记为 FAIL
        File updatedFile = fileMapper.selectById(file.getId());
        assertEquals(FileUploadStatus.FAIL.getCode(), updatedFile.getStatus().intValue(),
                "S3 失败后文件状态应为 FAIL");
    }

    // ──────────────────────────── Test 2 ────────────────────────────

    /**
     * S3 成功 + 区块链失败 → S3 数据被补偿删除，Saga 最终 COMPENSATED。
     */
    @Test
    void executeUpload_s3SuccessChainFails_s3CompensatedAndSagaCompensated() throws Exception {
        File file = insertTestFile(TEST_USER_ID, FileUploadStatus.PREPARE.getCode());
        java.io.File tempFile = createTempFileWithContent("chunk-data");
        String requestId = UUID.randomUUID().toString();

        // S3 上传成功
        Mockito.lenient()
                .when(fileRemoteClient.storeFileChunk(any(), any()))
                .thenReturn(Result.success("bucket/path/to/file"));

        // 区块链返回 null（ResultUtils.getData 会抛出异常）
        Mockito.lenient()
                .when(fileRemoteClient.storeFileOnChain(any()))
                .thenReturn(null);

        // S3 补偿删除成功
        Mockito.lenient()
                .when(fileRemoteClient.deleteStorageFile(any()))
                .thenReturn(Result.success(true));

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

        // S3 已上传 → 补偿删除 S3 → COMPENSATED
        assertEquals(FileSagaStatus.COMPENSATED.name(), saga.getStatus(),
                "区块链失败后 S3 数据应被补偿删除，Saga 应为 COMPENSATED");

        // 验证 S3 删除被调用（补偿发生）
        Mockito.verify(fileRemoteClient, Mockito.atLeastOnce()).deleteStorageFile(any());

        // 验证 saga payload 包含 S3_DELETED 步骤
        assertNotNull(saga.getPayload());
        assertTrue(saga.getPayload().contains("S3_DELETED"),
                "Payload 应记录 S3 补偿步骤已完成");
    }

    // ──────────────────────────── Test 3 ────────────────────────────

    /**
     * PENDING_COMPENSATION Saga：第一次补偿失败（S3 不可用），第二次成功。
     * 验证：retryCount 递增，最终状态 COMPENSATED。
     */
    @Test
    void retryCompensation_persistsStateAcrossRetries() {
        File file = insertTestFile(TEST_USER_ID, FileUploadStatus.PREPARE.getCode());
        String payloadJson = "{\"storedPaths\":{\"hash1\":\"path/to/file\"},\"compensatedSteps\":[]}";
        String requestId = UUID.randomUUID().toString();

        FileSaga saga = insertTestSaga(file.getId(), requestId,
                FileSagaStatus.PENDING_COMPENSATION.name(),
                FileSagaStep.S3_UPLOADED.name(), 1, payloadJson);
        // 确保 isRetryDue() 返回 true
        saga.setNextRetryAt(new Date(0));
        sagaMapper.updateById(saga);

        // 第一次调用：S3 删除失败；第二次：成功
        Mockito.lenient()
                .when(fileRemoteClient.deleteStorageFile(any()))
                .thenThrow(new RuntimeException("S3 unavailable"))
                .thenReturn(Result.success(true));

        // ── 第一次补偿重试 ──
        orchestrator.retryCompensation(saga);

        FileSaga afterFirst = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(afterFirst);
        assertEquals(FileSagaStatus.PENDING_COMPENSATION.name(), afterFirst.getStatus(),
                "S3 删除失败后 Saga 应保持 PENDING_COMPENSATION");
        assertEquals(2, afterFirst.getRetryCount().intValue(),
                "retryCount 应从 1 递增到 2");

        // ── 第二次补偿重试（使用从 DB 刷新的 saga）──
        orchestrator.retryCompensation(afterFirst);

        FileSaga afterSecond = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(afterSecond);
        assertEquals(FileSagaStatus.COMPENSATED.name(), afterSecond.getStatus(),
                "第二次补偿成功后 Saga 应为 COMPENSATED");
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

        // S3 删除始终失败
        Mockito.lenient()
                .when(fileRemoteClient.deleteStorageFile(any()))
                .thenThrow(new RuntimeException("S3 permanently unavailable"));

        orchestrator.retryCompensation(saga);

        // Saga 应变 FAILED（超过最大重试）
        FileSaga updated = sagaMapper.selectByRequestId(requestId, TEST_TENANT_ID);
        assertNotNull(updated);
        assertEquals(FileSagaStatus.FAILED.name(), updated.getStatus(),
                "超过 maxRetries 后 Saga 应为 FAILED");
        assertEquals(6, updated.getRetryCount().intValue(),
                "recordError 后 retryCount 应从 5 增到 6");

        // outbox 表应有 saga.compensation.failed 死信事件
        OutboxEvent deadLetter = outboxMapper.selectOne(
                new LambdaQueryWrapper<OutboxEvent>()
                        .eq(OutboxEvent::getEventType, "saga.compensation.failed")
                        .eq(OutboxEvent::getAggregateId, saga.getId()));
        assertNotNull(deadLetter, "超过最大重试后应向 outbox 发布死信事件");
        trackOutboxId(deadLetter.getId());
    }

    // ──────────────────────────── Test 5 ────────────────────────────

    /**
     * REQUIRES_NEW 独立提交语义：外层事务回滚后，REQUIRES_NEW 提交的 payload 仍保留。
     */
    @Test
    void persistPayloadInNewTransaction_survivesOuterRollback() {
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
        assertEquals(testPayload, finalSaga.getPayload(),
                "REQUIRES_NEW 提交的 payload 不应被外层回滚撤销");
        assertEquals(FileSagaStatus.RUNNING.name(), finalSaga.getStatus(),
                "外层事务对 status 的修改应被回滚");
    }
}
