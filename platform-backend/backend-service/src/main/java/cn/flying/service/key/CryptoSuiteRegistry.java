package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed runtime catalog that separates write policy, historical read policy, and transition safety.
 */
@Service
public class CryptoSuiteRegistry {

    private static final String DECISION_METRIC = "app.crypto.suite.decision";
    private static final Instant P1_INTRODUCED = Instant.parse("2026-06-29T00:00:00Z");
    private static final Instant P3_INTRODUCED = Instant.parse("2026-07-27T00:00:00Z");

    private final CryptoAgilityProperties properties;
    private final MeterRegistry meterRegistry;
    private final Map<String, CryptoSuiteDefinition> definitions;

    /**
     * Builds and validates the immutable catalog before it can serve runtime decisions.
     */
    public CryptoSuiteRegistry(CryptoAgilityProperties properties, MeterRegistry meterRegistry) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.definitions = buildDefinitions();
        validateLifecycleOverrides();
    }

    /**
     * Requires a suite to be eligible for a new write under its exact type and provider identity.
     */
    public CryptoSuiteDefinition requireForWrite(CryptoSuiteType type,
                                                 String suiteId,
                                                 String providerId,
                                                 Integer providerContractVersion) {
        CryptoSuiteDefinition definition = requireIdentity(type, suiteId, providerId, providerContractVersion, false);
        CryptoSuiteStatus status = effectiveStatus(definition, Instant.now());
        CryptoSuiteFailureReason reason = switch (status) {
            case ACTIVE -> definition.productionWriteAllowed()
                    ? CryptoSuiteFailureReason.NONE : CryptoSuiteFailureReason.UNSUPPORTED;
            case DEPRECATED -> CryptoSuiteFailureReason.DEPRECATED_FOR_WRITE;
            case DISABLED -> CryptoSuiteFailureReason.DISABLED_FOR_READ;
            case UNSUPPORTED -> CryptoSuiteFailureReason.UNSUPPORTED;
            case EXPERIMENTAL -> experimentalWriteAllowed(definition)
                    ? CryptoSuiteFailureReason.NONE : CryptoSuiteFailureReason.EXPERIMENTAL_NOT_ALLOWED;
        };
        if (reason != CryptoSuiteFailureReason.NONE) {
            throw decisionFailure(type, "write", reason, false);
        }
        recordDecision(type, "write", CryptoSuiteFailureReason.NONE);
        return definition;
    }

    /**
     * Requires a write using the provider identity fixed by the closed catalog entry itself.
     */
    public CryptoSuiteDefinition requireForWrite(CryptoSuiteType type, String suiteId) {
        CryptoSuiteDefinition definition = definitions.get(suiteId);
        if (definition == null) {
            throw decisionFailure(type, "write", CryptoSuiteFailureReason.UNKNOWN_SUITE, false);
        }
        return requireForWrite(type, suiteId, definition.providerId(), definition.providerContractVersion());
    }

    /**
     * Requires a persisted suite to remain readable without consulting current write defaults.
     */
    public CryptoSuiteDefinition requireForRead(CryptoSuiteType type,
                                                String suiteId,
                                                String providerId,
                                                Integer providerContractVersion) {
        CryptoSuiteDefinition definition = requireIdentity(type, suiteId, providerId, providerContractVersion, true);
        CryptoSuiteStatus status = effectiveStatus(definition, Instant.now());
        CryptoSuiteFailureReason reason = switch (status) {
            case ACTIVE, DEPRECATED -> CryptoSuiteFailureReason.NONE;
            case DISABLED -> CryptoSuiteFailureReason.DISABLED_FOR_READ;
            case UNSUPPORTED -> CryptoSuiteFailureReason.UNSUPPORTED;
            case EXPERIMENTAL -> CryptoSuiteFailureReason.EXPERIMENTAL_NOT_ALLOWED;
        };
        if (reason != CryptoSuiteFailureReason.NONE) {
            throw decisionFailure(type, "read", reason, true);
        }
        recordDecision(type, "read", CryptoSuiteFailureReason.NONE);
        return definition;
    }

    /**
     * Requires a historical read using the provider identity fixed by the closed catalog entry itself.
     */
    public CryptoSuiteDefinition requireForRead(CryptoSuiteType type, String suiteId) {
        if (!StringUtils.hasText(suiteId)) {
            throw decisionFailure(type, "read", CryptoSuiteFailureReason.UNKNOWN_SUITE, true);
        }
        CryptoSuiteDefinition definition = definitions.get(suiteId);
        if (definition == null) {
            throw decisionFailure(type, "read", CryptoSuiteFailureReason.UNKNOWN_SUITE, true);
        }
        return requireForRead(type, suiteId, definition.providerId(), definition.providerContractVersion());
    }

    /**
     * Requires a transition to be explicitly compatible and identifies content re-encryption boundaries.
     */
    public void requireTransition(CryptoSuiteType type, String sourceSuiteId, String targetSuiteId) {
        CryptoSuiteDefinition source = definitions.get(sourceSuiteId);
        CryptoSuiteDefinition target = definitions.get(targetSuiteId);
        if (source == null || target == null) {
            throw decisionFailure(type, "transition", CryptoSuiteFailureReason.UNKNOWN_SUITE, false);
        }
        if (source.type() != type || target.type() != type) {
            throw decisionFailure(type, "transition", CryptoSuiteFailureReason.TYPE_MISMATCH, false);
        }
        if (!Objects.equals(source.id(), target.id()) && source.transitionRequiresReencryption()) {
            throw decisionFailure(type, "transition", CryptoSuiteFailureReason.REENCRYPT_REQUIRED, false);
        }
        if (!source.compatibleWith().contains(target.id())) {
            throw decisionFailure(type, "transition", CryptoSuiteFailureReason.DOWNGRADE_BLOCKED, false);
        }
        recordDecision(type, "transition", CryptoSuiteFailureReason.NONE);
    }

    /**
     * Returns the effective sanitized catalog ordered by type and stable identifier.
     */
    public List<CryptoSuiteDiagnostic> diagnostics() {
        Instant now = Instant.now();
        return definitions.values().stream()
                .sorted(Comparator.comparing(CryptoSuiteDefinition::type)
                        .thenComparing(CryptoSuiteDefinition::id))
                .map(definition -> new CryptoSuiteDiagnostic(
                        definition.id(), definition.type(), definition.providerId(),
                        definition.providerContractVersion(), effectiveStatus(definition, now),
                        definition.introducedAt(), effectiveDeprecatedAt(definition),
                        effectiveDisabledAt(definition), definition.keyConstraints(),
                        definition.compatibleWith(), definition.productionWriteAllowed(),
                        definition.transitionRequiresReencryption()))
                .toList();
    }

    /**
     * Returns whether a known catalog entry exists without making a read or write authorization decision.
     */
    public boolean contains(String suiteId) {
        return StringUtils.hasText(suiteId) && definitions.containsKey(suiteId);
    }

    /**
     * Resolves and validates the exact suite type and provider binding.
     */
    private CryptoSuiteDefinition requireIdentity(CryptoSuiteType type,
                                                  String suiteId,
                                                  String providerId,
                                                  Integer providerContractVersion,
                                                  boolean historicalRead) {
        if (type == null || !StringUtils.hasText(suiteId)) {
            throw decisionFailure(type, historicalRead ? "read" : "write",
                    CryptoSuiteFailureReason.UNKNOWN_SUITE, historicalRead);
        }
        CryptoSuiteDefinition definition = definitions.get(suiteId);
        if (definition == null) {
            throw decisionFailure(type, historicalRead ? "read" : "write",
                    CryptoSuiteFailureReason.UNKNOWN_SUITE, historicalRead);
        }
        if (definition.type() != type) {
            throw decisionFailure(type, historicalRead ? "read" : "write",
                    CryptoSuiteFailureReason.TYPE_MISMATCH, historicalRead);
        }
        if (!Objects.equals(definition.providerId(), providerId)
                || providerContractVersion == null
                || definition.providerContractVersion() != providerContractVersion) {
            throw decisionFailure(type, historicalRead ? "read" : "write",
                    CryptoSuiteFailureReason.PROVIDER_MISMATCH, historicalRead);
        }
        return definition;
    }

    /**
     * Computes the effective lifecycle after applying only known operator overrides and time windows.
     */
    private CryptoSuiteStatus effectiveStatus(CryptoSuiteDefinition definition, Instant now) {
        CryptoAgilityProperties.Lifecycle override = properties.getSuiteLifecycle().get(definition.id());
        CryptoSuiteStatus status = override != null && override.getStatus() != null
                ? override.getStatus() : definition.status();
        Instant disabledAt = effectiveDisabledAt(definition);
        Instant deprecatedAt = effectiveDeprecatedAt(definition);
        if (disabledAt != null && !disabledAt.isAfter(now)) {
            return CryptoSuiteStatus.DISABLED;
        }
        if (deprecatedAt != null && !deprecatedAt.isAfter(now)
                && status == CryptoSuiteStatus.ACTIVE) {
            return CryptoSuiteStatus.DEPRECATED;
        }
        return status;
    }

    /**
     * Returns the configured or built-in deprecation instant.
     */
    private Instant effectiveDeprecatedAt(CryptoSuiteDefinition definition) {
        CryptoAgilityProperties.Lifecycle override = properties.getSuiteLifecycle().get(definition.id());
        return override != null && override.getDeprecatedAt() != null
                ? override.getDeprecatedAt() : definition.deprecatedAt();
    }

    /**
     * Returns the configured or built-in disable instant.
     */
    private Instant effectiveDisabledAt(CryptoSuiteDefinition definition) {
        CryptoAgilityProperties.Lifecycle override = properties.getSuiteLifecycle().get(definition.id());
        return override != null && override.getDisabledAt() != null
                ? override.getDisabledAt() : definition.disabledAt();
    }

    /**
     * Allows experimental writes only in an explicitly non-production development configuration.
     */
    private boolean experimentalWriteAllowed(CryptoSuiteDefinition definition) {
        return !properties.isProductionMode()
                && properties.isAllowExperimentalWrites()
                && definition.productionWriteAllowed();
    }

    /**
     * Validates that lifecycle overrides only tighten known catalog entries with ordered timestamps.
     */
    private void validateLifecycleOverrides() {
        for (Map.Entry<String, CryptoAgilityProperties.Lifecycle> entry
                : properties.getSuiteLifecycle().entrySet()) {
            CryptoSuiteDefinition definition = definitions.get(entry.getKey());
            CryptoAgilityProperties.Lifecycle lifecycle = entry.getValue();
            if (definition == null || lifecycle == null
                    || widensLifecycle(definition.status(), lifecycle.getStatus())
                    || (lifecycle.getDeprecatedAt() != null
                    && lifecycle.getDeprecatedAt().isBefore(definition.introducedAt()))
                    || (lifecycle.getDisabledAt() != null
                    && lifecycle.getDisabledAt().isBefore(definition.introducedAt()))
                    || (lifecycle.getDeprecatedAt() != null && lifecycle.getDisabledAt() != null
                    && lifecycle.getDisabledAt().isBefore(lifecycle.getDeprecatedAt()))) {
                throw decisionFailure(null, "startup", CryptoSuiteFailureReason.INVALID_LIFECYCLE, false);
            }
        }
    }

    /**
     * Returns whether an operator status override would widen the built-in implementation contract.
     */
    private boolean widensLifecycle(CryptoSuiteStatus baseline, CryptoSuiteStatus override) {
        if (override == null || override == baseline) {
            return false;
        }
        return switch (baseline) {
            case ACTIVE -> false;
            case DEPRECATED -> override == CryptoSuiteStatus.ACTIVE;
            case DISABLED -> override == CryptoSuiteStatus.ACTIVE
                    || override == CryptoSuiteStatus.DEPRECATED
                    || override == CryptoSuiteStatus.EXPERIMENTAL;
            case UNSUPPORTED -> override != CryptoSuiteStatus.UNSUPPORTED;
            case EXPERIMENTAL -> override == CryptoSuiteStatus.ACTIVE
                    || override == CryptoSuiteStatus.DEPRECATED;
        };
    }

    /**
     * Creates a sanitized project exception and records its stable failure category.
     */
    private GeneralException decisionFailure(CryptoSuiteType type,
                                             String operation,
                                             CryptoSuiteFailureReason reason,
                                             boolean historicalRead) {
        recordDecision(type, operation, reason);
        ResultEnum result = historicalRead ? ResultEnum.FILE_RECORD_ERROR : ResultEnum.PARAM_IS_INVALID;
        return new GeneralException(result, Map.of("reason", reason.name()));
    }

    /**
     * Records only closed enum values to keep metric cardinality bounded.
     */
    private void recordDecision(CryptoSuiteType type,
                                String operation,
                                CryptoSuiteFailureReason reason) {
        meterRegistry.counter(
                DECISION_METRIC,
                "type", type == null ? "UNKNOWN" : type.name(),
                "operation", operation,
                "outcome", reason == CryptoSuiteFailureReason.NONE ? "success" : "failure",
                "reason", reason.name()).increment();
    }

    /**
     * Builds the authoritative closed catalog of real and explicitly non-real capabilities.
     */
    private Map<String, CryptoSuiteDefinition> buildDefinitions() {
        Map<String, CryptoSuiteDefinition> catalog = new LinkedHashMap<>();
        register(catalog, definition(CryptoSuiteIds.LEGACY_CHUNK_CHAIN,
                CryptoSuiteType.CONTENT_ENCRYPTION, CryptoSuiteIds.CONTENT_PROVIDER, 1,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("AES-256", "GCM", "LEGACY-CHUNK-CHAIN"),
                Set.of(CryptoSuiteIds.LEGACY_CHUNK_CHAIN), true, true));
        register(catalog, definition(CryptoSuiteIds.FRAMED_AEAD_V2,
                CryptoSuiteType.CONTENT_ENCRYPTION, CryptoSuiteIds.CONTENT_PROVIDER, 2,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("AES-256", "GCM", "HKDF-SHA256", "FRAMED"),
                Set.of(CryptoSuiteIds.FRAMED_AEAD_V2), true, true));
        register(catalog, definition(CryptoSuiteIds.LOCAL_WRAPPING,
                CryptoSuiteType.KEY_WRAPPING, LocalKeyWrappingService.PROVIDER_ID, 1,
                CryptoSuiteStatus.ACTIVE, P3_INTRODUCED, Set.of("AES-256", "GCM", "LOCAL-AAD-V1"),
                Set.of(CryptoSuiteIds.LOCAL_WRAPPING, CryptoSuiteIds.VAULT_TRANSIT_WRAPPING), true, false));
        register(catalog, definition(CryptoSuiteIds.VAULT_TRANSIT_WRAPPING,
                CryptoSuiteType.KEY_WRAPPING, VaultTransitKeyWrappingProvider.PROVIDER_ID, 1,
                CryptoSuiteStatus.ACTIVE, P3_INTRODUCED,
                Set.of("AES256-GCM96", "DERIVED-CONTEXT", "VAULT-TRANSIT"),
                Set.of(CryptoSuiteIds.LOCAL_WRAPPING, CryptoSuiteIds.VAULT_TRANSIT_WRAPPING), true, false));
        register(catalog, definition(CryptoSuiteIds.UNSIGNED_V1,
                CryptoSuiteType.SIGNATURE, CryptoSuiteIds.NO_PROVIDER, 1,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("NO-AUTHENTICITY"),
                Set.of(CryptoSuiteIds.UNSIGNED_V1), true, false));
        register(catalog, definition(CryptoSuiteIds.ED25519_JWS_V1,
                CryptoSuiteType.SIGNATURE, CryptoSuiteIds.LOCAL_ED25519_PROVIDER, 1,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("ED25519", "JWS-EDDSA"),
                Set.of(CryptoSuiteIds.ED25519_JWS_V1), true, false));
        register(catalog, definition(CryptoSuiteIds.ML_DSA_65_DRAFT,
                CryptoSuiteType.SIGNATURE, "unimplemented-pqc", 0,
                CryptoSuiteStatus.EXPERIMENTAL, P3_INTRODUCED, Set.of("NO-RUNTIME-PROVIDER"),
                Set.of(), false, false));
        register(catalog, definition(CryptoSuiteIds.NO_KEM_V1,
                CryptoSuiteType.KEM, CryptoSuiteIds.NO_PROVIDER, 1,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("NO-KEY-ESTABLISHMENT"),
                Set.of(CryptoSuiteIds.NO_KEM_V1), true, false));
        register(catalog, definition(CryptoSuiteIds.ML_KEM_768_DRAFT,
                CryptoSuiteType.KEM, "unimplemented-pqc", 0,
                CryptoSuiteStatus.EXPERIMENTAL, P3_INTRODUCED, Set.of("NO-RUNTIME-PROVIDER"),
                Set.of(), false, false));
        register(catalog, definition(CryptoSuiteIds.MERKLE_SHA256_V1,
                CryptoSuiteType.PROOF, CryptoSuiteIds.MERKLE_PROVIDER, 1,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("SHA-256", "ORDERED-MERKLE-PATH"),
                Set.of(CryptoSuiteIds.MERKLE_SHA256_V1), true, false));
        register(catalog, definition(CryptoSuiteIds.SIGNED_PROOF_ZIP_V2,
                CryptoSuiteType.PROOF, CryptoSuiteIds.LOCAL_ED25519_PROVIDER, 1,
                CryptoSuiteStatus.ACTIVE, P1_INTRODUCED, Set.of("SIGNED-PROOF-ZIP-V2", "ED25519"),
                Set.of(CryptoSuiteIds.SIGNED_PROOF_ZIP_V2), true, false));
        return Map.copyOf(catalog);
    }

    /**
     * Creates a catalog definition without lifecycle deadlines.
     */
    private CryptoSuiteDefinition definition(String id,
                                             CryptoSuiteType type,
                                             String providerId,
                                             int providerContractVersion,
                                             CryptoSuiteStatus status,
                                             Instant introducedAt,
                                             Set<String> constraints,
                                             Set<String> compatibleWith,
                                             boolean productionWriteAllowed,
                                             boolean transitionRequiresReencryption) {
        return new CryptoSuiteDefinition(id, type, providerId, providerContractVersion, status,
                introducedAt, null, null, constraints, compatibleWith,
                productionWriteAllowed, transitionRequiresReencryption);
    }

    /**
     * Rejects duplicate stable identifiers while assembling the catalog.
     */
    private void register(Map<String, CryptoSuiteDefinition> catalog, CryptoSuiteDefinition definition) {
        if (!StringUtils.hasText(definition.id()) || catalog.putIfAbsent(definition.id(), definition) != null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    Map.of("reason", CryptoSuiteFailureReason.INVALID_LIFECYCLE.name()));
        }
    }
}
