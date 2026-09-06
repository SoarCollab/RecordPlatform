package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** Allowlisted shared-component health without provider details. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Allowlisted shared-component health without provider details.")
public record PlatformHealthVO(
        @Schema(description = "Aggregate status", allowableValues = {"UP", "DOWN", "DEGRADED", "UNKNOWN"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "Allowlisted component name to normalized status", requiredMode = Schema.RequiredMode.REQUIRED)
        java.util.Map<String, String> components) {
}
