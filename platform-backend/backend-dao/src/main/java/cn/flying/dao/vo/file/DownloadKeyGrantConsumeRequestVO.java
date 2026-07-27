package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 消费短期下载密钥授权的请求。
 */
@Schema(description = "下载密钥授权消费请求")
public record DownloadKeyGrantConsumeRequestVO(
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{43}$")
        @Schema(description = "不透明授权引用", minLength = 43, maxLength = 43,
                pattern = "^[A-Za-z0-9_-]{43}$")
        String grantReference,
        @NotBlank
        @Size(min = 16, max = 128)
        @Pattern(regexp = "^[A-Za-z0-9_-]+$")
        @Schema(description = "创建授权时使用的浏览器内存会话标识",
                minLength = 16, maxLength = 128, pattern = "^[A-Za-z0-9_-]+$")
        String sessionId
) {
}
