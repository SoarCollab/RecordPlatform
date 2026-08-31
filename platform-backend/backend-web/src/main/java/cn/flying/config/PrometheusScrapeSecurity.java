package cn.flying.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.util.Enumeration;
import java.util.regex.Pattern;

/** Optional machine identity confined to the exact Prometheus read endpoint. */
@Component
public final class PrometheusScrapeSecurity {

    public static final String AUTHORITY = "PROMETHEUS_SCRAPE";
    private static final String PATH = "/actuator/prometheus";
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");
    private static final Pattern HASH = Pattern.compile("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");
    private final boolean enabled;
    private final String username;
    private final String passwordHash;

    /** Validate secrets without binding-error diagnostics that echo rejected values. */
    public PrometheusScrapeSecurity(
            @Value("${security.prometheus-scrape.enabled:${PROMETHEUS_SCRAPE_ENABLED:false}}") boolean enabled,
            @Value("${security.prometheus-scrape.username:${PROMETHEUS_SCRAPE_USERNAME:}}") String username,
            @Value("${security.prometheus-scrape.password-hash:${PROMETHEUS_SCRAPE_PASSWORD_HASH:}}") String passwordHash) {
        if (enabled && (username == null || !USERNAME.matcher(username).matches()
                || passwordHash == null || !HASH.matcher(passwordHash).matches())) {
            throw new IllegalArgumentException("Enabled Prometheus scrape authentication requires a dedicated username and BCrypt hash");
        }
        this.enabled = enabled;
        this.username = username;
        this.passwordHash = passwordHash;
    }

    /** Match only exact context-relative GET/HEAD requests, without path normalization. */
    public boolean matches(HttpServletRequest request) {
        if (!enabled || !("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()))) {
            return false;
        }
        return (request.getContextPath() + PATH).equals(request.getRequestURI());
    }

    /** Ignore tenant hints only for this machine credential form, never for Bearer callers. */
    public boolean isScrapeAttempt(HttpServletRequest request) {
        if (!matches(request)) {
            return false;
        }
        Enumeration<String> headers = request.getHeaders(HttpHeaders.AUTHORIZATION);
        while (headers != null && headers.hasMoreElements()) {
            if (isBasic(headers.nextElement())) {
                return true;
            }
        }
        return false;
    }

    /** Create a chain-local provider/filter; none of these are global authentication beans. */
    public BasicAuthenticationFilter createFilter() {
        if (!enabled) {
            throw new IllegalStateException("Prometheus scrape authentication is disabled");
        }
        var users = new InMemoryUserDetailsManager(User.withUsername(username).password(passwordHash)
                .authorities(AUTHORITY).build());
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(new BCryptPasswordEncoder());
        var filter = new BasicAuthenticationFilter(new ProviderManager(provider), (request, response, exception) -> {
            response.setStatus(401);
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"prometheus\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        }) {
            /** Leave all other authentication paths under the existing application rules. */
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !matches(request);
            }
        };
        filter.setSecurityContextRepository(new NullSecurityContextRepository());
        filter.setAuthenticationConverter(this::convert);
        return filter;
    }

    /** Expose only the non-secret feature state to the security-chain builder. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Reject ambiguity and sanitize parser failures before the standard filter logs them. */
    private Authentication convert(HttpServletRequest request) {
        // Pure Bearer requests retain all existing JWT semantics and limits.
        if (!isScrapeAttempt(request)) {
            return null;
        }
        Enumeration<String> values = request.getHeaders(HttpHeaders.AUTHORIZATION);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String header = values.nextElement();
        if (values.hasMoreElements() || header.length() > 1024 || header.indexOf(',') >= 0) {
            throw invalidCredentials();
        }
        if (!header.regionMatches(true, 0, "Basic ", 0, 6)) {
            throw invalidCredentials();
        }
        try {
            Authentication authentication = new BasicAuthenticationConverter().convert(request);
            // The framework logs usernames at TRACE; untrusted input must not reach that sink.
            if (authentication == null || !username.equals(authentication.getName())) {
                throw invalidCredentials();
            }
            return authentication;
        } catch (AuthenticationException exception) {
            throw invalidCredentials();
        }
    }

    /** Recognize Basic's scheme boundary, including malformed missing-token attempts. */
    private static boolean isBasic(String header) {
        if (header == null) {
            return false;
        }
        String value = header.trim();
        return value.equalsIgnoreCase("Basic") || value.regionMatches(true, 0, "Basic ", 0, 6)
                || value.regionMatches(true, 0, "Basic\t", 0, 6);
    }

    /** Return a fixed credential-free failure without retaining parser causes. */
    private static BadCredentialsException invalidCredentials() {
        return new BadCredentialsException("Invalid Prometheus scrape credentials");
    }
}
