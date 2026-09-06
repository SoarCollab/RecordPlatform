package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/** Bounded cross-tenant member metadata. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Bounded cross-tenant member metadata.")
public record PlatformUserVO(
        @Schema(description = "External user identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String id,
        @Schema(description = "External tenant identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String tenantId,
        @Schema(description = "Username", requiredMode = Schema.RequiredMode.REQUIRED)
        String username,
        @Schema(description = "Display nickname", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String nickname,
        @Schema(description = "Tenant role", allowableValues = {"user", "admin", "monitor"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String role,
        @Schema(description = "0 disabled; 1 active", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer status,
        @Schema(description = "Registration timestamp", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.util.Date registerTime,
        @Schema(description = "Last successful login timestamp", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        java.util.Date lastLoginTime) {
}
