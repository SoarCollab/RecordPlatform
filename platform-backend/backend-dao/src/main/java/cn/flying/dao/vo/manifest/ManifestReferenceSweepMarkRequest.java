package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Exact storage object identity proposed for reference-aware grace marking.
 */
@Schema(description = "Reference sweep mark request")
public record ManifestReferenceSweepMarkRequest(
        @NotBlank String storagePath,
        @NotBlank String cipherHash
) {
}
