package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Direct-upload chunk metadata declared by the frontend before requesting a presigned URL.
 */
@Getter
@Setter
@NoArgsConstructor
@Schema(description = "直传分片请求")
public class DirectUploadPartRequest {
    @NotNull
    @Min(0)
    @Schema(description = "分片索引，从 0 开始", requiredMode = Schema.RequiredMode.REQUIRED)
    private Integer index;

    @NotNull
    @Min(1)
    @Schema(description = "分片字节数", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long size;

    @NotBlank
    @Size(max = 71)
    @Pattern(regexp = "(?i)^sha256:[0-9a-f]{64}$", message = "plainHash 必须是规范 SHA-256 摘要")
    @Schema(description = "明文分片哈希")
    private String plainHash;

    @NotBlank
    @Size(max = 71)
    @Pattern(regexp = "(?i)^sha256:[0-9a-f]{64}$", message = "cipherHash 必须是规范 SHA-256 摘要")
    @Schema(description = "密文分片哈希；未启用前端加密时与 plainHash 相同")
    private String cipherHash;

    @Size(max = 16)
    @Pattern(regexp = "(?i)^(?:|SHA-256)$", message = "checksumAlgorithm 仅支持 SHA-256")
    @Schema(description = "分片校验算法")
    private String checksumAlgorithm;
}
