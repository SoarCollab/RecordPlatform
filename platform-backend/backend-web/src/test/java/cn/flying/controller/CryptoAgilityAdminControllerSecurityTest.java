package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.admin.CryptoAgilityDiagnosticsVO;
import cn.flying.dao.vo.admin.CryptoAgilityPolicyRequest;
import cn.flying.dao.vo.admin.CryptoAgilityPolicyVO;
import cn.flying.dao.vo.admin.CryptoProviderCapabilityVO;
import cn.flying.dao.vo.admin.CryptoSuiteCatalogEntryVO;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.key.CryptoSuiteIds;
import cn.flying.service.key.CryptoSuiteDiagnostic;
import cn.flying.service.key.CryptoSuitePolicyService;
import cn.flying.service.key.CryptoSuitePolicySnapshot;
import cn.flying.service.key.CryptoSuiteRegistry;
import cn.flying.service.key.CryptoSuiteStatus;
import cn.flying.service.key.CryptoSuiteType;
import cn.flying.service.key.KeyWrappingCapability;
import cn.flying.service.key.KeyWrappingProviderCapabilityDiagnostic;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import cn.flying.service.key.LocalKeyWrappingService;
import cn.flying.service.key.TenantCryptoPolicyService;
import cn.flying.service.proof.signed.ProofSigningProviderDiagnostic;
import cn.flying.service.proof.signed.ProofSigningProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the real admin method-security proxy and crypto diagnostics redaction surface.
 */
@SpringJUnitConfig(CryptoAgilityAdminControllerSecurityTest.MethodSecurityTestConfiguration.class)
class CryptoAgilityAdminControllerSecurityTest {

    private static final Long TENANT_ID = 23L;

    @Autowired
    private CryptoAgilityAdminController controller;
    @Autowired
    private TenantCryptoPolicyService tenantPolicyService;
    @Autowired
    private CryptoSuitePolicyService policyService;
    @Autowired
    private CryptoSuiteRegistry suiteRegistry;
    @Autowired
    private KeyWrappingProviderRegistry wrappingRegistry;
    @Autowired
    private ProofSigningProviderRegistry signingRegistry;

    /**
     * Establishes an authenticated principal while each test chooses the trusted admin role explicitly.
     */
    @BeforeEach
    void setUp() {
        reset(tenantPolicyService, policyService, suiteRegistry, wrappingRegistry, signingRegistry);
        TenantContext.setTenantId(TENANT_ID);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator", "n/a", List.of()));
    }

