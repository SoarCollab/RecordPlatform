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
        @Schema(description = "明文分片哈希")
        String plainHash,
        @Schema(description = "密文分片哈希")
        String cipherHash,
        @Schema(description = "校验算法")
        String checksumAlgorithm
) {
}
