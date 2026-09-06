package cn.flying.dao.vo.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** Updates one code-owned integer configuration. */
@Schema(description = "Updates one code-owned integer configuration.")
public record UpdatePlatformConfigurationRequest(
        @Schema(description = "Integer value within registry bounds") @NotNull @Min(1) Long value,
        @Schema(description = "Current configuration version") @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @Schema(description = "Required operation reason") @NotBlank @Size(max = 255) String reason) {
    /** Avoids including command payloads in incidental diagnostic output. */
    @Override
    public String toString() {
        return "UpdatePlatformConfigurationRequest[redacted]";
    }
}
