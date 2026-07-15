package cn.flying.verifier.web;

import cn.flying.verifier.ProofVerifier;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import cn.flying.verifier.resolver.TrustedEvidenceLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request-service boundary tests for malformed parts, streaming limits, I/O, and interruption.
 */
class VerificationServiceBoundaryTest {

    /** Rejects each absent required part and an empty proof with stable bad-request codes. */
    @Test
    void shouldRejectMissingAndEmptyRequiredParts() {
        VerificationService service = service(properties(Duration.ofMillis(10)));
        MockMultipartFile original = multipart("original", new byte[]{1});
        MockMultipartFile proof = multipart("proof", new byte[]{2});

        assertRequestError(() -> service.verify(null, proof, null), HttpStatus.BAD_REQUEST, "PART_REQUIRED");
        assertRequestError(() -> service.verify(original, null, null), HttpStatus.BAD_REQUEST, "PART_REQUIRED");
        assertRequestError(
                () -> service.verify(original, multipart("proof", new byte[0]), null),
                HttpStatus.BAD_REQUEST,
                "PROOF_REQUIRED");
    }

    /** Treats an empty optional trust part as absent and still completes local verification. */
    @Test
    void shouldAllowEmptyOptionalTrustPart() {
        VerificationReport result = service(properties(Duration.ofMillis(10))).verify(
                multipart("original", new byte[0]),
                multipart("proof", new byte[]{1}),
                multipart("trustedKey", new byte[0]));

        assertThat(result.outcome()).isEqualTo(VerificationOutcome.INDETERMINATE);
    }

    /** Maps malformed trust JSON to a stable client error without exposing parser details. */
    @Test
    void shouldRejectInvalidTrustJson() {
        VerificationService service = service(properties(Duration.ofMillis(10)));

        assertRequestError(
                () -> service.verify(
                        multipart("original", new byte[]{1}),
                        multipart("proof", new byte[]{2}),
                        multipart("trustedKey", new byte[]{'{'})),
                HttpStatus.BAD_REQUEST,
                "TRUSTED_KEY_INVALID");
    }

    /** Rejects a trust upload whose declared size exceeds the bounded in-memory parser limit. */
    @Test
    void shouldRejectDeclaredOversizedTrustPart() {
        VerificationService service = service(properties(Duration.ofMillis(10)));
        byte[] oversized = new byte[TrustedEvidenceLoader.MAX_TRUST_FILE_BYTES + 1];

        assertRequestError(
                () -> service.verify(
                        multipart("original", new byte[]{1}),
                        multipart("proof", new byte[]{2}),
                        multipart("trustedKey", oversized)),
                HttpStatus.PAYLOAD_TOO_LARGE,
                "TRUSTED_KEY_TOO_LARGE");
    }

    /** Enforces the streaming byte limit even when multipart metadata understates the body size. */
    @Test
    void shouldRejectStreamingOriginalLimitBypass() {
        VerifierProperties properties = properties(Duration.ofMillis(10));
        MockMultipartFile understated = new MockMultipartFile("original", new byte[5]) {
            @Override
            public long getSize() {
                return 4L;
            }
        };

        assertRequestError(
                () -> service(properties).verify(
                        understated,
                        multipart("proof", new byte[]{2}),
                        null),
                HttpStatus.PAYLOAD_TOO_LARGE,
                "ORIGINAL_TOO_LARGE");
    }

    /** Converts multipart stream failures into the opaque temporary-storage error contract. */
    @Test
    void shouldMapMultipartIoFailure() {
        MockMultipartFile unreadable = new MockMultipartFile("original", new byte[]{1}) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("sensitive local path");
            }
        };

        assertRequestError(
                () -> service(properties(Duration.ofMillis(10))).verify(
                        unreadable,
                        multipart("proof", new byte[]{2}),
                        null),
                HttpStatus.INTERNAL_SERVER_ERROR,
                "TEMPORARY_STORAGE_ERROR");
    }

    /** Preserves interruption and returns a retryable error before temporary files are created. */
    @Test
    void shouldRejectInterruptedPermitAcquisition() {
        VerificationService service = service(properties(Duration.ofSeconds(1)));
        Thread.currentThread().interrupt();
        try {
            assertRequestError(
                    () -> service.verify(
                            multipart("original", new byte[]{1}),
                            multipart("proof", new byte[]{2}),
                            null),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "VERIFIER_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    /** Rejects null, zero, and negative service permit timeouts during construction. */
    @Test
    void shouldRejectNonPositiveServiceAcquireTimeouts() {
        VerificationContextFactory factory = new VerificationContextFactory(
                properties(Duration.ofMillis(10)), Clock.systemUTC());

        for (Duration timeout : new Duration[]{null, Duration.ZERO, Duration.ofMillis(-1)}) {
            VerifierProperties invalid = properties(timeout);
            assertThatThrownBy(() -> new VerificationService(verifier(), factory, invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("acquire timeout must be positive");
        }
    }

    /** Asserts one stable request exception status and code without relying on its message text. */
    private void assertRequestError(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            HttpStatus status,
            String code
    ) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(VerificationRequestException.class, error -> {
                    assertThat(error.status()).isEqualTo(status);
                    assertThat(error.code()).isEqualTo(code);
                });
    }

    /** Creates a service whose SDK boundary returns a deterministic adapter-neutral report. */
    private VerificationService service(VerifierProperties properties) {
        return new VerificationService(
                verifier(),
                new VerificationContextFactory(properties, Clock.systemUTC()),
                properties);
    }

    /** Returns a no-network verifier double for request-boundary tests. */
    private ProofVerifier verifier() {
        return (original, proof, context) -> new VerificationReport(
                VerificationReport.SCHEMA_VERSION,
                VerificationOutcome.INDETERMINATE,
                "2026-07-15T00:00:00Z",
                VerificationReport.VERIFIER_VERSION,
                null,
                List.of());
    }

    /** Creates one multipart part backed by deterministic in-memory bytes. */
    private MockMultipartFile multipart(String name, byte[] bytes) {
        return new MockMultipartFile(name, bytes);
    }

    /** Creates one otherwise valid offline service policy with the selected permit timeout. */
    private VerifierProperties properties(Duration acquireTimeout) {
        return new VerifierProperties(
                4,
                1,
                acquireTimeout,
                new VerifierProperties.RateLimit(20, Duration.ofMinutes(1), 100),
                new VerifierProperties.Online(
                        false,
                        null,
                        null,
                        Set.of(),
                        false,
                        false,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1)));
    }
}
