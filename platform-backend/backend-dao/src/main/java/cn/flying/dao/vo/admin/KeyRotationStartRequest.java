package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Idempotent manual dry-run or apply trigger.
 */
@Schema(description = "Manual automated key rotation trigger")
public record KeyRotationStartRequest(
        @NotBlank
        @Pattern(regexp = "DRY_RUN|APPLY")
        String mode,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9_-]{1,64}")
        String requestId
) {
}
