package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Tenant administrator request for one optimistic crypto policy update.
 */
public record CryptoAgilityPolicyRequest(
        @NotNull @PositiveOrZero @Schema(description = "Current policy version; use zero to create")
        Long expectedVersion,
        @NotBlank @Size(max = 96) @Schema(description = "Content encryption suite for new envelopes")
        String contentEncryptionSuite,
        @NotBlank @Size(max = 96) @Schema(description = "Envelope signature suite")
        String envelopeSignatureSuite,
        @NotBlank @Size(max = 96) @Schema(description = "Envelope KEM suite")
        String kemSuite,
        @NotBlank @Size(max = 96) @Schema(description = "Envelope proof suite")
        String proofSuite,
        @NotBlank @Size(max = 64) @Schema(description = "Wrapping provider ID")
        String wrappingProvider,
        @NotNull @Positive @Schema(description = "Wrapping provider contract version")
        Integer wrappingProviderContract,
        @NotBlank @Size(max = 96) @Schema(description = "Signed-proof signature suite")
        String signedProofSignatureSuite,
        @NotBlank @Size(max = 96) @Schema(description = "Signed-proof format suite")
        String signedProofSuite,
        @NotBlank @Size(max = 64) @Schema(description = "Signing provider ID")
        String signingProvider,
        @NotNull @Positive @Schema(description = "Signing provider contract version")
        Integer signingProviderContract
) {
}
