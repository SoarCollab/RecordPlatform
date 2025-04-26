package cn.flying.listener;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.service.FileService;
import cn.flying.common.event.FileStorageEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.CompletableFuture;

/**
 * 文件存证事件监听器
 * 负责异步处理文件存证和MinIO存储
 *
 * @author flyingcoding
 * @create 2025-04-05
 */
@Component
@Slf4j
public class FileStorageEventListener {

    @Resource
    private FileService fileService;

    /**
     * 异步处理文件存证事件
     * 接收文件上传完成事件，处理文件存证上链与MinIO集群存储
     */
    @EventListener
    @Async("fileProcessTaskExecutor") // 使用专用的异步线程池
    public void handleFileStorageEvent(FileStorageEvent event) {
        String uid = event.getUid();
        String fileName = event.getFileName();

        log.info("收到文件存证事件: 用户={}, 文件名={}", uid, fileName);


            // 调用文件服务进行存证和存储
            File fileRecord = fileService.storeFile(
                    uid,
                    fileName,
                    event.getProcessedFiles(),
                    event.getFileHashes(),
                    event.getFileParam()
            );

        if (fileRecord != null) {
            log.info("文件存证和存储成功: 用户={}, 文件名={}, 文件哈希={}",
                    uid, fileName, fileRecord.getFileHash());

            // 可以在这里添加额外的后处理逻辑，如通知用户等
            notifyUser(uid, fileName, fileRecord.getFileHash())
                    .exceptionally(ex -> {
                        log.error("通知用户失败: {}", ex.getMessage(), ex);
                        return null;
                    });
        } else {
            log.error("文件存证和存储失败: 用户={}, 文件名={}", uid, fileName);
            // 记录失败日志，并抛出异常
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR);
        }
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