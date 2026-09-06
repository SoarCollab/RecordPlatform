package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Sanitized system-owned platform operation evidence. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Sanitized system-owned platform operation evidence.")
public record PlatformAuditVO(
        @Schema(description = "External operation identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(description = "External platform actor identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String actorId,
        @Schema(description = "Code-owned operation name", requiredMode = Schema.RequiredMode.REQUIRED)
        String operation,
        @Schema(description = "External target tenant identifier", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String targetTenantId,
        @Schema(description = "Code-owned resource type", requiredMode = Schema.RequiredMode.REQUIRED)
        String resourceType,
        @Schema(description = "Typed external resource identifier or safe configuration key", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String resourceId,
        @Schema(description = "Sanitized reason", requiredMode = Schema.RequiredMode.REQUIRED)
        String reason,
        @Schema(description = "Bounded sanitized before summary", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String beforeSummary,
        @Schema(description = "Bounded sanitized after summary", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String afterSummary,
        @Schema(description = "Durable lifecycle", allowableValues = {"PROCESSING", "SUCCESS", "FAILURE"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "Recorded successful response", requiredMode = Schema.RequiredMode.REQUIRED,
                nullable = true)
        PlatformMutationVO result,
        @Schema(description = "Stable business error code on failure", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Integer errorCode,
        @Schema(description = "Validated trace identifier", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String traceId,
        @Schema(description = "Operation start timestamp", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.time.LocalDateTime startedAt,
        @Schema(description = "Operation completion timestamp", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.time.LocalDateTime completedAt,
        @Schema(description = "Duration in milliseconds", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long durationMs) {
}
