package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.TenantCryptoPolicy;
import cn.flying.dao.entity.TenantCryptoPolicyAudit;
import cn.flying.dao.mapper.TenantCryptoPolicyAuditMapper;
import cn.flying.dao.mapper.TenantCryptoPolicyMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises tenant isolation, optimistic updates, and sanitized policy audit evidence.
 */
@ExtendWith(MockitoExtension.class)
class TenantCryptoPolicyServiceTest {

    private static final Long TENANT_ID = 17L;
    private static final Long ACTOR_ID = 91L;

    @Mock
    private TenantCryptoPolicyMapper policyMapper;
    @Mock
    private TenantCryptoPolicyAuditMapper auditMapper;
    @Mock
    private TenantCryptoPolicyAuditService auditService;
    @Mock
    private CryptoSuitePolicyService policyService;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private TenantCryptoPolicyService service;

    /**
     * Establishes a trusted tenant and deterministic service dependencies for every policy decision.
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        FileKeyEnvelopeProperties envelopeProperties = new FileKeyEnvelopeProperties();
        envelopeProperties.setKeyVersion(3);
        service = new TenantCryptoPolicyService(
                policyMapper,
                auditMapper,
                auditService,
                policyService,
                envelopeProperties,
                snowflakeIdGenerator);
    }

    /**
     * Clears the trusted tenant so later test classes cannot inherit its authorization boundary.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Proves first creation persists a versioned tenant row and fingerprint-only audit evidence atomically.
     */
    @Test
    void shouldCreateTenantPolicyAndSanitizedAudit() {
        CryptoSuitePolicySnapshot defaults = snapshot(0L);
        when(policyMapper.selectTenantPolicyForUpdate(TENANT_ID)).thenReturn(null);
        when(policyService.currentPolicy()).thenReturn(defaults);
        when(policyService.fingerprint(any(CryptoSuitePolicySnapshot.class))).thenReturn("b".repeat(64));
        when(policyService.validateWrappingSelection(any(), eq(3))).thenReturn(wrappingReference());
        when(snowflakeIdGenerator.nextId()).thenReturn(101L, 102L);
        when(policyMapper.insert(any(TenantCryptoPolicy.class))).thenReturn(1);
        when(auditMapper.insert(any(TenantCryptoPolicyAudit.class))).thenReturn(1);

        CryptoSuitePolicySnapshot saved = service.save(TENANT_ID, ACTOR_ID, command(0L));

        assertThat(saved.policyVersion()).isEqualTo(1L);
        ArgumentCaptor<TenantCryptoPolicy> policy = ArgumentCaptor.forClass(TenantCryptoPolicy.class);
        verify(policyMapper).insert(policy.capture());
        assertThat(policy.getValue().getTenantId()).isEqualTo(TENANT_ID);
        assertThat(policy.getValue().getPolicyVersion()).isEqualTo(1L);
        ArgumentCaptor<TenantCryptoPolicyAudit> audit = ArgumentCaptor.forClass(TenantCryptoPolicyAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getOutcome()).isEqualTo("SUCCESS");
        assertThat(audit.getValue().getAction()).isEqualTo("CREATE");
        assertThat(audit.getValue().getFailureReason()).isNull();
        assertThat(audit.getValue().toString())
                .doesNotContain("key material", "ciphertext", "token", "private key");
    }

    /**
     * Proves an existing row increments exactly once while retaining the trusted tenant boundary.
     */
    @Test
    void shouldUpdateExistingPolicyWithExpectedVersion() {
        TenantCryptoPolicy current = entity(4L);
        when(policyMapper.selectTenantPolicyForUpdate(TENANT_ID)).thenReturn(current);
        when(policyService.validateWrappingSelection(any(), eq(3))).thenReturn(wrappingReference());
        when(policyService.fingerprint(any())).thenReturn("c".repeat(64));
        when(policyMapper.updateById(current)).thenReturn(1);
        when(auditMapper.insert(any(TenantCryptoPolicyAudit.class))).thenReturn(1);

        CryptoSuitePolicySnapshot saved = service.save(TENANT_ID, ACTOR_ID, command(4L));

        assertThat(saved.policyVersion()).isEqualTo(5L);
        assertThat(current.getPolicyVersion()).isEqualTo(5L);
        ArgumentCaptor<TenantCryptoPolicyAudit> audit = ArgumentCaptor.forClass(TenantCryptoPolicyAudit.class);
        verify(auditMapper).insert(audit.capture());
        assertThat(audit.getValue().getAction()).isEqualTo("UPDATE");
        verify(policyMapper, never()).insert(any(TenantCryptoPolicy.class));
    }

