package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
/**
 * @program: RecordPlatform
 * @description: 文件上传状态内部类
 * @author flyingcoding
 * @create: 2025-04-01 13:37
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "文件上传状态类")
public class FileUploadState {
    @Schema(description = "租户ID（上传会话所属租户）")
    private Long tenantId;
    @Schema(description = "用户ID（上传会话所属用户）")
    private Long userId;
    @Schema(description = "客户端ID（标识唯一的客户端会话）")
    private String clientId;
    @Schema(description = "目标文件ID（版本上传时用于绑定既有 PREPARE 记录）")
    private Long targetFileId;
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
    @Schema(description = "上传状态: pending, uploading, processing, paused, completed")
    private volatile String status = "uploading";
    @Schema(description = "是否已完成 PREPARE 元数据落库（用于 completeUpload 幂等）")
    private volatile boolean prepareStored;
    @Schema(description = "是否为对象存储直传会话")
    private boolean directUpload;
    @Schema(description = "直传会话分片内部存储元数据")
    private List<DirectUploadPartState> directUploadParts = new ArrayList<>();
    @Schema(description = "直传完成后经后端校验的对象存储证据")
    private List<DirectUploadCompletedPartState> directCompletedParts = new ArrayList<>();
    @Schema(description = "当前上传会话稳定绑定的 PREPARE 文件ID")
    private Long preparedFileId;
    @Schema(description = "直传最终化阶段检查点")
    private String directFinalizationStage = "SESSION_CREATED";
    @Schema(description = "直传完成后的文件ID")
    private Long directFileId;
    @Schema(description = "直传完成后的文件哈希")
    private String directFileHash;
    @Schema(description = "原文件整体内容 SHA-256")
    private String contentHash;
    @Schema(description = "直传完成后的交易哈希")
    private String directTransactionHash;
    @Schema(description = "直传完成后的 manifest hash")
    private String directManifestHash;

    @Schema(description = "会话唯一标识（用于构建临时文件路径）")
    private String suid;

    @Schema(description = "上传临时目录路径（用于定时清理）")
    private String uploadTempPath;

    @Schema(description = "处理后临时目录路径（用于定时清理）")
    private String processedTempPath;

    @Schema(description = "清理重试次数（用于防止无限重试）")
    private Integer cleanupRetryCount = 0;

    @Schema(description = "上传恢复协议版本；为空表示升级前会话，必须失败关闭")
    private Integer recoverySchemaVersion;

    @Schema(description = "普通代理上传加密恢复版本")
    private Integer encryptionRecoveryVersion;

    @Schema(description = "普通代理上传对象格式版本：1=legacy，2=framed")
    private Integer encryptionFormatVersion;

    @Schema(description = "普通代理上传对象算法套件")
    private String encryptionAlgorithmSuite;

    @Schema(description = "普通代理上传文件级 DEK（仅受控 Redis 检查点）")
    private byte[] fileDataKey;

    @Schema(description = "普通代理上传文件级 nonce（仅受控 Redis 检查点）")
    private byte[] fileNonce;

    @Schema(description = "普通代理上传 framed 明文 frame 大小")
    private Integer framePlainSize;

    @Schema(description = "普通代理上传密钥派生算法")
    private String keyDerivation;

    @Schema(description = "普通代理上传 nonce 派生算法")
    private String nonceDerivation;

    @Schema(description = "普通代理上传 AAD 合同")
    private String aadSchema;

    @Schema(description = "普通代理上传 AEAD 标签大小")
    private Integer tagSize;

    @Schema(description = "普通代理上传 active manifest hash 检查点")
    private String manifestHash;

    public FileUploadState(Long userId, String fileName, long fileSize, String contentType, String clientId, int chunkSize, int totalChunks) {
        this(userId, fileName, fileSize, contentType, clientId, chunkSize, totalChunks, null);
    }

    /**
     * 构造上传会话状态，支持绑定目标文件ID。
     *
     * @param userId 用户ID
     * @param fileName 文件名
     * @param fileSize 文件大小
     * @param contentType 文件类型
     * @param clientId 客户端会话ID
     * @param chunkSize 分片大小
     * @param totalChunks 分片总数
     * @param targetFileId 目标文件ID
     */
    public FileUploadState(Long userId, String fileName, long fileSize, String contentType,
                           String clientId, int chunkSize, int totalChunks, Long targetFileId) {
        this.userId = userId;
        this.clientId = clientId;
        this.targetFileId = targetFileId;
        this.fileName = fileName;
        this.fileSize = fileSize;
        this.contentType = contentType;
        this.chunkSize = chunkSize;
        this.totalChunks = totalChunks;
        // 参数化构造只用于创建新会话；Jackson 读取旧 JSON 使用无参构造，缺字段仍保持 null。
        this.recoverySchemaVersion = 1;
        this.encryptionRecoveryVersion = 1;
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

    /**
     * Internal storage metadata for one direct-upload chunk.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "直传分片内部状态")
    public static class DirectUploadPartState {
        private int index;
        private long size;
        private String plainHash;
        private String cipherHash;
        private String checksumAlgorithm;
        private String uploadUrl;
        private long expiresAtEpochSeconds;
        private String storagePath;
        private String stagingObjectName;
        private String finalObjectName;
        private String nodeName;
    }

    /**
     * 保存对象存储完成响应中已经与可信上传计划逐字段核对的分片证据。
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "直传完成分片可信检查点")
    public static class DirectUploadCompletedPartState {
        private int index;
        private String storagePath;
        private long size;
        private String eTag;
        private String plainHash;
        private String cipherHash;
        private String checksumAlgorithm;
    }
}
