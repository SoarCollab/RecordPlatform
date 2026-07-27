package cn.flying.service.key;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the closed runtime suite catalog and its non-downgrade lifecycle rules.
 */
class CryptoSuiteRegistryTest {

    /**
     * Proves every real capability is visible while unimplemented PQC entries remain explicit.
     */
    @Test
    void shouldExposeRealAndExperimentalCapabilitiesWithoutClaimingPqcSupport() {
        CryptoSuiteRegistry registry = registry(new CryptoAgilityProperties());

        assertThat(registry.diagnostics())
                .extracting(CryptoSuiteDiagnostic::id)
                .contains(CryptoSuiteIds.LOCAL_WRAPPING,
                        CryptoSuiteIds.VAULT_TRANSIT_WRAPPING,
                        CryptoSuiteIds.ED25519_JWS_V1,
                        CryptoSuiteIds.ML_DSA_65_DRAFT,
                        CryptoSuiteIds.ML_KEM_768_DRAFT);
        assertFailure(
                () -> registry.requireForWrite(CryptoSuiteType.KEM, CryptoSuiteIds.ML_KEM_768_DRAFT),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.EXPERIMENTAL_NOT_ALLOWED);
    }

    /**
     * Proves a deprecated suite remains readable but cannot be selected for a new write.
     */
    @Test
    void shouldSeparateDeprecatedWriteAndHistoricalReadDecisions() {
        CryptoAgilityProperties properties = new CryptoAgilityProperties();
        CryptoAgilityProperties.Lifecycle lifecycle = new CryptoAgilityProperties.Lifecycle();
        lifecycle.setDeprecatedAt(Instant.now().minusSeconds(1));
        properties.setSuiteLifecycle(Map.of(CryptoSuiteIds.LEGACY_CHUNK_CHAIN, lifecycle));
        CryptoSuiteRegistry registry = registry(properties);

        assertFailure(
                () -> registry.requireForWrite(
                        CryptoSuiteType.CONTENT_ENCRYPTION, CryptoSuiteIds.LEGACY_CHUNK_CHAIN),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.DEPRECATED_FOR_WRITE);
        assertThat(registry.requireForRead(
                CryptoSuiteType.CONTENT_ENCRYPTION, CryptoSuiteIds.LEGACY_CHUNK_CHAIN).id())
                .isEqualTo(CryptoSuiteIds.LEGACY_CHUNK_CHAIN);
    }

    /**
     * Proves a disabled suite fails closed for both historical reads and new writes.
     */
    @Test
    void shouldRejectDisabledSuiteForReadAndWrite() {
        CryptoAgilityProperties properties = new CryptoAgilityProperties();
        CryptoAgilityProperties.Lifecycle lifecycle = new CryptoAgilityProperties.Lifecycle();
        lifecycle.setDisabledAt(Instant.now().minusSeconds(1));
        properties.setSuiteLifecycle(Map.of(CryptoSuiteIds.MERKLE_SHA256_V1, lifecycle));
        CryptoSuiteRegistry registry = registry(properties);

        assertFailure(
                () -> registry.requireForWrite(CryptoSuiteType.PROOF, CryptoSuiteIds.MERKLE_SHA256_V1),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.DISABLED_FOR_READ);
        assertFailure(
                () -> registry.requireForRead(CryptoSuiteType.PROOF, CryptoSuiteIds.MERKLE_SHA256_V1),
                ResultEnum.FILE_RECORD_ERROR,
                CryptoSuiteFailureReason.DISABLED_FOR_READ);
    }

    /**
     * Proves an explicitly unsupported capability remains distinct from an experimental entry.
     */
    @Test
    void shouldRejectUnsupportedSuiteWithStableReason() {
        CryptoAgilityProperties properties = new CryptoAgilityProperties();
        CryptoAgilityProperties.Lifecycle lifecycle = new CryptoAgilityProperties.Lifecycle();
        lifecycle.setStatus(CryptoSuiteStatus.UNSUPPORTED);
        properties.setSuiteLifecycle(Map.of(CryptoSuiteIds.UNSIGNED_V1, lifecycle));
        CryptoSuiteRegistry registry = registry(properties);

        assertFailure(
                () -> registry.requireForWrite(CryptoSuiteType.SIGNATURE, CryptoSuiteIds.UNSIGNED_V1),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.UNSUPPORTED);
        assertFailure(
                () -> registry.requireForRead(CryptoSuiteType.SIGNATURE, CryptoSuiteIds.UNSIGNED_V1),
                ResultEnum.FILE_RECORD_ERROR,
                CryptoSuiteFailureReason.UNSUPPORTED);
    }

