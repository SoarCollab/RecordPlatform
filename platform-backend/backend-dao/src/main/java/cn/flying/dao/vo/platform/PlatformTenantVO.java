package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Tenant metadata without business content. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Tenant metadata without business content.")
public record PlatformTenantVO(
        @Schema(description = "External tenant identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(description = "Immutable tenant code", requiredMode = Schema.RequiredMode.REQUIRED)
        String code,
        @Schema(description = "Tenant display name", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,
        @Schema(description = "0 disabled; 1 active", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer status,
        @Schema(description = "Optimistic tenant version", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long version,
        @Schema(description = "Sanitized disable reason", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String disabledReason,
        @Schema(description = "Disable timestamp", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.util.Date disabledAt,
        @Schema(description = "External actor who disabled this tenant", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String disabledBy,
        @Schema(description = "Creation timestamp", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.util.Date createTime,
        @Schema(description = "Last metadata change", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.util.Date updateTime,
        @Schema(description = "Current undeleted tenant member count; excludes platform accounts", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long memberCount) {
}
