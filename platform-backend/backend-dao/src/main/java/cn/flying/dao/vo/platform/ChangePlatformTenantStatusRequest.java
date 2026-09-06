package cn.flying.dao.vo.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** Changes versioned tenant lifecycle status. */
@Schema(description = "Changes versioned tenant lifecycle status.")
public record ChangePlatformTenantStatusRequest(
        @Schema(description = "0 disabled; 1 active") @NotNull @Min(0) @Max(1) Integer status,
        @Schema(description = "Current tenant version") @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @Schema(description = "Required operation reason") @NotBlank @Size(max = 255) String reason) {
    /** Avoids including command payloads in incidental diagnostic output. */
    @Override
    public String toString() {
        return "ChangePlatformTenantStatusRequest[redacted]";
    }
}
