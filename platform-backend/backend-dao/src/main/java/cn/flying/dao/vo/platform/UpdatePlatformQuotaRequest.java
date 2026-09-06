package cn.flying.dao.vo.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

/** Updates a versioned tenant quota override. */
@Schema(description = "Updates a versioned tenant quota override.")
public record UpdatePlatformQuotaRequest(
        @Schema(description = "Storage limit in logical bytes") @NotNull @Min(0) @Max(9007199254740991L) Long maxStorageBytes,
        @Schema(description = "File count limit") @NotNull @Min(0) @Max(9007199254740991L) Long maxFileCount,
        @Schema(description = "Current override version; 0 when absent") @NotNull @Min(0) @Max(9007199254740991L) Long expectedVersion,
        @Schema(description = "Required operation reason") @NotBlank @Size(max = 255) String reason) {
    /** Avoids including command payloads in incidental diagnostic output. */
    @Override
    public String toString() {
        return "UpdatePlatformQuotaRequest[redacted]";
    }
}
