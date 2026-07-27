package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.entity.TenantCryptoPolicy;
import cn.flying.dao.mapper.TenantCryptoPolicyMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证文件元数据显式算法套件的 allowlist 与废弃策略。
 */
@DisplayName("CryptoSuitePolicyService")
class CryptoSuitePolicyServiceTest {

    private static final String LEGACY_SUITE = "RP-AES256-GCM-CHUNK-CHAIN-V1";
    private static final String FRAMED_SUITE = "RP-AES256-GCM-FRAMED-V2";

    private FileKeyEnvelopeProperties properties;
    private CryptoAgilityProperties agilityProperties;
    private CryptoSuiteRegistry suiteRegistry;
    private KeyWrappingProviderRegistry wrappingRegistry;
    private TenantCryptoPolicyMapper policyMapper;
    private CryptoSuitePolicyService policyService;

    /**
     * 为每个用例创建独立的密码套件配置，避免废弃状态相互污染。
     */
    @BeforeEach
    void setUp() {
        properties = new FileKeyEnvelopeProperties();
        properties.setSupportedAlgorithmSuites(
                new LinkedHashSet<>(Set.of(LEGACY_SUITE, FRAMED_SUITE)));
        agilityProperties = new CryptoAgilityProperties();
        suiteRegistry = new CryptoSuiteRegistry(
                agilityProperties, new SimpleMeterRegistry());
        wrappingRegistry = mock(KeyWrappingProviderRegistry.class);
        policyMapper = mock(TenantCryptoPolicyMapper.class);
        policyService = new CryptoSuitePolicyService(
                properties, agilityProperties, suiteRegistry, wrappingRegistry, policyMapper);
    }

    /**
     * Clears the optional tenant context so default-policy tests remain isolated.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证 allowlist 内的 legacy 和 framed 套件均可用于受控 writer 回滚与升级。
     */
    @Test
    void validateAlgorithmSuite_shouldAcceptSupportedSuites() {
        assertDoesNotThrow(() -> policyService.validateAlgorithmSuite(LEGACY_SUITE));
        assertDoesNotThrow(() -> policyService.validateAlgorithmSuite(FRAMED_SUITE));
    }

