package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
    @Min(0)
    @Schema(description = "分片索引，从 0 开始")
    private int index;

    @Min(1)
    @Schema(description = "分片字节数")
    private long size;

    @NotBlank
    @Schema(description = "明文分片哈希")
    private String plainHash;

    @NotBlank
    @Schema(description = "密文分片哈希；未启用前端加密时与 plainHash 相同")
    private String cipherHash;

    @Schema(description = "分片校验算法")
    private String checksumAlgorithm;
}
