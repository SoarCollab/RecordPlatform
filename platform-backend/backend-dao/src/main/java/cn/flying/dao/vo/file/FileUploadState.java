package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @program: RecordPlatform
 * @description: 文件上传状态内部类
 * @author: flyingcoding
 * @create: 2025-04-01 13:37
 */
@Getter
@Setter
@Schema(description = "文件上传状态类")
public class FileUploadState {
    @Schema(description = "会话ID")
    private final String sessionId;
    @Schema(description = "客户端ID")
    private final String clientId;
    @Schema(description = "文件名")
    private final String fileName;
    @Schema(description = "文件大小")
    private final long fileSize;
    @Schema(description = "文件类型")
    private final String contentType;
    @Schema(description = "分片大小")
    private final int chunkSize;
    @Schema(description = "总分片数量")
    private final int totalChunks;
    @Schema(description = "开始时间")
    private final long startTime;
    @Schema(description = "已上传分片")
    private final Set<Integer> uploadedChunks;
    @Schema(description = "已处理分片")
    private final Set<Integer> processedChunks;
    @Schema(description = "分片哈希值")
    private final Map<String, String> chunkHashes;
    @Schema(description = "密钥")
    private final Map<Integer, byte[]> keys; // 存储加密密钥
    @Schema(description = "最后活动时间")
    private volatile long lastActivityTime;
    @Schema(description = "最后进度日志时间")
    private volatile long lastProgressLogTime;

    private static final long MIN_CHUNK_FILE_SIZE_BYTES = 500 * 1024; // 500KB 小于此大小的文件不进行分片
    private static final int TARGET_CHUNK_COUNT = 10; // 目标分片数量 (用于计算chunkSize)

    public FileUploadState(String fileName, long fileSize, String contentType, String clientId) {
        this.sessionId = UUID.randomUUID().toString();
        this.clientId = clientId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.startTime = System.currentTimeMillis();
        this.lastActivityTime = this.startTime;
        this.lastProgressLogTime = 0;

        if (fileSize < MIN_CHUNK_FILE_SIZE_BYTES && fileSize > 0) {
            this.chunkSize = (int) fileSize;
            this.totalChunks = 1;
        } else if (fileSize <= 0) {
            this.chunkSize = 0;
            this.totalChunks = 0;
        } else {
            long calculatedChunkSize = (long) Math.ceil((double) fileSize / TARGET_CHUNK_COUNT);
            this.chunkSize = (int) Math.max(1024 * 1024, Math.min(Integer.MAX_VALUE, calculatedChunkSize));
            this.totalChunks = (int) Math.ceil((double) fileSize / this.chunkSize);
        }

        this.uploadedChunks = ConcurrentHashMap.newKeySet();
        this.processedChunks = ConcurrentHashMap.newKeySet();
        this.chunkHashes = new ConcurrentHashMap<>();
        this.keys = new ConcurrentHashMap<>(this.totalChunks > 0 ? this.totalChunks : 16);
    }

    public void updateLastActivity() {
        this.lastActivityTime = System.currentTimeMillis();
    }
    public void addUploadedChunk(int chunkNumber) {
        uploadedChunks.add(chunkNumber);
        updateLastActivity();
    }
    public void addProcessedChunk(int chunkNumber) {
        processedChunks.add(chunkNumber);
        updateLastActivity();
    }
    public void addChunkHash(String chunkKey, String hash) {
        chunkHashes.put(chunkKey, hash);
    }
}
