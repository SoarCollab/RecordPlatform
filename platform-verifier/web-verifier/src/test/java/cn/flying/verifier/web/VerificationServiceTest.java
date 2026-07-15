package cn.flying.verifier.web;

import cn.flying.verifier.ProofVerifier;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.model.VerificationReport;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Request-service tests for byte limits, bounded concurrency, and fixed-window admission.
 */
class VerificationServiceTest {

    /** Rejects a declared original size before any SDK invocation. */
    @Test
    void shouldRejectOversizedOriginalPart() {
        VerifierProperties properties = properties(4, 1, Duration.ofMillis(10));
        ProofVerifier verifier = (original, proof, context) -> report();
        VerificationService service = new VerificationService(
                verifier,
                new VerificationContextFactory(properties, Clock.systemUTC()),
                properties);
        MockMultipartFile original = new MockMultipartFile("original", new byte[5]);
        MockMultipartFile proof = new MockMultipartFile("proof", new byte[]{1});

        assertThatThrownBy(() -> service.verify(original, proof, null))
                .isInstanceOfSatisfying(VerificationRequestException.class, error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(error.code()).isEqualTo("ORIGINAL_TOO_LARGE");
                });
    }

    /** Refuses a second expensive verification when the configured fair semaphore is occupied. */
    @Test
    void shouldBoundConcurrentVerificationQueue() throws Exception {
        VerifierProperties properties = properties(1024, 1, Duration.ofMillis(20));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProofVerifier verifier = (original, proof, context) -> {
            entered.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test verification was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return report();
        };
        VerificationService service = new VerificationService(
                verifier,
                new VerificationContextFactory(properties, Clock.systemUTC()),
                properties);

        CompletableFuture<VerificationReport> first = CompletableFuture.supplyAsync(() -> service.verify(
                new MockMultipartFile("original", new byte[]{1}),
                new MockMultipartFile("proof", new byte[]{2}),
                null));
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            assertThatThrownBy(() -> service.verify(
                    new MockMultipartFile("original", new byte[]{1}),
                    new MockMultipartFile("proof", new byte[]{2}),
                    null))
                    .isInstanceOfSatisfying(VerificationRequestException.class, error -> {
                        assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(error.code()).isEqualTo("VERIFIER_BUSY");
                    });
        } finally {
            release.countDown();
        }
        assertThat(first.get(1, TimeUnit.SECONDS).outcome()).isEqualTo(VerificationOutcome.INDETERMINATE);
    }

    /** Enforces per-client capacity, window reset, and bounded remembered-client cardinality. */
    @Test
    void shouldApplyBoundedFixedWindowRateLimit() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(
                new VerifierProperties.RateLimit(2, Duration.ofSeconds(1), 2));
        java.time.Instant now = java.time.Instant.parse("2026-07-15T00:00:00Z");

        assertThat(limiter.tryAcquire("client-a", now)).isTrue();
        assertThat(limiter.tryAcquire("client-a", now)).isTrue();
        assertThat(limiter.tryAcquire("client-a", now)).isFalse();
        assertThat(limiter.tryAcquire("client-b", now)).isTrue();
        assertThat(limiter.tryAcquire("client-c", now)).isFalse();
        assertThat(limiter.tryAcquire("client-c", now.plusSeconds(1))).isTrue();
        assertThat(limiter.retryAfterSeconds()).isEqualTo(1L);
    }

    /** Rejects sub-millisecond windows that would otherwise silently disable rate limiting. */
    @Test
    void shouldRejectSubMillisecondRateLimitWindow() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(
                new VerifierProperties.RateLimit(2, Duration.ofNanos(1), 2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one millisecond");
    }

    /** Rejects a second upload before the first request leaves the pre-multipart admission filter. */
    @Test
    void shouldBoundRequestsBeforeMultipartResolution() throws Exception {
        VerifierProperties properties = properties(1024, 1, Duration.ofMillis(20));
        VerifierConcurrencyFilter filter = new VerifierConcurrencyFilter(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MockHttpServletRequest first = verificationRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                filter.doFilter(first, firstResponse, (request, response) -> {
                    entered.countDown();
                    try {
                        if (!release.await(2, TimeUnit.SECONDS)) {
                            throw new ServletException("test request was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ServletException(e);
                    }
                });
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        });
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
        try {
            MockHttpServletResponse rejected = new MockHttpServletResponse();
            filter.doFilter(verificationRequest(), rejected, new MockFilterChain());

            assertThat(rejected.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
            assertThat(rejected.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
            assertThat(rejected.getContentAsString()).contains("\"code\":\"VERIFIER_BUSY\"");
        } finally {
            release.countDown();
        }
        running.get(1, TimeUnit.SECONDS);
    }

    /** Removes every server-created multipart file and directory after a completed request. */
    @Test
    void shouldCleanTemporaryInputsAfterVerification() throws Exception {
        VerifierProperties properties = properties(1024, 1, Duration.ofMillis(10));
        VerificationService service = new VerificationService(
                (original, proof, context) -> report(),
                new VerificationContextFactory(properties, Clock.systemUTC()),
                properties);
        Set<Path> before = temporaryVerifierDirectories();

        VerificationReport result = service.verify(
                new MockMultipartFile("original", new byte[]{1}),
                new MockMultipartFile("proof", new byte[]{2}),
                null);

        assertThat(result.outcome()).isEqualTo(VerificationOutcome.INDETERMINATE);
        assertThat(temporaryVerifierDirectories()).isEqualTo(before);
    }

    /** Removes temporary inputs and releases capacity even when the shared verifier throws unexpectedly. */
    @Test
    void shouldCleanTemporaryInputsAfterVerifierFailure() throws Exception {
        VerifierProperties properties = properties(1024, 1, Duration.ofMillis(10));
        VerificationService service = new VerificationService(
                (original, proof, context) -> {
                    throw new IllegalStateException("fixture failure");
                },
                new VerificationContextFactory(properties, Clock.systemUTC()),
                properties);
        Set<Path> before = temporaryVerifierDirectories();

        assertThatThrownBy(() -> service.verify(
                new MockMultipartFile("original", new byte[]{1}),
                new MockMultipartFile("proof", new byte[]{2}),
                null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(temporaryVerifierDirectories()).isEqualTo(before);
    }

    /** Returns the complete public 429 contract when the direct-peer fixed window is exhausted. */
    @Test
    void shouldReturnRateLimitHttpResponse() throws Exception {
        VerifierProperties base = properties(1024, 1, Duration.ofMillis(10));
        VerifierProperties limited = new VerifierProperties(
                base.maxOriginalFileBytes(),
                base.maxConcurrentVerifications(),
                base.acquireTimeout(),
                new VerifierProperties.RateLimit(1, Duration.ofSeconds(30), 10),
                base.online());
        VerifierRateLimitFilter filter = new VerifierRateLimitFilter(
                limited,
                Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), ZoneOffset.UTC));
        MockHttpServletRequest first = verificationRequest();
        MockHttpServletResponse accepted = new MockHttpServletResponse();
        filter.doFilter(first, accepted, new MockFilterChain());

        MockHttpServletRequest second = verificationRequest();
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(second, rejected, new MockFilterChain());

        assertThat(accepted.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(rejected.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS.value());
        assertThat(rejected.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(rejected.getHeader(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(rejected.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"");
    }

    /** Keeps no-argument multipart exception handlers bound to their stable public responses. */
    @Test
    void shouldReturnStableMultipartTransportErrors() {
        VerifierExceptionHandler handler = new VerifierExceptionHandler();

        var uploadLimit = handler.handleUploadLimit();
        var missingPart = handler.handleMissingPart();
        var malformed = handler.handleMultipart();

        assertThat(uploadLimit.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(uploadLimit.getBody())
                .extracting(VerifierErrorResponse::code)
                .isEqualTo("REQUEST_TOO_LARGE");
        assertThat(missingPart.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missingPart.getBody())
                .extracting(VerifierErrorResponse::code)
                .isEqualTo("PART_REQUIRED");
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(malformed.getBody())
                .extracting(VerifierErrorResponse::code)
                .isEqualTo("MULTIPART_INVALID");
    }

    /** Creates the minimum complete offline operator policy for service tests. */
    private VerifierProperties properties(long maxBytes, int concurrency, Duration acquireTimeout) {
        return new VerifierProperties(
                maxBytes,
                concurrency,
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

    /** Returns one adapter-neutral report for service boundary tests. */
    private VerificationReport report() {
        return new VerificationReport(
                VerificationReport.SCHEMA_VERSION,
                VerificationOutcome.INDETERMINATE,
                "2026-07-15T00:00:00Z",
                VerificationReport.VERIFIER_VERSION,
                null,
                List.of());
    }

    /** Lists only verifier-owned direct children of the operating-system temporary directory. */
    private Set<Path> temporaryVerifierDirectories() throws java.io.IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"));
        try (var paths = java.nio.file.Files.list(root)) {
            return paths
                    .filter(path -> path.getFileName().toString().startsWith("record-platform-verifier-"))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    /** Builds one direct-peer POST request that is subject to verifier rate limiting. */
    private MockHttpServletRequest verificationRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("POST");
        request.setServletPath("/api/v1/verify");
        request.setRemoteAddr("203.0.113.10");
        return request;
    }
}
