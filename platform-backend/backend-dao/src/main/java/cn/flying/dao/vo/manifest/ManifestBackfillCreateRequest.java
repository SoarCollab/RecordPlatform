package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Administrator request for a scan or frozen-snapshot derivative run.
 */
@Schema(description = "Manifest backfill run creation request")
public record ManifestBackfillCreateRequest(
        @NotBlank
        @Schema(description = "Run mode: SCAN, DRY_RUN, or APPLY", example = "SCAN")
        String mode,
        @Schema(description = "External source scan ID; required for DRY_RUN and APPLY")
        String snapshotRunId
) {
}