    /**
     * Proves unknown, blank, wrong-type, and wrong-provider identities never fall back to defaults.
     */
    @Test
    void shouldRejectUnknownTypeAndProviderMismatchesWithoutFallback() {
        CryptoSuiteRegistry registry = registry(new CryptoAgilityProperties());

        assertFailure(
                () -> registry.requireForRead(CryptoSuiteType.PROOF, null),
                ResultEnum.FILE_RECORD_ERROR,
                CryptoSuiteFailureReason.UNKNOWN_SUITE);
        assertFailure(
                () -> registry.requireForWrite(CryptoSuiteType.KEM, CryptoSuiteIds.ED25519_JWS_V1),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.TYPE_MISMATCH);
        assertFailure(
                () -> registry.requireForRead(
                        CryptoSuiteType.SIGNATURE,
                        CryptoSuiteIds.ED25519_JWS_V1,
                        "changed-default",
                        1),
                ResultEnum.FILE_RECORD_ERROR,
                CryptoSuiteFailureReason.PROVIDER_MISMATCH);
    }

    /**
     * Proves wrapping transitions may rewrap while content-suite transitions require re-encryption.
     */
    @Test
    void shouldDistinguishRewrapFromContentReencryption() {
        CryptoSuiteRegistry registry = registry(new CryptoAgilityProperties());

        registry.requireTransition(
                CryptoSuiteType.KEY_WRAPPING,
                CryptoSuiteIds.LOCAL_WRAPPING,
                CryptoSuiteIds.VAULT_TRANSIT_WRAPPING);
        assertFailure(
                () -> registry.requireTransition(
                        CryptoSuiteType.CONTENT_ENCRYPTION,
                        CryptoSuiteIds.LEGACY_CHUNK_CHAIN,
                        CryptoSuiteIds.FRAMED_AEAD_V2),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.REENCRYPT_REQUIRED);
    }

    /**
     * Proves configuration cannot promote an unimplemented experimental suite to active support.
     */
    @Test
    void shouldRejectLifecycleOverrideThatWidensBuiltInCapability() {
        CryptoAgilityProperties properties = new CryptoAgilityProperties();
        CryptoAgilityProperties.Lifecycle lifecycle = new CryptoAgilityProperties.Lifecycle();
        lifecycle.setStatus(CryptoSuiteStatus.ACTIVE);
        properties.setSuiteLifecycle(Map.of(CryptoSuiteIds.ML_KEM_768_DRAFT, lifecycle));

        assertFailure(
                () -> registry(properties),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.INVALID_LIFECYCLE);
    }

    /**
     * Proves lifecycle timestamps must be ordered and cannot predate implementation introduction.
     */
    @Test
    void shouldRejectImpossibleLifecycleTimestamps() {
        CryptoAgilityProperties properties = new CryptoAgilityProperties();
        CryptoAgilityProperties.Lifecycle lifecycle = new CryptoAgilityProperties.Lifecycle();
        lifecycle.setDeprecatedAt(Instant.parse("2026-07-28T00:00:00Z"));
        lifecycle.setDisabledAt(Instant.parse("2026-07-27T23:59:59Z"));
        properties.setSuiteLifecycle(Map.of(CryptoSuiteIds.LOCAL_WRAPPING, lifecycle));

        assertFailure(
                () -> registry(properties),
                ResultEnum.PARAM_IS_INVALID,
                CryptoSuiteFailureReason.INVALID_LIFECYCLE);
    }

    /**
     * Creates one registry with an isolated in-memory metrics boundary.
     */
    private CryptoSuiteRegistry registry(CryptoAgilityProperties properties) {
        return new CryptoSuiteRegistry(properties, new SimpleMeterRegistry());
    }

    /**
     * Asserts the public result and stable sanitized reason of a catalog decision failure.
     */
    private void assertFailure(Runnable action,
                               ResultEnum resultEnum,
                               CryptoSuiteFailureReason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> {
                    GeneralException exception = (GeneralException) error;
                    assertThat(exception.getResultEnum()).isEqualTo(resultEnum);
                    assertThat(exception.getData()).asString().contains(reason.name());
                });
    }
}
