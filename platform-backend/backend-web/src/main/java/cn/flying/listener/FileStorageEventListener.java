package cn.flying.listener;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.util.CommonUtils;
import cn.flying.dao.dto.File;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 文件存证事件监听器
 * 负责异步处理文件存证和 SSE 通知。
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

    // 注入文件处理线程池
    @Resource(name = "fileProcessTaskExecutor")
    private Executor fileProcessTaskExecutor;

    /**
     * 异步处理文件存证事件。
     *
     * @param event 文件存证事件
     */
    @EventListener
    @Async("fileProcessTaskExecutor") // 使用指定的线程池进行异步处理
    public void handleFileStorageEvent(FileStorageEvent event) {
        log.info("收到文件存证事件: tenantId={}, 用户={}, 文件名={}", event.getTenantId(), event.getUid(), event.getFileName());

        if (CommonUtils.isEmpty(event.getProcessedFiles())) {
            log.warn("文件存证事件中止：文件列表为空，用户={}, 文件名={}", event.getUid(), event.getFileName());
            notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, "文件分片为空");
            return;
        }

        // 异步执行文件存储和上链操作
        CompletableFuture.runAsync(() -> {
            try {
                File storedFile = fileService.storeFile(
                        event.getUid(),
                        event.getFileName(),
                        event.getProcessedFiles(),
                        event.getFileHashes(),
                        event.getFileParam()
                );

                if (storedFile != null) {
                    log.info("文件异步存储和上链成功: 用户={}, 文件名={}, 文件哈希={}",
                            event.getUid(), event.getFileName(), storedFile.getFileHash());
                    notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), storedFile.getFileHash(), true, null);
                } else {
                    log.error("文件异步存储或上链失败，返回结果为 null: 用户={}, 文件名={}",
                            event.getUid(), event.getFileName());
                    handleStorageFailure(event, "存储结果为空");
                }
            } catch (Exception e) {
                log.error("处理文件存证事件时发生异常: 用户={}, 文件名={}",
                        event.getUid(), event.getFileName(), e);
                handleStorageFailure(event, e.getMessage());
            }
        }, fileProcessTaskExecutor).exceptionally(ex -> {
            // 处理 CompletableFuture 内部的异常 (例如线程池拒绝任务等)
            log.error("文件存储 CompletableFuture 执行异常: 用户={}, 文件名={}, 错误: {}",
                    event.getUid(), event.getFileName(), ex.getMessage(), ex);
            handleStorageFailure(event, ex.getMessage());
            return null; // 返回 null 表示异常已处理
        });
    }

    /**
     * 统一处理存证失败的状态回写、缓存清理与 SSE 通知。
     *
     * @param event 文件存证事件
     * @param reason 失败原因
     */
    private void handleStorageFailure(FileStorageEvent event, String reason) {
        fileService.changeFileStatusByName(event.getUid(), event.getFileName(), FileUploadStatus.FAIL.getCode());

        // 存证失败时清理Redis上传状态
        try {
            redisStateManager.removeSessionByFileName(event.getUid(), event.getFileName());
            log.info("存证失败，已清理Redis状态: 用户={}, 文件名={}", event.getUid(), event.getFileName());
        } catch (Exception cleanupEx) {
            log.warn("存证失败时清理Redis状态异常: 用户={}, 文件名={}",
                    event.getUid(), event.getFileName(), cleanupEx);
        }

        notifyUser(event.getTenantId(), event.getUid(), event.getFileName(), null, false, reason);
    }

    /**
     * 异步通知用户存证结果（SSE）。
     *
     * @param tenantId 租户ID
     * @param uid 用户ID
     * @param fileName 文件名
     * @param fileHash 文件哈希
     * @param success 是否成功
     * @param reason 失败原因
     * @return 异步任务
     */
    private CompletableFuture<Void> notifyUser(Long tenantId,
                                               Long uid,
                                               String fileName,
                                               String fileHash,
                                               boolean success,
                                               String reason) {
        return CompletableFuture.runAsync(() -> {
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
        }, fileProcessTaskExecutor);
    }
}
