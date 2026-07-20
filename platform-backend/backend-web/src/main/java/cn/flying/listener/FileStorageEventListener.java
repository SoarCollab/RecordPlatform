package cn.flying.listener;

import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.CommonUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 文件存证事件监听器
 * 负责在事件发布调用栈内完成文件存证，并以尽力而为方式发送 SSE 通知。
 *
 * @author flyingcoding
 * @create 2025-04-05
 */
@Component
public class FileStorageEventListener {

    private static final Logger log = LoggerFactory.getLogger(FileStorageEventListener.class);

    @Resource
    private FileService fileService;

    @Resource
    private FileUploadRedisStateManager redisStateManager;

    @Resource
    private SseEmitterManager sseEmitterManager;

    /**
     * 同步处理文件存证事件，使发布者仅在文件最终化成功后推进上传完成态。
     *
     * @param event 文件存证事件
     */
    @EventListener
    public void handleFileStorageEvent(FileStorageEvent event) {
        log.info("收到文件存证事件: tenantId={}, 用户={}, 文件名={}", event.getTenantId(), event.getUid(), event.getFileName());

        if (CommonUtils.isEmpty(event.getProcessedFiles())) {
            log.warn("文件存证事件中止：文件列表为空，用户={}, 文件名={}", event.getUid(), event.getFileName());
            GeneralException failure = new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件分片为空");
            handleStorageFailureSafely(event, "文件分片为空", failure);
            throw failure;
        }

        try {
            if (event.getPreparedFileId() == null || CommonUtils.isEmpty(event.getClientId())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "文件存证事件缺少稳定 preparedFileId/clientId");
            }
            File storedFile = fileService.storeFile(
                    event.getUid(),
                    event.getPreparedFileId(),
                    event.getFileName(),
                    event.getProcessedFiles(),
                    event.getFileHashes(),
                    event.getFileParam(),
                    event.getClientId()
            );
            if (storedFile == null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "存储结果为空");
            }

