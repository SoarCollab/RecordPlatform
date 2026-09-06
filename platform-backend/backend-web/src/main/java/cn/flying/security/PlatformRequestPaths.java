package cn.flying.security;

import cn.flying.common.util.SensitiveDataMasker;
import jakarta.servlet.http.HttpServletRequest;

/** Identifies the dedicated platform route family for shared logging boundaries. */
public final class PlatformRequestPaths {

    private static final String ROOT = "/api/v1/platform";

    /** Prevents instances of the route classifier. */
    private PlatformRequestPaths() {
    }

    /** Matches the canonical application path without swallowing similarly prefixed tenant routes. */
    public static boolean isPlatformRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (path == null || path.isBlank()) {
            path = request.getServletPath();
        } else if (contextPath != null && !contextPath.isEmpty()
                && path.startsWith(contextPath + "/")) {
            path = path.substring(contextPath.length());
        }
        if (path == null) {
            return false;
        }
        String normalized = SensitiveDataMasker.normalizePathForRouteMatching(path);
        return ROOT.equals(normalized) || normalized.startsWith(ROOT + "/");
    }
}
