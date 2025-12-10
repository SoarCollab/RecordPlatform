package cn.flying.service.saga;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.entity.FileSagaStep;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.service.monitor.SagaMetrics;
import cn.flying.service.outbox.OutboxService;
import cn.flying.service.remote.FileRemoteClient;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * 文件上传 Saga 编排器。
 * 协调 MinIO 上传 + 区块链存储的分布式事务，失败时自动补偿。
 * 支持指数退避重试策略。
 * 集成 Prometheus 监控指标。
 * 多租户隔离：按租户分别执行补偿任务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSagaOrchestrator {

    private final FileSagaMapper sagaMapper;
    private final FileRemoteClient fileRemoteClient;
    private final OutboxService outboxService;
    private final FileMapper fileMapper;
    private final SagaMetrics sagaMetrics;
    private final TenantMapper tenantMapper;

    @Value("${saga.compensation.max-retries:5}")
    private int maxCompensationRetries;

    @Value("${saga.compensation.batch-size:50}")
    private int compensationBatchSize;

    @Value("${saga.dead-letter.enabled:true}")
    private boolean deadLetterEnabled;

    private static final String COMP_STEP_MINIO = "MINIO_DELETED";
    private static final String COMP_STEP_DB = "DB_ROLLBACK";

    /**
     * 执行文件上传 Saga。
     * 区块链存储失败时自动补偿删除 MinIO 数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public FileUploadResult executeUpload(FileUploadCommand cmd) {
        Timer.Sample timerSample = sagaMetrics.startSagaTimer();
        sagaMetrics.recordSagaStarted();

        FileSaga saga = startOrResumeSaga(cmd);

        try {
            Map<String, String> storedPaths = executeMinioUpload(saga, cmd);
            StoreFileResponse chainResult = executeBlockchainStore(saga, cmd, storedPaths);

            publishSuccessEvent(saga, cmd, chainResult);
            completeSaga(saga);

            sagaMetrics.recordSagaCompleted();
            return FileUploadResult.success(chainResult.getTransactionHash(), chainResult.getFileHash());

        } catch (Exception ex) {
            log.error("Saga 执行失败: requestId={}", cmd.getRequestId(), ex);
            handleFailure(saga, cmd, ex);
            throw ex;
        } finally {
            sagaMetrics.stopSagaTimer(timerSample);
        }
    }

    /**
     * 定时处理待补偿重试的 Saga。
     * 使用分布式锁防止多实例重复执行。
     * 按租户分别处理，确保多租户隔离。
     */
    @Scheduled(fixedDelayString = "${saga.compensation.poll-interval-ms:30000}")
    @DistributedLock(key = "saga:compensation:retry", leaseTime = 300)
    public void processRetriableSagas() {
        // 获取所有活跃租户
        List<Long> activeTenantIds = tenantMapper.selectActiveTenantIds();
        if (activeTenantIds == null || activeTenantIds.isEmpty()) {
            return;
        }

        for (Long tenantId : activeTenantIds) {
            try {
                TenantContext.runWithTenant(tenantId, () -> processRetriableSagasForTenant(tenantId));
            } catch (Exception e) {
                log.error("租户 {} Saga 补偿处理失败: {}", tenantId, e.getMessage(), e);
            }
        }
    }

    /**
     * 处理指定租户的待补偿 Saga
     */
    private void processRetriableSagasForTenant(Long tenantId) {
        List<FileSaga> pendingSagas = sagaMapper.selectPendingCompensation(tenantId, compensationBatchSize);
        if (pendingSagas.isEmpty()) {
            return;
        }

        log.info("租户 {} 发现 {} 个待重试补偿的 Saga", tenantId, pendingSagas.size());

        for (FileSaga saga : pendingSagas) {
            if (!saga.isRetryDue()) {
                continue;
            }

            try {
                retryCompensation(saga);
            } catch (Exception e) {
                log.error("租户 {} Saga 补偿重试失败: id={}", tenantId, saga.getId(), e);
            }
        }
    }

    /**
     * 重试单个 Saga 的补偿操作
     */
    @Transactional(rollbackFor = Exception.class)
    public void retryCompensation(FileSaga saga) {
        Timer.Sample timerSample = sagaMetrics.startCompensationTimer();
        log.info("开始重试 Saga 补偿: id={}, retryCount={}", saga.getId(), saga.getRetryCount());

        saga.markStatus(FileSagaStatus.COMPENSATING);
        sagaMapper.updateById(saga);

        try {
            compensate(saga, loadPayloadContext(saga));
            saga.markStatus(FileSagaStatus.COMPENSATED);
            sagaMetrics.recordSagaCompensated();
            log.info("Saga 补偿成功: id={}", saga.getId());
        } catch (Exception e) {
            saga.recordError(e);

            if (saga.isMaxRetriesExceeded(maxCompensationRetries)) {
                saga.markStatus(FileSagaStatus.FAILED);
                sagaMetrics.recordSagaFailed();
                log.error("Saga 补偿超过最大重试次数，标记为失败: id={}, retryCount={}",
                        saga.getId(), saga.getRetryCount());
                // 发布死信事件，便于人工介入处理
                publishDeadLetterEvent(saga, e);
            } else {
                saga.scheduleNextRetry()
                    .markStatus(FileSagaStatus.PENDING_COMPENSATION);
                log.warn("Saga 补偿失败，安排下次重试: id={}, nextRetryAt={}",
                        saga.getId(), saga.getNextRetryAt());
            }
        } finally {
            sagaMetrics.stopCompensationTimer(timerSample);
        }
        sagaMapper.updateById(saga);
    }

    private FileSaga startOrResumeSaga(FileUploadCommand cmd) {
        Long tenantId = TenantContext.requireTenantId();
        FileSaga existing = sagaMapper.selectByRequestId(cmd.getRequestId(), tenantId);
        if (existing != null) {
            if (existing.getFileId() == null && cmd.getFileId() != null) {
                existing.setFileId(cmd.getFileId());
                sagaMapper.updateById(existing);
            }
            if (FileSagaStatus.SUCCEEDED.name().equals(existing.getStatus())) {
                throw new GeneralException(ResultEnum.FAIL, "上传已完成");
            }
            if (FileSagaStatus.RUNNING.name().equals(existing.getStatus())) {
                log.info("恢复 Saga: requestId={}", cmd.getRequestId());
                return existing;
            }
        }

        FileSaga saga = new FileSaga()
                .setFileId(cmd.getFileId())
                .setRequestId(cmd.getRequestId())
                .setUserId(cmd.getUserId())
                .setFileName(cmd.getFileName())
                .setCurrentStep(FileSagaStep.PENDING.name())
                .setStatus(FileSagaStatus.RUNNING.name())
                .setRetryCount(0);
        sagaMapper.insert(saga);
        return saga;
    }

    private Map<String, String> executeMinioUpload(FileSaga saga, FileUploadCommand cmd) {
        SagaPayloadContext context = loadPayloadContext(saga);
        if (saga.reachedStep(FileSagaStep.MINIO_UPLOADED) && context.getStoredPaths() != null) {
            return new LinkedHashMap<>(context.getStoredPaths());
        }

        saga.advanceTo(FileSagaStep.MINIO_UPLOADING);
        sagaMapper.updateById(saga);

        Map<String, String> storedPaths = new LinkedHashMap<>();
        List<java.io.File> fileList = cmd.getFileList();
        List<String> fileHashList = cmd.getFileHashList();

        for (int i = 0; i < fileList.size(); i++) {
            java.io.File chunkFile = fileList.get(i);
            String chunkHash = fileHashList.get(i);

            byte[] chunkData;
            try (InputStream in = Files.newInputStream(chunkFile.toPath())) {
                chunkData = in.readAllBytes();
            } catch (IOException e) {
                log.error("读取文件块失败: index={}, path={}", i, chunkFile.getPath(), e);
                throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
            }

            Result<String> result = fileRemoteClient.storeFileChunk(chunkData, chunkHash);
            String logicalPath = ResultUtils.getData(result);
            if (logicalPath == null) {
                log.error("MinIO 上传失败: index={}, hash={}", i, chunkHash);
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR);
            }
            storedPaths.put(chunkHash, logicalPath);
        }

        context.setStoredPaths(storedPaths);
        context.resetCompensatedSteps();

        saga.advanceTo(FileSagaStep.MINIO_UPLOADED);
        persistPayload(saga, context);

        return new LinkedHashMap<>(storedPaths);
    }

    private StoreFileResponse executeBlockchainStore(FileSaga saga, FileUploadCommand cmd,
                                                 Map<String, String> storedPaths) {
        saga.advanceTo(FileSagaStep.CHAIN_STORING);
        sagaMapper.updateById(saga);

        String fileContent = JsonConverter.toJsonWithPretty(storedPaths);
        String userIdStr = String.valueOf(cmd.getUserId());

        Result<StoreFileResponse> result = fileRemoteClient.storeFileOnChain(StoreFileRequest.builder()
                .uploader(userIdStr)
                .fileName(cmd.getFileName())
                .param(cmd.getFileParam())
                .content(fileContent)
                .build());

        StoreFileResponse res = ResultUtils.getData(result);
        if (res == null || res.getTransactionHash() == null || res.getFileHash() == null) {
            throw new GeneralException(ResultEnum.FISCO_SERVICE_ERROR, "区块链存储返回无效结果");
        }

        return res;
    }

    private void completeSaga(FileSaga saga) {
        saga.advanceTo(FileSagaStep.COMPLETED)
            .markStatus(FileSagaStatus.SUCCEEDED);
        sagaMapper.updateById(saga);
    }

    private void publishSuccessEvent(FileSaga saga, FileUploadCommand cmd, StoreFileResponse chainResult) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("userId", cmd.getUserId());
        eventData.put("fileName", cmd.getFileName());
        eventData.put("transactionHash", chainResult.getTransactionHash());
        eventData.put("fileHash", chainResult.getFileHash());
        eventData.put("requestId", cmd.getRequestId());

        outboxService.appendEvent("FILE", saga.getFileId(), "file.stored",
                JsonConverter.toJson(eventData));
    }

    private void handleFailure(FileSaga saga, FileUploadCommand cmd, Exception ex) {
        saga.markStatus(FileSagaStatus.COMPENSATING).recordError(ex);
        sagaMapper.updateById(saga);

        try {
            compensate(saga, loadPayloadContext(saga));
            saga.markStatus(FileSagaStatus.COMPENSATED);
        } catch (Exception compEx) {
            log.error("补偿失败: saga={}", saga.getId(), compEx);
            // 使用指数退避安排下次重试
            saga.scheduleNextRetry()
                .markStatus(FileSagaStatus.PENDING_COMPENSATION);
            log.info("安排补偿重试: id={}, nextRetryAt={}", saga.getId(), saga.getNextRetryAt());
        }
        sagaMapper.updateById(saga);
    }

    /**
     * 补偿操作：删除已上传到 MinIO 的文件
     * 设计为幂等操作，重复调用不会产生副作用
     */
    private void compensate(FileSaga saga, SagaPayloadContext context) {
        // 检查是否已经补偿过（幂等性保证）
        if (FileSagaStatus.COMPENSATED.name().equals(saga.getStatus())) {
            log.info("Saga 已经补偿完成，跳过: sagaId={}", saga.getId());
            return;
        }

        boolean minioCompensated = compensateMinioUpload(saga, context);
        boolean dbCompensated = compensateDatabaseState(saga, context);

        if (!minioCompensated || !dbCompensated) {
            throw new RuntimeException("补偿未完全成功: minioCompensated=" + minioCompensated
                    + ", dbCompensated=" + dbCompensated);
        }
    }

    /**
     * 补偿 MinIO 上传
     */
    private boolean compensateMinioUpload(FileSaga saga, SagaPayloadContext context) {
        if (context.isStepDone(COMP_STEP_MINIO)) {
            log.info("MinIO 补偿已完成（幂等跳过）：sagaId={}", saga.getId());
            return true;
        }
        if (!saga.reachedStep(FileSagaStep.MINIO_UPLOADED)) {
            log.info("无需补偿 MinIO 数据（未到达 MINIO_UPLOADED 步骤）: sagaId={}", saga.getId());
            return true;
        }

        Map<String, String> storedPaths = context.getStoredPaths();
        if (storedPaths == null || storedPaths.isEmpty()) {
            log.info("存储路径为空，跳过 MinIO 补偿: sagaId={}", saga.getId());
            return true;
        }

        log.info("开始补偿 MinIO 上传: sagaId={}, 文件数量={}", saga.getId(), storedPaths.size());

        Result<Boolean> result = fileRemoteClient.deleteStorageFile(storedPaths);
        if (ResultUtils.isSuccess(result)) {
            log.info("MinIO 补偿完成: sagaId={}", saga.getId());
            context.markStepDone(COMP_STEP_MINIO);
            persistPayload(saga, context);
            return true;
        } else {
            // 区分"文件已不存在"（幂等成功）和真正的删除失败
            log.warn("MinIO 补偿结果: sagaId={}, result={}", saga.getId(),
                    result != null ? result.getCode() + ":" + result.getMessage() : "null");
            // 如果是文件不存在的错误，视为补偿成功（幂等）
            if (result != null && result.getCode() == ResultEnum.FILE_NOT_EXIST.getCode()) {
                context.markStepDone(COMP_STEP_MINIO);
                persistPayload(saga, context);
                return true;
            }
            return false;
        }
    }

    /**
     * 补偿数据库状态（如果需要）
     * 当前场景下数据库状态由 Saga 表自身管理，无需额外补偿
     * 此方法预留用于扩展，如需回滚业务表数据时使用
     */
    private boolean compensateDatabaseState(FileSaga saga, SagaPayloadContext context) {
        if (context.isStepDone(COMP_STEP_DB)) {
            log.debug("数据库状态补偿已完成（幂等跳过）：sagaId={}", saga.getId());
            return true;
        }

        if (saga.getFileId() == null) {
            log.debug("Saga 未关联业务文件记录，跳过数据库补偿: sagaId={}", saga.getId());
            context.markStepDone(COMP_STEP_DB);
            persistPayload(saga, context);
            return true;
        }

        try {
            File file = new File()
                    .setId(saga.getFileId())
                    .setStatus(FileUploadStatus.FAIL.getCode())
                    .setFileHash(null)
                    .setTransactionHash(null);
            int updated = fileMapper.updateById(file);
            log.info("数据库补偿结果: sagaId={}, fileId={}, updated={}", saga.getId(), saga.getFileId(), updated);
            context.markStepDone(COMP_STEP_DB);
            persistPayload(saga, context);
            return true;
        } catch (Exception e) {
            log.error("数据库状态补偿失败: sagaId={}, fileId={}, error={}", saga.getId(), saga.getFileId(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * 发布死信事件，用于补偿失败后的人工介入
     */
    private void publishDeadLetterEvent(FileSaga saga, Exception error) {
        if (!deadLetterEnabled) {
            log.warn("死信事件发布已禁用，跳过: sagaId={}", saga.getId());
            return;
        }

        try {
            Map<String, Object> deadLetterData = new HashMap<>();
            deadLetterData.put("sagaId", saga.getId());
            deadLetterData.put("requestId", saga.getRequestId());
            deadLetterData.put("userId", saga.getUserId());
            deadLetterData.put("fileName", saga.getFileName());
            deadLetterData.put("currentStep", saga.getCurrentStep());
            deadLetterData.put("retryCount", saga.getRetryCount());
            deadLetterData.put("lastError", error != null ? error.getMessage() : "unknown");
            deadLetterData.put("payload", saga.getPayload());
            deadLetterData.put("failedAt", System.currentTimeMillis());

            outboxService.appendEvent("SAGA_DEAD_LETTER", saga.getId(), "saga.compensation.failed",
                    JsonConverter.toJson(deadLetterData));

            log.warn("已发布 Saga 死信事件: sagaId={}, requestId={}", saga.getId(), saga.getRequestId());
        } catch (Exception e) {
            // 死信事件发布失败不应影响主流程
            log.error("发布死信事件失败: sagaId={}", saga.getId(), e);
        }
    }

    private SagaPayloadContext loadPayloadContext(FileSaga saga) {
        String payloadJson = saga.getPayload();
        SagaPayloadContext context = null;
        if (payloadJson != null && !payloadJson.isBlank()) {
            context = JsonConverter.parse(payloadJson, SagaPayloadContext.class);
        }
        if (context == null) {
            context = new SagaPayloadContext();
        }
        if (context.getStoredPaths() == null) {
            context.setStoredPaths(new LinkedHashMap<>());
        }
        if (context.getCompensatedSteps() == null) {
            context.setCompensatedSteps(new HashSet<>());
        }
        return context;
    }

    private void persistPayload(FileSaga saga, SagaPayloadContext context) {
        saga.setPayload(JsonConverter.toJsonWithPretty(context));
        sagaMapper.updateById(saga);
    }

    private static class SagaPayloadContext {
        private Map<String, String> storedPaths;
        private Set<String> compensatedSteps;

        public Map<String, String> getStoredPaths() {
            return storedPaths;
        }

        public void setStoredPaths(Map<String, String> storedPaths) {
            this.storedPaths = storedPaths;
        }

        public Set<String> getCompensatedSteps() {
            return compensatedSteps;
        }

        public void setCompensatedSteps(Set<String> compensatedSteps) {
            this.compensatedSteps = compensatedSteps;
        }

        public boolean isStepDone(String step) {
            return compensatedSteps != null && compensatedSteps.contains(step);
        }

        public void markStepDone(String step) {
            if (compensatedSteps == null) {
                compensatedSteps = new HashSet<>();
            }
            compensatedSteps.add(step);
        }

        public void resetCompensatedSteps() {
            if (compensatedSteps != null) {
                compensatedSteps.clear();
            }
        }
    }
}
