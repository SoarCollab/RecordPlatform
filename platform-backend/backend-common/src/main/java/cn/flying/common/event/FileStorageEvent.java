package cn.flying.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.io.File;
import java.util.List;

/**
 * 文件存证事件，在文件上传完成后触发
 * 用于异步执行文件存证上链与 S3 兼容存储
 */
@Getter
public class FileStorageEvent extends ApplicationEvent {
    private final Long uid;              // 用户ID
    private final String fileName;         // 文件名
    private final String sessionId;        // 上传会话ID
    private final String clientId;         // 客户端ID
    private final List<File> processedFiles; // 处理后的文件列表
    private final List<String> fileHashes;   // 文件哈希值列表
    private final String fileParam;          // 文件参数

    public FileStorageEvent(Object source, Long uid, String fileName,
                            String sessionId, String clientId,
                            List<File> processedFiles, List<String> fileHashes,
                            String fileParam) {
        super(source);
        this.uid = uid;
        this.fileName = fileName;
        this.sessionId = sessionId;
        this.clientId = clientId;
        this.processedFiles = processedFiles;
        this.fileHashes = fileHashes;
        this.fileParam = fileParam;
    }
}
