package cn.flying.verifier.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Converts transport failures into bounded errors without disclosing paths, content, or stack traces.
 */
@RestControllerAdvice
public class VerifierExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(VerifierExceptionHandler.class);

    /** Returns the explicit status and stable code selected by the request service. */
    @ExceptionHandler(VerificationRequestException.class)
    public ResponseEntity<VerifierErrorResponse> handleRequest(VerificationRequestException error) {
        HttpHeaders headers = noStoreHeaders();
        if (error.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            headers.set(HttpHeaders.RETRY_AFTER, "1");
        }
        return new ResponseEntity<>(
                VerifierErrorResponse.of(error.code(), error.getMessage()),
                headers,
                error.status());
    }

    /** Maps container-level multipart size rejection to a stable 413 response. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<VerifierErrorResponse> handleUploadLimit() {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "REQUEST_TOO_LARGE",
                "The multipart request exceeds the configured byte limit");
    }

    /** Maps missing required parts to a stable client error. */
    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<VerifierErrorResponse> handleMissingPart() {
        return response(HttpStatus.BAD_REQUEST, "PART_REQUIRED", "Required multipart input is missing");
    }

    /** Maps malformed multipart framing to a stable client error. */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<VerifierErrorResponse> handleMultipart() {
        return response(HttpStatus.BAD_REQUEST, "MULTIPART_INVALID", "The multipart request is invalid");
    }

    /** Logs an opaque server-side failure class and returns no implementation details. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<VerifierErrorResponse> handleUnexpected(Exception error) {
        LOG.error("Public verifier request failed with type {}", error.getClass().getName());
        return response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "VERIFIER_ERROR",
                "The verifier could not safely process this request");
    }

    /** Builds one no-store JSON error response. */
    private ResponseEntity<VerifierErrorResponse> response(HttpStatus status, String code, String message) {
        return new ResponseEntity<>(VerifierErrorResponse.of(code, message), noStoreHeaders(), status);
    }

    /** Prevents browsers and intermediaries from retaining request-layer verification errors. */
    private HttpHeaders noStoreHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl(CacheControl.noStore());
        return headers;
    }
}
