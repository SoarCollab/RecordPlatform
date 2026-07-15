package cn.flying.verifier.web;

import org.springframework.http.HttpStatus;

/**
 * Safe request-layer failure with a stable public code and HTTP status.
 */
public final class VerificationRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    /** Creates one safe request error without retaining raw uploaded content. */
    public VerificationRequestException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /** Returns the response status selected by the request boundary. */
    public HttpStatus status() {
        return status;
    }

    /** Returns the stable machine-readable request error code. */
    public String code() {
        return code;
    }
}