    /**
     * Proves a stale optimistic version is rejected before mutation and emits only a stable failure category.
     */
    @Test
    void shouldRejectStaleVersionAndAuditFailure() {
        TenantCryptoPolicy current = entity(5L);
        when(policyMapper.selectTenantPolicyForUpdate(TENANT_ID)).thenReturn(current);
        when(policyService.fingerprint(any())).thenReturn("d".repeat(64));
        when(policyService.fingerprint(isNull())).thenReturn(null);

        assertThatThrownBy(() -> service.save(TENANT_ID, ACTOR_ID, command(4L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getData())
                        .asString().contains(CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT.name()));

        verify(auditService).recordFailure(
                TENANT_ID, 5L, ACTOR_ID, "UPDATE", "d".repeat(64), null,
                CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT);
        verify(policyMapper, never()).updateById(any(TenantCryptoPolicy.class));
        verifyNoInteractions(auditMapper);
    }

    /**
     * Proves registry validation failures are persisted as closed reasons without saving the request.
     */
    @Test
    void shouldAuditUnknownSuiteWithoutPolicyMutation() {
        CryptoSuitePolicySnapshot defaults = snapshot(0L);
        when(policyMapper.selectTenantPolicyForUpdate(TENANT_ID)).thenReturn(null);
        when(policyService.currentPolicy()).thenReturn(defaults);
        when(policyService.fingerprint(any())).thenReturn("e".repeat(64));
        org.mockito.Mockito.doThrow(new GeneralException(
                        ResultEnum.PARAM_IS_INVALID,
                        Map.of("reason", CryptoSuiteFailureReason.UNKNOWN_SUITE.name())))
                .when(policyService).validateForWrite(any());

        assertThatThrownBy(() -> service.save(TENANT_ID, ACTOR_ID, command(0L)))
                .isInstanceOf(GeneralException.class);

        verify(auditService).recordFailure(
                TENANT_ID, 0L, ACTOR_ID, "CREATE", "e".repeat(64), "e".repeat(64),
                CryptoSuiteFailureReason.UNKNOWN_SUITE);
        verify(policyMapper, never()).insert(any(TenantCryptoPolicy.class));
        verifyNoInteractions(auditMapper);
    }

    /**
     * Proves nullable direct-service input is fingerprinted and audited without masking the registry failure.
     */
    @Test
    void shouldAuditNullableRejectedPolicyWithoutFingerprintFailure() {
        CryptoSuitePolicySnapshot defaults = snapshot(0L);
        TenantCryptoPolicyCommand invalid = new TenantCryptoPolicyCommand(
                0L, null, CryptoSuiteIds.UNSIGNED_V1, CryptoSuiteIds.NO_KEM_V1,
                CryptoSuiteIds.MERKLE_SHA256_V1, LocalKeyWrappingService.PROVIDER_ID, 1,
                CryptoSuiteIds.ED25519_JWS_V1, CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                CryptoSuiteIds.LOCAL_ED25519_PROVIDER, 1);
        when(policyMapper.selectTenantPolicyForUpdate(TENANT_ID)).thenReturn(null);
        when(policyService.currentPolicy()).thenReturn(defaults);
        when(policyService.fingerprint(any(CryptoSuitePolicySnapshot.class))).thenReturn("b".repeat(64));
        when(policyService.fingerprint(defaults)).thenReturn("f".repeat(64));
        org.mockito.Mockito.doThrow(new GeneralException(
                        ResultEnum.PARAM_IS_INVALID,
                        Map.of("reason", CryptoSuiteFailureReason.UNKNOWN_SUITE.name())))
                .when(policyService).validateForWrite(any());

        assertThatThrownBy(() -> service.save(TENANT_ID, ACTOR_ID, invalid))
                .isInstanceOf(GeneralException.class);

        verify(auditService).recordFailure(
                eq(TENANT_ID), eq(0L), eq(ACTOR_ID), eq("CREATE"), eq("f".repeat(64)),
                eq("b".repeat(64)),
                eq(CryptoSuiteFailureReason.UNKNOWN_SUITE));
    }

    /**
     * Proves the optimistic policy version cannot wrap from Long.MAX_VALUE to a negative value.
     */
    @Test
    void shouldRejectVersionOverflowBeforePolicyMutation() {
        TenantCryptoPolicy current = entity(Long.MAX_VALUE);
        when(policyMapper.selectTenantPolicyForUpdate(TENANT_ID)).thenReturn(current);
        when(policyService.fingerprint(any())).thenReturn("a".repeat(64));
        when(policyService.fingerprint(isNull())).thenReturn(null);

        assertThatThrownBy(() -> service.save(TENANT_ID, ACTOR_ID, command(Long.MAX_VALUE)))
                .isInstanceOf(GeneralException.class);

        verify(auditService).recordFailure(
                TENANT_ID, Long.MAX_VALUE, ACTOR_ID, "UPDATE", "a".repeat(64), null,
                CryptoSuiteFailureReason.POLICY_VERSION_CONFLICT);
        verify(policyMapper, never()).updateById(any(TenantCryptoPolicy.class));
    }

    /**
     * Proves an explicit tenant argument cannot cross the trusted request tenant boundary.
     */
    @Test
    void shouldRejectCrossTenantPolicyAccessBeforePersistence() {
        assertThatThrownBy(() -> service.save(99L, ACTOR_ID, command(0L)))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED));

