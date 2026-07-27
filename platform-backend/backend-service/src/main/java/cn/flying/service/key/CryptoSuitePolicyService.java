package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.entity.TenantCryptoPolicy;
import cn.flying.dao.mapper.TenantCryptoPolicyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * Validates configured crypto agility suite identifiers before metadata is persisted or exported.
 */
@Service
public class CryptoSuitePolicyService {

    private final FileKeyEnvelopeProperties properties;
    private final CryptoAgilityProperties agilityProperties;
    private final CryptoSuiteRegistry suiteRegistry;
    private final KeyWrappingProviderRegistry wrappingRegistry;
    private final TenantCryptoPolicyMapper policyMapper;

    /**
     * Creates the runtime policy service with the closed suite and provider registries.
     */
    public CryptoSuitePolicyService(FileKeyEnvelopeProperties properties,
                                    CryptoAgilityProperties agilityProperties,
                                    CryptoSuiteRegistry suiteRegistry,
                                    KeyWrappingProviderRegistry wrappingRegistry,
                                    TenantCryptoPolicyMapper policyMapper) {
        this.properties = properties;
        this.agilityProperties = agilityProperties;
        this.suiteRegistry = suiteRegistry;
        this.wrappingRegistry = wrappingRegistry;
        this.policyMapper = policyMapper;
    }

    /**
     * Returns validated current suite metadata for the supplied wrapping key version.
     */
    public CryptoSuiteMetadata currentMetadata(Integer keyVersion) {
        CryptoSuitePolicySnapshot snapshot = currentPolicy();
        return metadataFor(snapshot, keyVersion);
    }

    /**
     * Builds suite metadata from one already validated policy snapshot without re-reading mutable defaults.
     */
    public CryptoSuiteMetadata metadataFor(CryptoSuitePolicySnapshot snapshot, Integer keyVersion) {
        validateForWrite(snapshot);
        Integer resolvedKeyVersion = keyVersion != null ? keyVersion : properties.getKeyVersion();
        return new CryptoSuiteMetadata(
                snapshot.contentEncryptionSuite(),
                snapshot.envelopeSignatureSuite(),
                snapshot.kemSuite(),
                snapshot.proofSuite(),
                resolvedKeyVersion,
                properties.getDeprecatedAfter()
        );
    }

    /**
     * Resolves and validates the current tenant policy, falling back only to validated operator defaults.
     */
    public CryptoSuitePolicySnapshot currentPolicy() {
        Long tenantId = TenantContext.getTenantId();
        TenantCryptoPolicy policy = tenantId == null ? null : selectTenantPolicy(tenantId);
        CryptoSuitePolicySnapshot snapshot = policy == null
                ? defaultSnapshot(tenantId) : toSnapshot(policy);
        validateSnapshotForWrite(snapshot);
        return snapshot;
    }

    /**
     * Resolves the exact wrapping target selected by the effective tenant policy.
     */
    public WrappingKeyReference currentWrappingTarget(Integer logicalKeyVersion) {
        CryptoSuitePolicySnapshot snapshot = currentPolicy();
        return validateWrappingSelection(snapshot, logicalKeyVersion);
    }

