package cn.flying.dao.vo.file;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @program: RecordPlatform
 * @description: 文件上传状态内部类
 * @author: 王贝强
 * @create: 2025-04-01 13:37
 */
@Getter
@Setter
public class FileUploadState {
    private final String sessionId;
    private final String clientId;
    private final String fileName;
    private final long fileSize;
    private final String contentType;
    private final int chunkSize;
    private final int totalChunks;
    private final long startTime;
    private final Set<Integer> uploadedChunks;
    private final Set<Integer> processedChunks;
    private final Map<String, String> chunkHashes;
    private final Map<Integer, byte[]> keys; // 存储加密密钥
    private volatile long lastActivityTime;
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
