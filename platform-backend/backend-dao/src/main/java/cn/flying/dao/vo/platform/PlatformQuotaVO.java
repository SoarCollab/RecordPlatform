package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Effective tenant quota and writable override version. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Effective tenant quota and writable override version.")
public record PlatformQuotaVO(
        @Schema(description = "External tenant identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String tenantId,
        @Schema(description = "Effective storage limit", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long maxStorageBytes,
        @Schema(description = "Effective file count limit", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long maxFileCount,
        @Schema(description = "Current logical storage usage", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long usedStorageBytes,
        @Schema(description = "Current quota-eligible file count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long usedFileCount,
        @Schema(description = "Effective source", allowableValues = {"TENANT_OVERRIDE", "TENANT_DEFAULT", "APPLICATION_DEFAULT"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String source,
        @Schema(description = "Effective rollout-aware enforcement mode", allowableValues = {"SHADOW", "ENFORCE"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String enforcementMode,
        @Schema(description = "Writable override version; 0 when absent", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long version) {
}
