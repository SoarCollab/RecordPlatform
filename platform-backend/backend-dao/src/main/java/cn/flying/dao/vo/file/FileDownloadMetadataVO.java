package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Metadata required by the frontend to stream and decrypt a file from object storage.
 */
@Schema(description = "文件预签名分片下载元数据")
public record FileDownloadMetadataVO(
        @Schema(description = "外部文件 ID")
        String fileId,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "文件名")
        String fileName,
        @Schema(description = "文件大小（字节）")
        long fileSize,
        @Schema(description = "文件 MIME 类型")
        String contentType,
        @Schema(description = "仅 plaintext-v0 兼容模式返回的初始密钥；grant-v1 下为空")
        String initialKey,
        @Schema(description = "grant-v1 短期密钥授权；未加密文件或 plaintext-v0 下为空")
        DownloadKeyGrantVO keyGrant,
        @Schema(description = "manifest schema id")
        String manifestSchemaId,
        @Schema(description = "manifest hash")
        String manifestHash,
        @Schema(description = "不含密钥材料的 canonical manifest JSON")
        String canonicalManifestJson,
        @Schema(description = "Machine-readable active manifest lifecycle status")
        String manifestStatus,
        @Schema(description = "Manifest evidence classification")
        String manifestClassification,
        @Schema(description = "Manifest error/reason code; null for an active manifest")
        String manifestErrorCode,
        @Schema(description = "Whether a bounded typed legacy download path is explicitly allowed")
        boolean legacyDownloadAllowed,
        @Schema(description = "哈希算法")
        String hashAlgorithm,
        @Schema(description = "加密算法")
        String encryptionAlgorithm,
        @Schema(description = "存储后端")
        String storageBackend,
        @Schema(description = "分片大小")
        long chunkSize,
        @Schema(description = "分片总数")
        int totalChunks,
        @Schema(description = "版本化加密格式描述；历史 manifest 可为空")
        FileDownloadEncryptionVO encryption,
        @Schema(description = "有序分片下载 URL 列表")
        List<FileDownloadPartVO> parts
) {

    /**
     * 保留旧下载元数据构造调用，历史响应不携带版本化加密描述。
     */
    public FileDownloadMetadataVO(
            String fileId,
            String fileHash,
            String fileName,
            long fileSize,
            String contentType,
            String initialKey,
            String manifestSchemaId,
            String manifestHash,
            String hashAlgorithm,
            String encryptionAlgorithm,
            String storageBackend,
            long chunkSize,
            int totalChunks,
            List<FileDownloadPartVO> parts
    ) {
        this(fileId, fileHash, fileName, fileSize, contentType, initialKey, null,
                manifestSchemaId, manifestHash, null,
                "ACTIVE", "ALREADY_MANIFEST", null, false,
                hashAlgorithm, encryptionAlgorithm,
                storageBackend, chunkSize, totalChunks, null, parts);
    }
}
