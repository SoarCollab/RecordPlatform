package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * 撤销已签发证明包的受限请求。
 */
@Schema(description = "撤销证明包请求")
public record RevokeProofRequest(
        @Size(max = 256)
        @Schema(description = "撤销原因，最多 256 字符")
        String reason
) {
}
