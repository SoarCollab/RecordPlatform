package cn.flying.verifier.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct exception-handler tests for stable status, retry, cache, and disclosure contracts.
 */
class VerifierExceptionHandlerTest {

    private final VerifierExceptionHandler handler = new VerifierExceptionHandler();

    /** Adds Retry-After only to retryable request failures while preserving the public error body. */
    @Test
    void shouldMapRetryableAndNonRetryableRequestErrors() {
        var retryable = handler.handleRequest(new VerificationRequestException(
                HttpStatus.SERVICE_UNAVAILABLE, "VERIFIER_BUSY", "Please retry"));
        var badRequest = handler.handleRequest(new VerificationRequestException(
                HttpStatus.BAD_REQUEST, "PART_REQUIRED", "Missing input"));

        assertThat(retryable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(retryable.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(retryable.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(retryable.getBody())
                .extracting(VerifierErrorResponse::code, VerifierErrorResponse::message)
                .containsExactly("VERIFIER_BUSY", "Please retry");
        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badRequest.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
        assertThat(badRequest.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    /** Converts an unexpected exception into one opaque 500 response without exposing its message. */
    @Test
    void shouldHideUnexpectedExceptionDetails() {
        var response = handler.handleUnexpected(new IllegalStateException("secret-path-and-content"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getBody())
                .extracting(VerifierErrorResponse::code, VerifierErrorResponse::message)
                .containsExactly("VERIFIER_ERROR", "The verifier could not safely process this request");
        assertThat(response.getBody().message()).doesNotContain("secret-path-and-content");
    }
}