        verifyNoInteractions(policyMapper, auditMapper, auditService, policyService);
    }

    /**
     * Builds a complete command containing only stable provider and suite identifiers.
     */
    private TenantCryptoPolicyCommand command(long expectedVersion) {
        return new TenantCryptoPolicyCommand(
                expectedVersion,
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
     * Builds an immutable effective policy for default and persistence mapping tests.
     */
    private CryptoSuitePolicySnapshot snapshot(long version) {
        return new CryptoSuitePolicySnapshot(
                TENANT_ID,
                version,
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
     * Builds one persisted policy fixture at the requested optimistic version.
     */
    private TenantCryptoPolicy entity(long version) {
        CryptoSuitePolicySnapshot snapshot = snapshot(version);
        return new TenantCryptoPolicy()
                .setId(500L)
                .setTenantId(TENANT_ID)
                .setContentEncryptionSuite(snapshot.contentEncryptionSuite())
                .setEnvelopeSignatureSuite(snapshot.envelopeSignatureSuite())
                .setKemSuite(snapshot.kemSuite())
                .setProofSuite(snapshot.proofSuite())
                .setWrappingProvider(snapshot.wrappingProvider())
                .setWrappingProviderContract(snapshot.wrappingProviderContract())
                .setSignedProofSignatureSuite(snapshot.signedProofSignatureSuite())
                .setSignedProofSuite(snapshot.signedProofSuite())
                .setSigningProvider(snapshot.signingProvider())
                .setSigningProviderContract(snapshot.signingProviderContract())
                .setPolicyVersion(version)
                .setDeleted(0);
    }

    /**
     * Builds a non-secret local wrapping reference returned after provider capability validation.
     */
    private WrappingKeyReference wrappingReference() {
        return new WrappingKeyReference(
                LocalKeyWrappingService.PROVIDER_ID,
                1,
                "redacted-by-api",
                "3",
                CryptoSuiteIds.LOCAL_WRAPPING,
                WrappingContext.LOCAL_AAD_V1);
    }
}