    /**
     * Clears thread-local authorization and tenant state after each proxied invocation.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /**
     * Proves a non-admin cannot inspect tenant crypto policy or provider topology.
     */
    @Test
    void shouldRejectNonAdminBeforeServiceInvocation() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());

        assertThatThrownBy(() -> controller.getPolicy(TENANT_ID))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(tenantPolicyService, policyService, suiteRegistry, wrappingRegistry, signingRegistry);
    }

    /**
     * Proves a tenant administrator receives only the selected non-secret policy identity.
     */
    @Test
    void shouldAllowAdminWithinTenantBoundary() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        CryptoSuitePolicySnapshot snapshot = snapshot();
        when(tenantPolicyService.getEffective(TENANT_ID)).thenReturn(snapshot);
        when(policyService.fingerprint(snapshot)).thenReturn("f".repeat(64));

        CryptoAgilityPolicyVO policy = controller.getPolicy(TENANT_ID).getData();

        assertThat(policy.policyVersion()).isEqualTo(3L);
        assertThat(policy.policyFingerprint()).hasSize(64);
        verify(tenantPolicyService).getEffective(TENANT_ID);
    }

    /**
     * Proves every public control-plane operation is audited and update bodies are never retained.
     */
    @Test
    void shouldAuditEveryOperationAndSuppressPolicyRequestBody() throws NoSuchMethodException {
        List<java.lang.reflect.Method> operations = Arrays.stream(
                        CryptoAgilityAdminController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(operations).hasSize(3);
        assertThat(operations).allSatisfy(method -> assertThat(method.getAnnotation(OperationLog.class))
                .as(method.getName() + " must be audited")
                .isNotNull());
        OperationLog savePolicy = CryptoAgilityAdminController.class
                .getDeclaredMethod(
                        "savePolicy", Long.class, Long.class, CryptoAgilityPolicyRequest.class)
                .getAnnotation(OperationLog.class);
        assertThat(savePolicy.saveRequestData()).isFalse();
    }

    /**
     * Proves response contracts cannot expose raw keys, wrapped blobs, tokens, or private material.
     */
    @Test
    void shouldKeepSecretFieldsOutOfResponseContracts() {
        Set<String> forbidden = Set.of(
                "keyId", "kmsKeyId", "targetKeyId", "encryptedDataKey", "wrappingIv",
                "token", "privateKey", "privateKeyPkcs8", "ciphertext", "recipientId");

        for (Class<?> type : List.of(
                CryptoAgilityPolicyVO.class,
                CryptoAgilityDiagnosticsVO.class,
                CryptoProviderCapabilityVO.class,
                CryptoSuiteCatalogEntryVO.class)) {
            Set<String> fields = Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(Collectors.toSet());
            assertThat(fields).doesNotContainAnyElementsOf(forbidden);
        }
    }

    /**
     * Proves diagnostics preserve real suite and provider readiness while excluding key identities.
     */
    @Test
    void shouldReturnSanitizedProviderAndSuiteDiagnostics() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        CryptoSuitePolicySnapshot snapshot = snapshot();
        when(tenantPolicyService.getEffective(TENANT_ID)).thenReturn(snapshot);
        when(policyService.fingerprint(snapshot)).thenReturn("f".repeat(64));
        when(suiteRegistry.diagnostics()).thenReturn(List.of(new CryptoSuiteDiagnostic(
                CryptoSuiteIds.ED25519_JWS_V1, CryptoSuiteType.SIGNATURE,
                CryptoSuiteIds.LOCAL_ED25519_PROVIDER, 1, CryptoSuiteStatus.ACTIVE,
                Instant.parse("2026-06-29T00:00:00Z"), null, null,
                Set.of("ED25519"), Set.of(CryptoSuiteIds.ED25519_JWS_V1), true, false)));
        when(wrappingRegistry.capabilityDiagnostics()).thenReturn(List.of(
                new KeyWrappingProviderCapabilityDiagnostic(
                        LocalKeyWrappingService.PROVIDER_ID, 1,
                        Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP),
                        Set.of(CryptoSuiteIds.LOCAL_WRAPPING), true, "configured")));
        when(signingRegistry.diagnostics()).thenReturn(List.of(new ProofSigningProviderDiagnostic(
                CryptoSuiteIds.LOCAL_ED25519_PROVIDER, 1, CryptoSuiteIds.ED25519_JWS_V1,
                Set.of(CryptoSuiteIds.SIGNED_PROOF_ZIP_V2), true, "configured")));

        CryptoAgilityDiagnosticsVO diagnostics = controller.diagnostics(TENANT_ID).getData();

        assertThat(diagnostics.signingProviders()).singleElement().satisfies(provider -> {
            assertThat(provider.available()).isTrue();
            assertThat(provider.suites()).containsExactlyInAnyOrder(
                    CryptoSuiteIds.ED25519_JWS_V1, CryptoSuiteIds.SIGNED_PROOF_ZIP_V2);
            assertThat(provider.toString()).doesNotContain("keyId", "privateKey", "token");
        });
        assertThat(diagnostics.wrappingProviders()).singleElement().satisfies(provider ->
                assertThat(provider.suites()).containsExactly(CryptoSuiteIds.LOCAL_WRAPPING));
    }

    /**
     * Builds one valid effective policy returned by the tenant service mock.
     */
    private CryptoSuitePolicySnapshot snapshot() {
        return new CryptoSuitePolicySnapshot(
                TENANT_ID,
                3L,
                CryptoSuiteIds.LEGACY_CHUNK_CHAIN,
                CryptoSuiteIds.UNSIGNED_V1,
                CryptoSuiteIds.NO_KEM_V1,
                CryptoSuiteIds.MERKLE_SHA256_V1,
                LocalKeyWrappingService.PROVIDER_ID,
                1,
                CryptoSuiteIds.ED25519_JWS_V1,
                CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                CryptoSuiteIds.LOCAL_ED25519_PROVIDER,
                1);
    }

    /**
     * Minimal context that enables the production custom method-security expression handler.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {

        /**
         * Registers the project's custom isAdmin expression before security advisors initialize.
         */
        @Bean
        static MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
            return new CustomMethodSecurityExpressionHandler(mock(PermissionService.class));
        }

        /**
         * Provides an isolated tenant policy service mock.
         */
        @Bean
        TenantCryptoPolicyService tenantCryptoPolicyService() {
            return mock(TenantCryptoPolicyService.class);
        }

        /**
         * Provides an isolated suite policy service mock.
         */
        @Bean
        CryptoSuitePolicyService cryptoSuitePolicyService() {
            return mock(CryptoSuitePolicyService.class);
        }

        /**
         * Provides an isolated suite registry mock.
         */
        @Bean
        CryptoSuiteRegistry cryptoSuiteRegistry() {
            return mock(CryptoSuiteRegistry.class);
        }

        /**
         * Provides an isolated wrapping registry mock.
         */
        @Bean
        KeyWrappingProviderRegistry keyWrappingProviderRegistry() {
            return mock(KeyWrappingProviderRegistry.class);
        }

        /**
         * Provides an isolated signing registry mock.
         */
        @Bean
        ProofSigningProviderRegistry proofSigningProviderRegistry() {
            return mock(ProofSigningProviderRegistry.class);
        }

        /**
         * Creates the real controller target that Spring wraps with method security.
         */
        @Bean
        CryptoAgilityAdminController cryptoAgilityAdminController(
                TenantCryptoPolicyService tenantPolicyService,
                CryptoSuitePolicyService policyService,
                CryptoSuiteRegistry suiteRegistry,
                KeyWrappingProviderRegistry wrappingRegistry,
                ProofSigningProviderRegistry signingRegistry) {
            return new CryptoAgilityAdminController(
                    tenantPolicyService, policyService, suiteRegistry, wrappingRegistry, signingRegistry);
        }
    }
}
