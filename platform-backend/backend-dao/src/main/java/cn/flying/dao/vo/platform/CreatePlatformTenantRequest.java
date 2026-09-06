package cn.flying.dao.vo.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** Creates a tenant without provisioning credentials. */
@Schema(description = "Creates a tenant without provisioning credentials.")
public record CreatePlatformTenantRequest(
        @Schema(description = "Immutable lower-case tenant code") @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{1,63}") String code,
        @Schema(description = "Tenant display name") @NotBlank @Size(max = 128) String name,
        @Schema(description = "Initial storage limit; null uses the existing application default", nullable = true) @Min(0) @Max(9007199254740991L) Long maxStorageBytes,
        @Schema(description = "Initial file limit; null uses the existing application default", nullable = true) @Min(0) @Max(9007199254740991L) Long maxFileCount,
        @Schema(description = "Required operation reason") @NotBlank @Size(max = 255) String reason) {
    /** Avoids including command payloads in incidental diagnostic output. */
    @Override
    public String toString() {
        return "CreatePlatformTenantRequest[redacted]";
    }
}
