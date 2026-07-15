package cn.flying.verifier.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Adds browser hardening headers to the UI, API, errors, and health responses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class VerifierSecurityHeadersFilter extends OncePerRequestFilter {

    /** Applies a self-hosted CSP and disables framing, sniffing, referrer, and powerful features. */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'; "
                        + "object-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        filterChain.doFilter(request, response);
    }
}
