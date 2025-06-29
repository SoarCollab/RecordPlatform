package cn.flying.listener;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.dao.dto.File;
import cn.flying.service.FileService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.util.CommonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 文件存证事件监听器
 * 负责异步处理文件存证和MinIO存储
 *
 * @author 王贝强
 * @create 2025-04-05
 */
@Component
@Slf4j
public class FileStorageEventListener {

    @Resource
    private FileService fileService;

    @Resource
    private FileUploadRedisStateManager redisStateManager;

    // 注入文件处理线程池
    @Resource(name = "fileProcessTaskExecutor")
    private Executor fileProcessTaskExecutor;

    /**
     * 异步处理文件存证事件
     * 接收文件上传完成事件，处理文件存证上链与MinIO集群存储
     */
    @EventListener
    @Async("fileProcessTaskExecutor") // 使用指定的线程池进行异步处理
    public void handleFileStorageEvent(FileStorageEvent event) {
        log.info("收到文件存证事件: 用户={}, 文件名={}", event.getUid(), event.getFileName());

        if (CommonUtils.isEmpty(event.getProcessedFiles())) {
            log.warn("文件存证事件中止：文件列表为空，用户={}, 文件名={}", event.getUid(), event.getFileName());
            // 可以考虑更新文件状态为失败
            // fileService.updateFileStatus(event.getUid(), event.getOriginFileName(), FileUploadStatus.FAILED.getCode(), "文件列表为空");
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
                } else {
                    log.error("文件异步存储或上链失败，返回结果为 null: 用户={}, 文件名={}",
                            event.getUid(), event.getFileName());
                    // 根据业务逻辑决定是否需要更新文件状态为失败
                     fileService.changeFileStatusByName(event.getUid(), event.getFileName(), FileUploadStatus.FAIL.getCode());
                }
            } catch (Exception e) {
                log.error("处理文件存证事件时发生异常: 用户={}, 文件名={}",
                        event.getUid(), event.getFileName(), e);
                // 更新数据库状态为失败
                fileService.changeFileStatusByName(event.getUid(), event.getFileName(), FileUploadStatus.FAIL.getCode());

                // 存证失败时清理Redis上传状态
                try {
                    redisStateManager.removeSessionByFileName(event.getUid(), event.getFileName());
                    log.info("存证失败，已清理Redis状态: 用户={}, 文件名={}", event.getUid(), event.getFileName());
                } catch (Exception cleanupEx) {
                    log.warn("存证失败时清理Redis状态异常: 用户={}, 文件名={}",
                            event.getUid(), event.getFileName(), cleanupEx);
                }
            }
        }, fileProcessTaskExecutor).exceptionally(ex -> {
            // 处理 CompletableFuture 内部的异常 (例如线程池拒绝任务等)
            log.error("文件存储 CompletableFuture 执行异常: 用户={}, 文件名={}, 错误: {}",
                    event.getUid(), event.getFileName(), ex.getMessage(), ex);
            fileService.changeFileStatusByName(event.getUid(), event.getFileName(), FileUploadStatus.FAIL.getCode());

            // CompletableFuture异常时也清理Redis状态
            try {
                redisStateManager.removeSessionByFileName(event.getUid(), event.getFileName());
                log.info("CompletableFuture异常，已清理Redis状态: 用户={}, 文件名={}", event.getUid(), event.getFileName());
            } catch (Exception cleanupEx) {
                log.warn("CompletableFuture异常时清理Redis状态失败: 用户={}, 文件名={}",
                        event.getUid(), event.getFileName(), cleanupEx);
            }
            return null; // 返回 null 表示异常已处理
        });
    }

    /**
     * 异步通知用户存证完成
     * 可以通过消息系统、邮件、推送等方式通知用户
     */
    private CompletableFuture<Void> notifyUser(String uid, String fileName, String fileHash) {
        return CompletableFuture.runAsync(() -> {
            //todo 实现实际的用户通知逻辑
            log.info("通知用户文件存证完成: 用户={}, 文件名={}, 文件哈希={}", uid, fileName, fileHash);
        });
    }
}