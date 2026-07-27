package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Tenant administrator policy request with expected provider identity and bounded worker controls.
 */
@Schema(description = "Automated key rotation policy request")
public record KeyRotationPolicyRequest(
        @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,63}")
        @Schema(description = "Expected active provider ID", example = "vault-transit")
        String expectedProvider,
        @Min(1)
        @Schema(description = "Expected provider contract version", example = "1")
        Integer expectedProviderContract,
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,128}")
        @Schema(description = "Expected provider-native key version", example = "2")
        String expectedProviderKeyVersion,
        @NotNull @Min(1)
        Integer targetLogicalKeyVersion,
        @NotNull @Min(1) @Max(500)
        Integer batchSize,
        @NotNull @Min(1) @Max(60000)
        Integer maxItemsPerMinute,
        @NotNull
        Boolean scheduleEnabled,
        @Min(60) @Max(31536000)
        Long scheduleIntervalSeconds,
        @NotNull @Min(1) @Max(20)
        Integer maxAttempts,
        @NotNull @Min(1) @Max(86400)
        Long initialBackoffSeconds,
        @NotNull @Min(1) @Max(86400)
        Long maxBackoffSeconds,
        @NotNull @Min(10) @Max(3600)
        Long leaseSeconds,
        @NotNull @Min(0) @Max(31536000)
        Long gracePeriodSeconds
) {
}
