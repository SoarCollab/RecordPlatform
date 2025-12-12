package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @program: RecordPlatform
 * @description: 文件上传状态内部类
 * @author: flyingcoding
 * @create: 2025-04-01 13:37
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "文件上传状态类")
public class FileUploadState {
    @Schema(description = "用户ID（上传会话所属用户）")
    private Long userId;
    @Schema(description = "客户端ID（标识唯一的客户端会话）")
    private String clientId;
    @Schema(description = "文件名")
    private String fileName;
    @Schema(description = "文件大小")
    private long fileSize;
    @Schema(description = "文件类型")
    private String contentType;
    @Schema(description = "分片大小")
    private int chunkSize;
    @Schema(description = "总分片数量")
    private int totalChunks;
    @Schema(description = "开始时间")
    private long startTime;
    @Schema(description = "已上传分片")
    private Set<Integer> uploadedChunks;
    @Schema(description = "已处理分片")
    private Set<Integer> processedChunks;
    @Schema(description = "分片哈希值")
    private Map<String, String> chunkHashes;
    @Schema(description = "密钥")
    private Map<Integer, byte[]> keys; // 存储加密密钥
    @Schema(description = "最后活动时间")
    private volatile long lastActivityTime;
    @Schema(description = "最后进度日志时间")
    private volatile long lastProgressLogTime;

    public FileUploadState(Long userId, String fileName, long fileSize, String contentType, String clientId, int chunkSize, int totalChunks) {
        this.userId = userId;
        this.clientId = clientId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        this.startTime = System.currentTimeMillis();
        this.lastActivityTime = this.startTime;
        this.lastProgressLogTime = 0;

        this.uploadedChunks = ConcurrentHashMap.newKeySet();
        this.processedChunks = ConcurrentHashMap.newKeySet();
        this.chunkHashes = new ConcurrentHashMap<>();
        this.keys = new ConcurrentHashMap<>(this.totalChunks > 0 ? this.totalChunks : 16);
    }

    public void updateLastActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
}