package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.admin.CryptoAgilityDiagnosticsVO;
import cn.flying.dao.vo.admin.CryptoAgilityPolicyRequest;
import cn.flying.dao.vo.admin.CryptoAgilityPolicyVO;
import cn.flying.dao.vo.admin.CryptoProviderCapabilityVO;
import cn.flying.dao.vo.admin.CryptoSuiteCatalogEntryVO;
import cn.flying.service.key.CryptoSuiteDiagnostic;
import cn.flying.service.key.CryptoSuitePolicyService;
import cn.flying.service.key.CryptoSuitePolicySnapshot;
import cn.flying.service.key.CryptoSuiteRegistry;
import cn.flying.service.key.KeyWrappingProviderCapabilityDiagnostic;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import cn.flying.service.key.TenantCryptoPolicyCommand;
import cn.flying.service.key.TenantCryptoPolicyService;
import cn.flying.service.proof.signed.ProofSigningProviderDiagnostic;
import cn.flying.service.proof.signed.ProofSigningProviderRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tenant administrator control plane for runtime cryptographic policy and sanitized diagnostics.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/admin/crypto-agility")
@PreAuthorize("isAdmin()")
@Tag(name = "Admin - Crypto Agility", description = "Runtime cryptographic suite governance")
public class CryptoAgilityAdminController {

    private final TenantCryptoPolicyService tenantPolicyService;
    private final CryptoSuitePolicyService policyService;
    private final CryptoSuiteRegistry suiteRegistry;
    private final KeyWrappingProviderRegistry wrappingProviderRegistry;
    private final ProofSigningProviderRegistry signingProviderRegistry;

    /**
     * Returns the effective tenant policy without provider key identifiers or key material.
     */
    @GetMapping("/policy")
    @Operation(summary = "Get the effective tenant crypto policy")
    @OperationLog(module = "crypto-agility", operationType = "query",
            description = "Get tenant crypto policy")
    public Result<CryptoAgilityPolicyVO> getPolicy(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        return Result.success(toPolicyVO(tenantPolicyService.getEffective(tenantId)));
    }

    /**
     * Creates or updates the tenant policy with explicit optimistic versioning.
     */
    @PutMapping("/policy")
    @Operation(summary = "Create or update the tenant crypto policy")
    @OperationLog(module = "crypto-agility", operationType = "update",
            description = "Update tenant crypto policy", saveRequestData = false)
    public Result<CryptoAgilityPolicyVO> savePolicy(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestBody @Valid CryptoAgilityPolicyRequest request
    ) {
        TenantCryptoPolicyCommand command = new TenantCryptoPolicyCommand(
                request.expectedVersion(), request.contentEncryptionSuite(),
                request.envelopeSignatureSuite(), request.kemSuite(), request.proofSuite(),
                request.wrappingProvider(), request.wrappingProviderContract(),
                request.signedProofSignatureSuite(), request.signedProofSuite(),
                request.signingProvider(), request.signingProviderContract());
        return Result.success(toPolicyVO(tenantPolicyService.save(tenantId, userId, command)));
    }

    /**
     * Returns effective suite lifecycle and provider capabilities without secret configuration values.
     */
    @GetMapping("/diagnostics")
    @Operation(summary = "Get sanitized runtime crypto diagnostics")
    @OperationLog(module = "crypto-agility", operationType = "query",
            description = "Get runtime crypto diagnostics")
    public Result<CryptoAgilityDiagnosticsVO> diagnostics(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId
    ) {
        CryptoSuitePolicySnapshot policy = tenantPolicyService.getEffective(tenantId);
        return Result.success(new CryptoAgilityDiagnosticsVO(
                toPolicyVO(policy),
                suiteRegistry.diagnostics().stream().map(this::toSuiteVO).toList(),
                wrappingProviderRegistry.capabilityDiagnostics().stream()
                        .map(this::toWrappingProviderVO).toList(),
                signingProviderRegistry.diagnostics().stream()
                        .map(this::toSigningProviderVO).toList()));
    }

    /**
     * Maps the immutable service snapshot to a public non-secret policy view.
     */
    private CryptoAgilityPolicyVO toPolicyVO(CryptoSuitePolicySnapshot policy) {
        return new CryptoAgilityPolicyVO(
                policy.policyVersion(), policy.contentEncryptionSuite(),
                policy.envelopeSignatureSuite(), policy.kemSuite(), policy.proofSuite(),
                policy.wrappingProvider(), policy.wrappingProviderContract(),
                policy.signedProofSignatureSuite(), policy.signedProofSuite(),
                policy.signingProvider(), policy.signingProviderContract(),
                policyService.fingerprint(policy));
    }

    /**
     * Maps one sanitized suite diagnostic to the OpenAPI response model.
     */
    private CryptoSuiteCatalogEntryVO toSuiteVO(CryptoSuiteDiagnostic suite) {
        return new CryptoSuiteCatalogEntryVO(
                suite.id(), suite.type().name(), suite.providerId(), suite.providerContractVersion(),
                suite.status().name(), suite.introducedAt(), suite.deprecatedAt(), suite.disabledAt(),
                suite.keyConstraints(), suite.compatibleWith(), suite.productionWriteAllowed(),
                suite.transitionRequiresReencryption());
    }

    /**
     * Maps one wrapping provider capability without key identifiers or remote addresses.
     */
    private CryptoProviderCapabilityVO toWrappingProviderVO(
            KeyWrappingProviderCapabilityDiagnostic provider) {
        Set<String> capabilities = provider.capabilities().stream().map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new CryptoProviderCapabilityVO(
                "KEY_WRAPPING", provider.providerId(), provider.contractVersion(), capabilities,
                provider.wrappingAlgorithms(), provider.available(), provider.configurationState());
    }

    /**
     * Maps one proof-signing provider capability without public or private key material.
     */
    private CryptoProviderCapabilityVO toSigningProviderVO(ProofSigningProviderDiagnostic provider) {
        Set<String> suites = new LinkedHashSet<>(provider.proofSuites());
        suites.add(provider.signatureSuite());
        return new CryptoProviderCapabilityVO(
                "PROOF_SIGNING", provider.providerId(), provider.contractVersion(),
                Set.of("SIGN", "VERIFY"), Set.copyOf(suites),
                provider.available(), provider.configurationState());
    }
}