    /**
     * Validates every configured active suite against supported and deprecated policy.
     */
    public void validateCurrentSuites() {
        validateSnapshotForWrite(defaultSnapshot(null));
        if (properties.getKeyVersion() == null || properties.getKeyVersion() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "keyVersion 必须为正整数");
        }
        if (properties.getDeprecatedAfter() != null && !properties.getDeprecatedAfter().isAfter(Instant.now())) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "当前密码套件已废弃");
        }
    }

    /**
     * 校验文件元数据显式声明的算法套件，允许受控回滚下的 v1/v2 allowlist。
     */
    public void validateAlgorithmSuite(String algorithmSuite) {
        validateSuite("algorithmSuite", algorithmSuite, properties.getSupportedAlgorithmSuites());
        suiteRegistry.requireForWrite(CryptoSuiteType.CONTENT_ENCRYPTION, algorithmSuite);
        if (properties.getDeprecatedAfter() != null && !properties.getDeprecatedAfter().isAfter(Instant.now())) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "当前密码套件已废弃");
        }
    }

    /**
     * Validates all persisted envelope suites and exact wrapping provider capabilities for historical reads.
     */
    public void validatePersistedEnvelopeForRead(FileKeyEnvelope envelope) {
        if (envelope == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    Map.of("reason", CryptoSuiteFailureReason.UNKNOWN_SUITE.name()));
        }
        suiteRegistry.requireForRead(CryptoSuiteType.CONTENT_ENCRYPTION, envelope.getAlgorithmSuite());
        suiteRegistry.requireForRead(CryptoSuiteType.SIGNATURE, envelope.getSignatureSuite());
        suiteRegistry.requireForRead(CryptoSuiteType.KEM, envelope.getKemSuite());
        suiteRegistry.requireForRead(CryptoSuiteType.PROOF, envelope.getProofSuite());
        suiteRegistry.requireForRead(CryptoSuiteType.KEY_WRAPPING,
                envelope.getWrappingAlgorithm(), envelope.getKmsProvider(), envelope.getProviderContractVersion());
        if (!wrappingRegistry.supports(envelope.getKmsProvider(), envelope.getProviderContractVersion(),
                KeyWrappingCapability.UNWRAP, envelope.getWrappingAlgorithm())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    Map.of("reason", CryptoSuiteFailureReason.CAPABILITY_MISMATCH.name()));
        }
    }

    /**
     * Validates that envelope rotation changes only the wrapping layer and never relabels content encryption.
     */
    public void validateRewrapTransition(FileKeyEnvelope source, WrappingKeyReference target) {
        validatePersistedEnvelopeForRead(source);
        suiteRegistry.requireForWrite(CryptoSuiteType.KEY_WRAPPING,
                target.wrappingAlgorithm(), target.providerId(), target.providerContractVersion());
        suiteRegistry.requireTransition(CryptoSuiteType.KEY_WRAPPING,
                source.getWrappingAlgorithm(), target.wrappingAlgorithm());
        suiteRegistry.requireTransition(CryptoSuiteType.CONTENT_ENCRYPTION,
                source.getAlgorithmSuite(), source.getAlgorithmSuite());
    }

    /**
     * Validates operator defaults during startup without accessing a tenant row.
     */
    public void validateDefaults() {
        validateCurrentSuites();
        CryptoSuitePolicySnapshot snapshot = defaultSnapshot(null);
        WrappingKeyReference target = wrappingRegistry.keyReference(
                snapshot.wrappingProvider(), snapshot.wrappingProviderContract(), properties.getKeyVersion())
                .requireValue();
        suiteRegistry.requireForWrite(CryptoSuiteType.KEY_WRAPPING,
                target.wrappingAlgorithm(), target.providerId(), target.providerContractVersion());
    }

    /**
     * Validates an administrator-supplied immutable policy snapshot for new writes.
     */
    public void validateForWrite(CryptoSuitePolicySnapshot snapshot) {
        if (snapshot == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    Map.of("reason", CryptoSuiteFailureReason.UNKNOWN_SUITE.name()));
        }
        validateSnapshotForWrite(snapshot);
    }

    /**
     * Validates and resolves the exact wrapping provider selected by a supplied policy snapshot.
     */
    public WrappingKeyReference validateWrappingSelection(CryptoSuitePolicySnapshot snapshot,
                                                          Integer logicalKeyVersion) {
        validateForWrite(snapshot);
        WrappingKeyReference reference = wrappingRegistry.keyReference(
                snapshot.wrappingProvider(), snapshot.wrappingProviderContract(), logicalKeyVersion)
                .requireValue();
        suiteRegistry.requireForWrite(CryptoSuiteType.KEY_WRAPPING,
                reference.wrappingAlgorithm(), reference.providerId(), reference.providerContractVersion());
        return reference;
    }

    /**
     * Returns a deterministic non-secret fingerprint for audit and diagnostics.
     */
    public String fingerprint(CryptoSuitePolicySnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        String canonical = String.join("\n",
                canonical(snapshot.contentEncryptionSuite()), canonical(snapshot.envelopeSignatureSuite()),
                canonical(snapshot.kemSuite()), canonical(snapshot.proofSuite()), canonical(snapshot.wrappingProvider()),
                String.valueOf(snapshot.wrappingProviderContract()), canonical(snapshot.signedProofSignatureSuite()),
                canonical(snapshot.signedProofSuite()), canonical(snapshot.signingProvider()),
                String.valueOf(snapshot.signingProviderContract()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new GeneralException(ResultEnum.FAIL,
                    Map.of("reason", CryptoSuiteFailureReason.CAPABILITY_MISMATCH.name()));
        }
    }

    /**
     * Rejects blank, unsupported, or explicitly deprecated suite identifiers.
     */
    private void validateSuite(String field, String value, Set<String> supportedValues) {
        if (!StringUtils.hasText(value)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, field + " 不能为空");
        }
        if (supportedValues == null || !supportedValues.contains(value)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "不支持的密码套件: " + field + "=" + value);
        }
        if (properties.getDeprecatedSuites() != null && properties.getDeprecatedSuites().contains(value)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "已废弃的密码套件: " + field + "=" + value);
        }
    }

    /**
     * Validates all suite and provider identities selected for new writes.
     */
    private void validateSnapshotForWrite(CryptoSuitePolicySnapshot snapshot) {
        validateSuite("algorithmSuite", snapshot.contentEncryptionSuite(),
                properties.getSupportedAlgorithmSuites());
        validateSuite("signatureSuite", snapshot.envelopeSignatureSuite(),
                properties.getSupportedSignatureSuites());
        validateSuite("kemSuite", snapshot.kemSuite(), properties.getSupportedKemSuites());
        validateSuite("proofSuite", snapshot.proofSuite(), properties.getSupportedProofSuites());
        suiteRegistry.requireForWrite(CryptoSuiteType.CONTENT_ENCRYPTION, snapshot.contentEncryptionSuite());
        suiteRegistry.requireForWrite(CryptoSuiteType.SIGNATURE, snapshot.envelopeSignatureSuite());
        suiteRegistry.requireForWrite(CryptoSuiteType.KEM, snapshot.kemSuite());
        suiteRegistry.requireForWrite(CryptoSuiteType.PROOF, snapshot.proofSuite());
        suiteRegistry.requireForWrite(CryptoSuiteType.SIGNATURE,
                snapshot.signedProofSignatureSuite(), snapshot.signingProvider(), snapshot.signingProviderContract());
        suiteRegistry.requireForWrite(CryptoSuiteType.PROOF,
                snapshot.signedProofSuite(), snapshot.signingProvider(), snapshot.signingProviderContract());
        if (properties.getDeprecatedAfter() != null && !properties.getDeprecatedAfter().isAfter(Instant.now())) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "当前密码套件已废弃");
        }
        if (snapshot.wrappingProviderContract() <= 0 || snapshot.signingProviderContract() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    Map.of("reason", CryptoSuiteFailureReason.PROVIDER_MISMATCH.name()));
        }
    }

    /**
     * Loads one tenant-isolated policy without bypassing the tenant interceptor.
     */
    private TenantCryptoPolicy selectTenantPolicy(Long tenantId) {
        return policyMapper.selectOne(new LambdaQueryWrapper<TenantCryptoPolicy>()
                .eq(TenantCryptoPolicy::getTenantId, tenantId)
                .eq(TenantCryptoPolicy::getDeleted, 0)
                .last("LIMIT 1"));
    }

    /**
     * Builds the validated operator-default policy used when a tenant has no override.
     */
    private CryptoSuitePolicySnapshot defaultSnapshot(Long tenantId) {
        return new CryptoSuitePolicySnapshot(
                tenantId, 0L, properties.getAlgorithmSuite(), properties.getSignatureSuite(),
                properties.getKemSuite(), properties.getProofSuite(), properties.getActiveProvider(),
                value(properties.getActiveProviderContractVersion()),
                agilityProperties.getSignedProofSignatureSuite(), agilityProperties.getSignedProofSuite(),
                agilityProperties.getSigningProvider(), value(agilityProperties.getSigningProviderContractVersion()));
    }

    /**
     * Converts a persisted tenant policy into one immutable runtime decision snapshot.
     */
    private CryptoSuitePolicySnapshot toSnapshot(TenantCryptoPolicy policy) {
        return new CryptoSuitePolicySnapshot(
                policy.getTenantId(), longValue(policy.getPolicyVersion()),
                policy.getContentEncryptionSuite(), policy.getEnvelopeSignatureSuite(),
                policy.getKemSuite(), policy.getProofSuite(), policy.getWrappingProvider(),
                value(policy.getWrappingProviderContract()), policy.getSignedProofSignatureSuite(),
                policy.getSignedProofSuite(), policy.getSigningProvider(),
                value(policy.getSigningProviderContract()));
    }

    private int value(Integer value) {
        return value == null ? 0 : value;
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * Canonicalizes rejected nullable policy fields without exposing arbitrary exception details.
     */
    private String canonical(String value) {
        return value == null ? "<null>" : value;
    }
}
