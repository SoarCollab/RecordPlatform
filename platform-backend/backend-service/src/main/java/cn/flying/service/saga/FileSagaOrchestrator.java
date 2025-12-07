package cn.flying.service.saga;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.constant.ResultEnum;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * Saga orchestrator for file upload distributed transactions.
 * Coordinates MinIO upload + blockchain storage with compensation on failure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileSagaOrchestrator {

    private final FileSagaMapper sagaMapper;
    private final FileRemoteClient fileRemoteClient;
    private final OutboxService outboxService;

    private static final int MAX_COMPENSATION_RETRIES = 3;

    /**
     * Execute file upload with saga pattern.
     * If blockchain storage fails, automatically compensates by deleting MinIO data.
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
            log.error("Saga execution failed for requestId={}", cmd.getRequestId(), ex);
            handleFailure(saga, cmd, ex);
            throw ex;
        }
    }

    private FileSaga startOrResumeSaga(FileUploadCommand cmd) {
        FileSaga existing = sagaMapper.selectByRequestId(cmd.getRequestId());
        if (existing != null) {
            if (existing.getFileId() == null && cmd.getFileId() != null) {
                existing.setFileId(cmd.getFileId());
                sagaMapper.updateById(existing);
            }
            if (FileSagaStatus.SUCCEEDED.name().equals(existing.getStatus())) {
                throw new GeneralException(ResultEnum.FAIL, "Upload already completed");
            }
            if (FileSagaStatus.RUNNING.name().equals(existing.getStatus())) {
                log.info("Resuming saga for requestId={}", cmd.getRequestId());
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
                log.error("Failed to read chunk: index={}, path={}", i, chunkFile.getPath(), e);
                throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
            }

            Result<String> result = fileRemoteClient.storeFileChunk(chunkData, chunkHash);
            String logicalPath = ResultUtils.getData(result);
            if (logicalPath == null) {
                log.error("MinIO upload failed: index={}, hash={}", i, chunkHash);
                throw new GeneralException(ResultEnum.File_UPLOAD_ERROR);
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
            throw new GeneralException(ResultEnum.FISCO_SERVICE_ERROR, "Blockchain store returned invalid result");
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
            compensate(saga, cmd);
            saga.markStatus(FileSagaStatus.COMPENSATED);
        } catch (Exception compEx) {
            log.error("Compensation failed for saga={}", saga.getId(), compEx);
            if (saga.getRetryCount() >= MAX_COMPENSATION_RETRIES) {
                saga.markStatus(FileSagaStatus.FAILED);
            }
        }
        sagaMapper.updateById(saga);
    }

    private void compensate(FileSaga saga, FileUploadCommand cmd) {
        if (!saga.reachedStep(FileSagaStep.MINIO_UPLOADED)) {
            log.info("No MinIO data to compensate for saga={}", saga.getId());
            return;
        }

        log.info("Compensating MinIO uploads for saga={}", saga.getId());

        try {
            String payloadJson = saga.getPayload();
            if (payloadJson != null) {
                @SuppressWarnings("unchecked")
                Map<String, String> storedPaths = JsonConverter.parse(payloadJson, Map.class);
                if (storedPaths != null && !storedPaths.isEmpty()) {
                    fileRemoteClient.deleteStorageFile(storedPaths);
                }
            }
            log.info("MinIO compensation completed for saga={}", saga.getId());
        } catch (Exception e) {
            log.error("MinIO compensation failed for saga={}", saga.getId(), e);
            throw e;
        }
    }
}
