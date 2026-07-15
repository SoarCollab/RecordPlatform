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
import java.time.Clock;

/**
 * Rate-limits only verification submissions by direct socket peer, never by spoofable forwarding headers.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class VerifierRateLimitFilter extends OncePerRequestFilter {

    private static final byte[] RATE_LIMIT_BODY = (
            "{\"schemaVersion\":\"record-platform-verifier-error.v1\","
                    + "\"code\":\"RATE_LIMITED\","
                    + "\"message\":\"The verification request rate limit was exceeded\"}")
            .getBytes(StandardCharsets.UTF_8);

    private final FixedWindowRateLimiter limiter;
    private final Clock clock;

    /** Creates a bounded in-memory limiter from validated application configuration. */
    public VerifierRateLimitFilter(VerifierProperties properties, Clock clock) {
        this.limiter = new FixedWindowRateLimiter(properties.rateLimit());
        this.clock = clock;
    }

    /** Applies the request window or returns a self-contained 429 response before multipart parsing. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String client = request.getRemoteAddr();
        if (client == null || client.isBlank()) {
            client = "unknown";
        }
        if (limiter.tryAcquire(client, clock.instant())) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(limiter.retryAfterSeconds()));
        response.setContentLength(RATE_LIMIT_BODY.length);
        response.getOutputStream().write(RATE_LIMIT_BODY);
    }

    /** Skips health, static resources, and all methods other than the verification POST. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !"/api/v1/verify".equals(request.getServletPath());
    }
}
