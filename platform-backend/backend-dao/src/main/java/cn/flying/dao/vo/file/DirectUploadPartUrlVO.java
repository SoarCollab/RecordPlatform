package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Public presigned URL metadata for one direct-upload chunk.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "直传分片预签名 URL")
public class DirectUploadPartUrlVO {
    @Schema(description = "分片索引，从 0 开始", requiredMode = Schema.RequiredMode.REQUIRED)
    private int index;
    @Schema(description = "分片字节数", requiredMode = Schema.RequiredMode.REQUIRED)
    private long size;
    @Schema(description = "预签名上传 URL", requiredMode = Schema.RequiredMode.REQUIRED)
    private String uploadUrl;
    @Schema(description = "URL 过期时间（Unix 秒）", requiredMode = Schema.RequiredMode.REQUIRED)
    private long expiresAtEpochSeconds;
    @Schema(description = "完成后持久化的 chunk storagePath", requiredMode = Schema.RequiredMode.REQUIRED)
    private String storagePath;
    @Schema(description = "明文分片哈希", requiredMode = Schema.RequiredMode.REQUIRED)
    private String plainHash;
    @Schema(description = "密文分片哈希", requiredMode = Schema.RequiredMode.REQUIRED)
    private String cipherHash;
}
