package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * 短期下载密钥授权引用。
 */
@Schema(description = "短期、会话绑定的一次性下载密钥授权")
public record DownloadKeyGrantVO(
        @Schema(description = "不透明授权引用，仅允许通过 POST 请求体消费",
                minLength = 43, maxLength = 43, pattern = "^[A-Za-z0-9_-]{43}$")
        String reference,
        @Schema(description = "密钥交付协议版本", example = "grant-v1")
        String protocol,
        @Schema(description = "授权到期时间")
        Instant expiresAt
) {
}
