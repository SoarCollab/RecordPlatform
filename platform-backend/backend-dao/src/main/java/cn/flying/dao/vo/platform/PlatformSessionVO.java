package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Authenticated platform identity and code-owned capabilities. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Authenticated platform identity and code-owned capabilities.")
public record PlatformSessionVO(
        @Schema(description = "External platform actor identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String actorId,
        @Schema(description = "Current principal name", requiredMode = Schema.RequiredMode.REQUIRED)
        String username,
        @Schema(description = "Fixed platform scope", allowableValues = {"platform"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String scope,
        @Schema(description = "Fixed system tenant identity", allowableValues = {"0"}, requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long systemTenantId,
        @Schema(description = "Deterministically ordered platform permissions", requiredMode = Schema.RequiredMode.REQUIRED)
        java.util.List<String> capabilities) {
}
