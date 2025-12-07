package cn.flying.service.saga;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.entity.FileSaga;
import cn.flying.dao.entity.FileSagaStatus;
import cn.flying.dao.entity.FileSagaStep;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.service.outbox.OutboxService;
import cn.flying.service.remote.FileRemoteClient;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSagaOrchestrator {

    private final FileSagaMapper sagaMapper;
    private final FileRemoteClient fileRemoteClient;
    private final OutboxService outboxService;

    @Value("${saga.compensation.max-retries:5}")
    private int maxCompensationRetries;

    @Value("${saga.compensation.batch-size:50}")
    private int compensationBatchSize;

    /**
     * 执行文件上传 Saga。
     * 区块链存储失败时自动补偿删除 MinIO 数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public FileUploadResult executeUpload(FileUploadCommand cmd) {
        FileSaga saga = startOrResumeSaga(cmd);

        try {
            Map<String, String> storedPaths = executeMinioUpload(saga, cmd);
            List<String> chainResult = executeBlockchainStore(saga, cmd, storedPaths);

            publishSuccessEvent(saga, cmd, chainResult);
            completeSaga(saga);

            return FileUploadResult.success(chainResult.get(0), chainResult.get(1));

        } catch (Exception ex) {
            log.error("Saga 执行失败: requestId={}", cmd.getRequestId(), ex);
            handleFailure(saga, cmd, ex);
            throw ex;
        }
    }

    /**
     * 定时处理待补偿重试的 Saga。
     * 使用分布式锁防止多实例重复执行。
     */
    @Scheduled(fixedDelayString = "${saga.compensation.poll-interval-ms:30000}")
    @DistributedLock(key = "saga:compensation:retry", leaseTime = 300)
    public void processRetriableSagas() {
        List<FileSaga> pendingSagas = sagaMapper.selectPendingCompensation(compensationBatchSize);
        if (pendingSagas.isEmpty()) {
            return;
        }

        log.info("发现 {} 个待重试补偿的 Saga", pendingSagas.size());

        for (FileSaga saga : pendingSagas) {
            if (!saga.isRetryDue()) {
                continue;
            }

            try {
                retryCompensation(saga);
            } catch (Exception e) {
                log.error("Saga 补偿重试失败: id={}", saga.getId(), e);
            }
        }
    }

    /**
     * 重试单个 Saga 的补偿操作
     */
    @Transactional(rollbackFor = Exception.class)
    public void retryCompensation(FileSaga saga) {
        log.info("开始重试 Saga 补偿: id={}, retryCount={}", saga.getId(), saga.getRetryCount());

        saga.markStatus(FileSagaStatus.COMPENSATING);
        sagaMapper.updateById(saga);

        try {
            compensate(saga);
            saga.markStatus(FileSagaStatus.COMPENSATED);
            log.info("Saga 补偿成功: id={}", saga.getId());
        } catch (Exception e) {
            saga.recordError(e);

            if (saga.isMaxRetriesExceeded(maxCompensationRetries)) {
                saga.markStatus(FileSagaStatus.FAILED);
                log.error("Saga 补偿超过最大重试次数，标记为失败: id={}, retryCount={}",
                        saga.getId(), saga.getRetryCount());
            } else {
                saga.scheduleNextRetry()
                    .markStatus(FileSagaStatus.PENDING_COMPENSATION);
                log.warn("Saga 补偿失败，安排下次重试: id={}, nextRetryAt={}",
                        saga.getId(), saga.getNextRetryAt());
            }
        }
        sagaMapper.updateById(saga);
    }

    private FileSaga startOrResumeSaga(FileUploadCommand cmd) {
        FileSaga existing = sagaMapper.selectByRequestId(cmd.getRequestId());
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
        if (saga.reachedStep(FileSagaStep.MINIO_UPLOADED)) {
            return JsonConverter.parse(saga.getPayload(), Map.class);
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

        saga.advanceTo(FileSagaStep.MINIO_UPLOADED)
            .setPayload(JsonConverter.toJsonWithPretty(storedPaths));
        sagaMapper.updateById(saga);

        return storedPaths;
    }

    private List<String> executeBlockchainStore(FileSaga saga, FileUploadCommand cmd,
                                                 Map<String, String> storedPaths) {
        saga.advanceTo(FileSagaStep.CHAIN_STORING);
        sagaMapper.updateById(saga);

        String fileContent = JsonConverter.toJsonWithPretty(storedPaths);
        String userIdStr = String.valueOf(cmd.getUserId());

        Result<List<String>> result = fileRemoteClient.storeFileOnChain(
                userIdStr, cmd.getFileName(), cmd.getFileParam(), fileContent);

        List<String> res = ResultUtils.getData(result);
        if (res == null || res.size() != 2) {
            throw new GeneralException(ResultEnum.FISCO_SERVICE_ERROR, "区块链存储返回无效结果");
        }

        return res;
    }

    private void completeSaga(FileSaga saga) {
        saga.advanceTo(FileSagaStep.COMPLETED)
            .markStatus(FileSagaStatus.SUCCEEDED);
        sagaMapper.updateById(saga);
    }

    private void publishSuccessEvent(FileSaga saga, FileUploadCommand cmd, List<String> chainResult) {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("userId", cmd.getUserId());
        eventData.put("fileName", cmd.getFileName());
        eventData.put("transactionHash", chainResult.get(0));
        eventData.put("fileHash", chainResult.get(1));
        eventData.put("requestId", cmd.getRequestId());

        outboxService.appendEvent("FILE", saga.getFileId(), "file.stored",
                JsonConverter.toJson(eventData));
    }

    private void handleFailure(FileSaga saga, FileUploadCommand cmd, Exception ex) {
        saga.markStatus(FileSagaStatus.COMPENSATING).recordError(ex);
        sagaMapper.updateById(saga);

        try {
            compensate(saga);
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
    private void compensate(FileSaga saga) {
        if (!saga.reachedStep(FileSagaStep.MINIO_UPLOADED)) {
            log.info("无需补偿 MinIO 数据（未到达 MINIO_UPLOADED 步骤）: sagaId={}", saga.getId());
            return;
        }

        String payloadJson = saga.getPayload();
        if (payloadJson == null || payloadJson.isBlank()) {
            log.info("Saga payload 为空，跳过补偿: sagaId={}", saga.getId());
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> storedPaths = JsonConverter.parse(payloadJson, Map.class);
        if (storedPaths == null || storedPaths.isEmpty()) {
            log.info("存储路径为空，跳过补偿: sagaId={}", saga.getId());
            return;
        }

        log.info("开始补偿 MinIO 上传: sagaId={}, 文件数量={}", saga.getId(), storedPaths.size());

        Result<Boolean> result = fileRemoteClient.deleteStorageFile(storedPaths);
        if (result != null && result.isSuccess()) {
            log.info("MinIO 补偿完成: sagaId={}", saga.getId());
        } else {
            // 区分"文件已不存在"（幂等成功）和真正的删除失败
            // 不抛出异常，因为文件可能在之前的补偿尝试中已被删除
            log.warn("MinIO 补偿结果: sagaId={}, result={}", saga.getId(),
                    result != null ? result.getCode() + ":" + result.getMessage() : "null");
        }
    }
}