    /**
     * 验证空值和 allowlist 外的套件返回参数错误。
     */
    @Test
    void validateAlgorithmSuite_shouldRejectBlankAndUnsupportedSuites() {
        GeneralException nullSuite = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite(null));
        GeneralException blankSuite = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite("  "));
        GeneralException unsupportedSuite = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite("UNKNOWN-SUITE"));

        assertThat(nullSuite.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(blankSuite.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(unsupportedSuite.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
    }

    /**
     * 验证显式废弃列表中的套件即使仍在 allowlist 中也必须被拒绝。
     */
    @Test
    void validateAlgorithmSuite_shouldRejectExplicitlyDeprecatedSuite() {
        properties.setDeprecatedSuites(new LinkedHashSet<>(Set.of(FRAMED_SUITE)));

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite(FRAMED_SUITE));

        assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(error.getData()).asString().contains("已废弃的密码套件");
    }

    /**
     * 验证全局废弃时间到期后，当前显式算法套件也停止接受。
     */
    @Test
    void validateAlgorithmSuite_shouldRejectSuiteAfterGlobalDeprecation() {
        properties.setDeprecatedAfter(Instant.now().minusSeconds(1));

        GeneralException error = assertThrows(
                GeneralException.class,
                () -> policyService.validateAlgorithmSuite(FRAMED_SUITE));

        assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PARAM_ERROR);
        assertThat(error.getData()).asString().contains("当前密码套件已废弃");
    }

    /**
     * Proves rejected nullable policy fields still produce deterministic non-secret audit fingerprints.
     */
    @Test
    void fingerprint_shouldHandleRejectedNullableFields() {
        CryptoSuitePolicySnapshot invalid = new CryptoSuitePolicySnapshot(
                1L, 1L, null, null, null, null, null, 0, null, null, null, 0);

        assertThat(policyService.fingerprint(invalid)).matches("[0-9a-f]{64}");
        assertThat(policyService.fingerprint(invalid)).isEqualTo(policyService.fingerprint(invalid));
        assertThat(policyService.fingerprint(null)).isNull();
    }

    /**
     * Proves default metadata and wrapping selection use one validated closed-catalog snapshot.
     */
    @Test
    void shouldResolveDefaultMetadataAndWrappingTarget() {
        WrappingKeyReference reference = localReference(3);
        when(wrappingRegistry.keyReference("local", 1, 3))
                .thenReturn(KeyWrappingResult.success(reference));
        when(wrappingRegistry.keyReference("local", 1, 1))
                .thenReturn(KeyWrappingResult.success(localReference(1)));

        CryptoSuiteMetadata metadata = policyService.currentMetadata(null);
        WrappingKeyReference selected = policyService.currentWrappingTarget(3);
        policyService.validateDefaults();

        assertThat(metadata.algorithmSuite()).isEqualTo(LEGACY_SUITE);
        assertThat(metadata.keyVersion()).isEqualTo(1);
        assertThat(selected).isEqualTo(reference);
        verify(wrappingRegistry).keyReference("local", 1, 3);
    }

    /**
     * Proves a tenant row, including its exact provider contracts, overrides operator defaults for new writes.
     */
    @Test
    void shouldLoadPersistedTenantPolicyWithoutDefaultDrift() {
        TenantContext.setTenantId(41L);
        TenantCryptoPolicy persisted = new TenantCryptoPolicy()
                .setTenantId(41L)
                .setPolicyVersion(7L)
                .setContentEncryptionSuite(FRAMED_SUITE)
                .setEnvelopeSignatureSuite(CryptoSuiteIds.UNSIGNED_V1)
                .setKemSuite(CryptoSuiteIds.NO_KEM_V1)
                .setProofSuite(CryptoSuiteIds.MERKLE_SHA256_V1)
                .setWrappingProvider(LocalKeyWrappingService.PROVIDER_ID)
                .setWrappingProviderContract(1)
                .setSignedProofSignatureSuite(CryptoSuiteIds.ED25519_JWS_V1)
                .setSignedProofSuite(CryptoSuiteIds.SIGNED_PROOF_ZIP_V2)
                .setSigningProvider(CryptoSuiteIds.LOCAL_ED25519_PROVIDER)
                .setSigningProviderContract(1)
                .setDeleted(0);
        when(policyMapper.selectOne(any())).thenReturn(persisted);

        CryptoSuitePolicySnapshot policy = policyService.currentPolicy();

        assertThat(policy.tenantId()).isEqualTo(41L);
        assertThat(policy.policyVersion()).isEqualTo(7L);
        assertThat(policy.contentEncryptionSuite()).isEqualTo(FRAMED_SUITE);
        assertThat(policy.signingProvider()).isEqualTo(CryptoSuiteIds.LOCAL_ED25519_PROVIDER);
    }

    /**
     * Proves historical envelope reads and wrapping-only rotation use persisted suite/provider identities.
     */
    @Test
    void shouldValidatePersistedEnvelopeAndCompatibleRewrapTransition() {
        FileKeyEnvelope envelope = persistedEnvelope();
        when(wrappingRegistry.supports("local", 1, KeyWrappingCapability.UNWRAP,
                CryptoSuiteIds.LOCAL_WRAPPING)).thenReturn(true);
        WrappingKeyReference target = new WrappingKeyReference(
                VaultTransitKeyWrappingProvider.PROVIDER_ID,
                1,
                "vault-key",
                "2",
                CryptoSuiteIds.VAULT_TRANSIT_WRAPPING,
                WrappingContext.EXTERNAL_CONTEXT_V2);

        assertDoesNotThrow(() -> policyService.validatePersistedEnvelopeForRead(envelope));
        assertDoesNotThrow(() -> policyService.validateRewrapTransition(envelope, target));
    }

    /**
     * Proves absent records, capability drift, and invalid provider contracts fail closed with stable reasons.
     */
    @Test
    void shouldRejectInvalidPersistedAndWriteSelections() {
        FileKeyEnvelope envelope = persistedEnvelope();
        when(wrappingRegistry.supports(eq("local"), eq(1), eq(KeyWrappingCapability.UNWRAP),
                eq(CryptoSuiteIds.LOCAL_WRAPPING))).thenReturn(false);

        assertThatThrownBy(() -> policyService.validatePersistedEnvelopeForRead(null))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
        assertThatThrownBy(() -> policyService.validatePersistedEnvelopeForRead(envelope))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getData())
                        .asString().contains(CryptoSuiteFailureReason.CAPABILITY_MISMATCH.name()));
        assertThatThrownBy(() -> policyService.validateForWrite(null))
                .isInstanceOf(GeneralException.class);
        CryptoSuitePolicySnapshot invalidContract = new CryptoSuitePolicySnapshot(
                1L, 1L, LEGACY_SUITE, CryptoSuiteIds.UNSIGNED_V1,
                CryptoSuiteIds.NO_KEM_V1, CryptoSuiteIds.MERKLE_SHA256_V1,
                "local", 0, CryptoSuiteIds.ED25519_JWS_V1,
                CryptoSuiteIds.SIGNED_PROOF_ZIP_V2, CryptoSuiteIds.LOCAL_ED25519_PROVIDER, 1);
        assertThatThrownBy(() -> policyService.validateForWrite(invalidContract))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getData())
                        .asString().contains(CryptoSuiteFailureReason.PROVIDER_MISMATCH.name()));
    }

    /**
     * Builds one complete historical local envelope for read and transition decisions.
     */
    private FileKeyEnvelope persistedEnvelope() {
        return new FileKeyEnvelope()
                .setAlgorithmSuite(LEGACY_SUITE)
                .setSignatureSuite(CryptoSuiteIds.UNSIGNED_V1)
                .setKemSuite(CryptoSuiteIds.NO_KEM_V1)
                .setProofSuite(CryptoSuiteIds.MERKLE_SHA256_V1)
                .setWrappingAlgorithm(CryptoSuiteIds.LOCAL_WRAPPING)
                .setKmsProvider(LocalKeyWrappingService.PROVIDER_ID)
                .setProviderContractVersion(1);
    }

    /**
     * Builds a stable local wrapping target for default-selection tests.
     */
    private WrappingKeyReference localReference(int logicalVersion) {
        return new WrappingKeyReference(
                LocalKeyWrappingService.PROVIDER_ID,
                1,
                "local-file-key-v1",
                String.valueOf(logicalVersion),
                CryptoSuiteIds.LOCAL_WRAPPING,
                WrappingContext.LOCAL_AAD_V1);
    }
}
