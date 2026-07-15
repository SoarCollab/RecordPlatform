package cn.flying.verifier.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounds verification uploads before Spring resolves and disk-spools multipart request bodies.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class VerifierConcurrencyFilter extends OncePerRequestFilter {

    private static final byte[] BUSY_BODY = (
            "{\"schemaVersion\":\"record-platform-verifier-error.v1\","
                    + "\"code\":\"VERIFIER_BUSY\","
                    + "\"message\":\"The verifier is at its configured concurrency limit\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final Semaphore permits;
    private final long acquireTimeoutMillis;

    /** Creates a fair process-wide admission gate from validated operator limits. */
    public VerifierConcurrencyFilter(VerifierProperties properties) {
        this.permits = new Semaphore(properties.maxConcurrentVerifications(), true);
        this.acquireTimeoutMillis = positiveMillis(properties.acquireTimeout());
    }

    /** Acquires admission before multipart parsing and releases it after the complete response. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeBusy(response);
            return;
        }
        if (!acquired) {
            writeBusy(response);
            return;
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            permits.release();
        }
    }

    /** Returns the stable no-store 503 response without allowing multipart parsing to begin. */
    private void writeBusy(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setContentLength(BUSY_BODY.length);
        response.getOutputStream().write(BUSY_BODY);
    }

    /** Converts the configured wait to a strictly positive millisecond duration. */
    private long positiveMillis(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Verifier acquire timeout must be positive");
        }
        long millis = duration.toMillis();
        if (millis <= 0) {
            throw new IllegalArgumentException("Verifier acquire timeout must be at least one millisecond");
        }
        return millis;
    }

    /** Applies admission only to the public multipart verification endpoint. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/verify".equals(request.getServletPath());
    }
}
