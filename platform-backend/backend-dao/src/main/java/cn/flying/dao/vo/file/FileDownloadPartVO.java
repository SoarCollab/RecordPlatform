package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Presigned download metadata for one ordered file chunk.
 */
@Schema(description = "文件分片预签名下载元数据")
public record FileDownloadPartVO(
        @Schema(description = "分片索引，从 0 开始")
        int index,
        @Schema(description = "分片字节数")
        long size,
        @Schema(description = "预签名下载 URL")
        String downloadUrl,
        @Schema(description = "URL 过期时间（Unix 秒）")
        long expiresAtEpochSeconds,
        @Schema(description = "分片 storagePath")
        String storagePath,
        @Schema(description = "manifest 归属的存储后端")
        String storageBackend,
        @Schema(description = "manifest 中的对象 ETag；历史 metadata 可为空")
        String etag,
        @Schema(description = "明文分片哈希")
        String plainHash,
        @Schema(description = "密文分片哈希")
        String cipherHash,
        @Schema(description = "校验算法")
        String checksumAlgorithm,
        @Schema(description = "分片明文字节数；历史 manifest 可为空")
        Long plainSize,
        @Schema(description = "分片认证 frame 数量；非 v2 可为空")
        Integer frameCount
) {

    /**
     * 保留旧下载分片构造调用，历史 manifest 不携带 framed 字段。
     */
    public FileDownloadPartVO(
            int index,
            long size,
            String downloadUrl,
            long expiresAtEpochSeconds,
            String storagePath,
            String plainHash,
            String cipherHash,
            String checksumAlgorithm
    ) {
        this(index, size, downloadUrl, expiresAtEpochSeconds, storagePath,
                null, null, plainHash, cipherHash, checksumAlgorithm, null, null);
    }

    /**
     * 保留新增明文尺寸/frame 字段的构造调用，同时允许旧 metadata 缺少存储证据。
     */
    public FileDownloadPartVO(
            int index,
            long size,
            String downloadUrl,
            long expiresAtEpochSeconds,
            String storagePath,
            String plainHash,
            String cipherHash,
            String checksumAlgorithm,
            Long plainSize,
            Integer frameCount
    ) {
        this(index, size, downloadUrl, expiresAtEpochSeconds, storagePath,
                null, null, plainHash, cipherHash, checksumAlgorithm, plainSize, frameCount);
    }
}
