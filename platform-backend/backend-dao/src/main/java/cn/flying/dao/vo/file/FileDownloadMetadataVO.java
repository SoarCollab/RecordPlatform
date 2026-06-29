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
        @Schema(description = "初始密钥（最后一个分片的解密密钥，Base64 编码）")
        String initialKey,
        @Schema(description = "manifest schema id")
        String manifestSchemaId,
        @Schema(description = "manifest hash")
        String manifestHash,
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
        @Schema(description = "有序分片下载 URL 列表")
        List<FileDownloadPartVO> parts
) {
}
