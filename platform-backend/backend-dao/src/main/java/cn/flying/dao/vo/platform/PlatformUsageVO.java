package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.swagger.v3.oas.annotations.media.Schema;

/** Explicitly scoped tenant usage without file content. */
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "Explicitly scoped tenant usage without file content.")
public record PlatformUsageVO(
        @Schema(description = "External tenant identifier", requiredMode = Schema.RequiredMode.REQUIRED)
        String tenantId,
        @Schema(description = "Undeleted tenant member count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long users,
        @Schema(description = "Quota-eligible PREPARE and SUCCESS file count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long files,
        @Schema(description = "Quota-eligible logical storage bytes", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long logicalStorageBytes,
        @Schema(description = "Tenant business operation audit count, excluding platform audit", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long auditRecords,
        @Schema(description = "Completed tenant attestation batch count", requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "9007199254740991")
        @JsonSerialize(using = PlatformSafeLongSerializer.class)
        Long completedAttestations,
        @Schema(description = "Audit measurement scope", requiredMode = Schema.RequiredMode.REQUIRED)
        String auditScope,
        @Schema(description = "Attestation measurement scope", requiredMode = Schema.RequiredMode.REQUIRED)
        String attestationScope,
        @Schema(description = "Measurement state", allowableValues = {"AVAILABLE"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String state) {
}
