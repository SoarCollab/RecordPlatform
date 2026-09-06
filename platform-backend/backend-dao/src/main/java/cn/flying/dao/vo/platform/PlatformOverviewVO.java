package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Platform metadata counts from successful database measurements. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Platform metadata counts from successful database measurements.")
public record PlatformOverviewVO(
        @Schema(description = "Undeleted tenant count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long tenants,
        @Schema(description = "Active tenant count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long activeTenants,
        @Schema(description = "Disabled tenant count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long disabledTenants,
        @Schema(description = "Undeleted tenant member count, excluding platform accounts", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long users,
        @Schema(description = "Active tenant member count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long activeUsers,
        @Schema(description = "Measurement state", allowableValues = {"AVAILABLE"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String state) {
}
