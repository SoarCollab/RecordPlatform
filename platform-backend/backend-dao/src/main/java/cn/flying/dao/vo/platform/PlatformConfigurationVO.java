package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Validated metadata and value from the safe global registry. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Validated metadata and value from the safe global registry.")
public record PlatformConfigurationVO(
        @Schema(description = "Code-owned configuration key", requiredMode = Schema.RequiredMode.REQUIRED)
        String key,
        @Schema(description = "Registry value type", allowableValues = {"INTEGER"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String type,
        @Schema(description = "Validated integer value; null when unavailable", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long value,
        @Schema(description = "Current configuration version; 0 when absent, null when the stored version is invalid", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long version,
        @Schema(description = "Inclusive minimum", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long minimum,
        @Schema(description = "Inclusive maximum", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long maximum,
        @Schema(description = "Code-owned description", requiredMode = Schema.RequiredMode.REQUIRED)
        String description,
        @Schema(description = "Fixed global scope", requiredMode = Schema.RequiredMode.REQUIRED)
        String scope,
        @Schema(description = "Persisted value source", requiredMode = Schema.RequiredMode.REQUIRED)
        String source,
        @Schema(description = "Whether online updates are supported", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean mutable,
        @Schema(description = "Whether an application restart is required", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean restartRequired,
        @Schema(description = "Value availability", allowableValues = {"AVAILABLE", "UNAVAILABLE"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String state) {
}
