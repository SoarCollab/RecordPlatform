package cn.flying.dao.vo.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** Updates versioned tenant display metadata. */
@Schema(description = "Updates versioned tenant display metadata.")
public record UpdatePlatformTenantRequest(
        @Schema(description = "Tenant display name") @NotBlank @Size(max = 128) String name,
        @Schema(description = "Current tenant version") @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @Schema(description = "Required operation reason") @NotBlank @Size(max = 255) String reason) {
    /** Avoids including command payloads in incidental diagnostic output. */
    @Override
    public String toString() {
        return "UpdatePlatformTenantRequest[redacted]";
    }
}