            log.info("文件存储和上链成功: 用户={}, 文件名={}, 文件哈希={}",
                    event.getUid(), event.getFileName(), storedFile.getFileHash());
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(),
                    storedFile.getFileHash(), true, null);
        } catch (RuntimeException storageError) {
            log.error("处理文件存证事件时发生异常: 用户={}, 文件名={}",
                    event.getUid(), event.getFileName(), storageError);
            handleStorageFailureSafely(event, storageError.getMessage(), storageError);
            throw propagateStorageFailure(storageError);
        }
    }

    /**
     * 在不覆盖原始存证异常的前提下执行失败回写和通知。
     *
     * @param event 文件存证事件
     * @param reason 失败原因
     * @param storageError 原始存证异常
     */
    private void handleStorageFailureSafely(
            FileStorageEvent event,
            String reason,
            RuntimeException storageError
    ) {
        try {
            handleStorageFailure(event, reason);
        } catch (RuntimeException failureHandlingError) {
            storageError.addSuppressed(failureHandlingError);
            log.error("文件存证失败后的状态回写异常: 用户={}, 文件名={}",
                    event.getUid(), event.getFileName(), failureHandlingError);
        }
    }

    /**
     * 保留结构化业务异常；其他运行时异常统一映射后回传给同步事件发布者。
     *
     * @param storageError 原始存证异常
     * @return 可由上传完成调用栈感知的异常
     */
    private RuntimeException propagateStorageFailure(RuntimeException storageError) {
        if (storageError instanceof GeneralException) {
            return storageError;
        }
        GeneralException propagated = new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件存证失败");
        propagated.initCause(storageError);
        return propagated;
    }

    /**
     * 统一处理存证失败的状态回写、缓存清理与 SSE 通知。
     *
     * @param event 文件存证事件
     * @param reason 失败原因
     */
    private void handleStorageFailure(FileStorageEvent event, String reason) {
        if (event.getPreparedFileId() == null || CommonUtils.isEmpty(event.getClientId())) {
            log.error("存证失败事件缺少稳定 preparedFileId/clientId，保留现场禁止按文件名回退清理: 用户={}, 文件名={}",
                    event.getUid(), event.getFileName());
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
            return;
        }

        FileService.FinalizationRecoveryPhase recoveryPhase;
        try {
            recoveryPhase = fileService.getFinalizationRecoveryPhase(
                    event.getUid(), event.getPreparedFileId());
        } catch (Exception claimCheckError) {
            log.error("读取最终化 claim 失败，保留上传现场避免破坏未知链结果: 用户={}, preparedFileId={}",
                    event.getUid(), event.getPreparedFileId(), claimCheckError);
            recoveryPhase = FileService.FinalizationRecoveryPhase.UNKNOWN;
        }
        if (recoveryPhase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING
                || recoveryPhase == FileService.FinalizationRecoveryPhase.UNKNOWN) {
            log.error("存证已进入链结果不可重放阶段，保留 PREPARE 与上传状态等待人工对账: 用户={}, preparedFileId={}",
                    event.getUid(), event.getPreparedFileId());
            retainUploadStateForManualReconciliation(event);
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
            return;
        }
        if (recoveryPhase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED) {
            retainUploadStateForAutomaticRecovery(event);
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
            return;
        }
        if (recoveryPhase == FileService.FinalizationRecoveryPhase.SUCCESS) {
            log.info("存证目标已经提交 SUCCESS，保留上传会话供同步发布者补齐完成态: 用户={}, preparedFileId={}",
                    event.getUid(), event.getPreparedFileId());
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
            return;
        }

        boolean safeToDeleteUploadSession;
        try {
            safeToDeleteUploadSession =
                    fileService.markFileUploadFailed(event.getUid(), event.getPreparedFileId());
        } catch (RuntimeException failureMarkError) {
            log.error("文件失败状态无法事务性收敛，保留 Redis 会话和版本链供重试: 用户={}, preparedFileId={}",
                    event.getUid(), event.getPreparedFileId(), failureMarkError);
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
            return;
        }
        if (!safeToDeleteUploadSession) {
            log.warn("文件未安全进入 FAIL，保留 Redis 会话供 DB SUCCESS 或并发终态恢复: 用户={}, preparedFileId={}, clientId={}",
                    event.getUid(), event.getPreparedFileId(), event.getClientId());
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
            return;
        }

        // 同步发布者仍需严格删除 upload/processed 两类目录；监听器不得先移除唯一恢复入口。
        log.info("存证失败已安全收敛 DB FAIL，保留 Redis 会话交由同步发布者完成文件清理: 用户={}, fileId={}, clientId={}",
                event.getUid(), event.getPreparedFileId(), event.getClientId());

        notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
    }

    /**
     * 延长不可自动重放会话的诊断保留期，覆盖 publish 后 completed 状态原本仅五分钟的 TTL。
     *
     * @param event 文件存证事件
     */
    private void retainUploadStateForManualReconciliation(FileStorageEvent event) {
        try {
            FileUploadState state = redisStateManager.getState(event.getClientId());
            if (state != null) {
                redisStateManager.retainManualReconciliationState(
                        state,
                        "finalization_manual_reconciliation_required",
                        7 * 24 * 60 * 60L);
            }
        } catch (Exception retainError) {
            log.error("延长人工对账上传状态保留期失败: clientId={}, preparedFileId={}",
                    event.getClientId(), event.getPreparedFileId(), retainError);
        }
    }

    /**
     * ATTESTED 已持久化链结果时保留自动恢复状态，后续重试只推进 DB SUCCESS。
     */
    private void retainUploadStateForAutomaticRecovery(FileStorageEvent event) {
        try {
            FileUploadState state = redisStateManager.getState(event.getClientId());
            if (state != null) {
                state.setStatus("finalization_recovery_pending");
                redisStateManager.updateState(state);
            }
        } catch (RuntimeException retainError) {
            log.error("保留 ATTESTED 自动恢复状态失败: clientId={}, preparedFileId={}",
                    event.getClientId(), event.getPreparedFileId(), retainError);
        }
    }

    /**
     * 尽力而为通知用户存证结果，通知失败不得改变已经确定的存证终态。
     *
     * @param tenantId 租户ID
     * @param uid 用户ID
     * @param fileName 文件名
     * @param fileHash 文件哈希
     * @param success 是否成功
     * @param reason 失败原因
     */
    private void notifyUser(Long tenantId,
                            Long uid,
                            String fileName,
                            String fileHash,
                            boolean success,
                            String reason) {
        try {
            if (tenantId == null) {
                log.warn("发送文件存证SSE通知失败：tenantId为空，uid={}, fileName={}", uid, fileName);
                return;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("fileName", fileName);
            payload.put("fileHash", fileHash);
            payload.put("status", success ? "completed" : "failed");
            if (!success) {
                payload.put("reason", reason);
            }

            SseEvent event = SseEvent.of(
                    success ? SseEventType.FILE_RECORD_SUCCESS : SseEventType.FILE_RECORD_FAILED,
                    payload
            );
            sseEmitterManager.sendToUser(tenantId, uid, event);
        } catch (RuntimeException notificationError) {
            log.warn("发送文件存证SSE通知失败，但不改变存证结果: tenantId={}, uid={}, fileName={}",
                    tenantId, uid, fileName, notificationError);
        }
    }
}
