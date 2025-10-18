package cn.flying.monitor.auth.controller;

import cn.flying.monitor.auth.service.AuthenticationService;
import cn.flying.monitor.common.security.Permission;
import cn.flying.monitor.common.security.RequirePermission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Authentication controller with permission validation examples
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationService authenticationService;

    /**
     * Login endpoint - no permissions required
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam(required = false) String clientIp) {
        
        Map<String, Object> result = authenticationService.authenticate(username, password, clientIp);
        return ResponseEntity.ok(result);
    }

    /**
     * Complete MFA - requires pre-auth token
     */
    @PostMapping("/mfa/complete")
    public ResponseEntity<Map<String, Object>> completeMfa(
            @RequestParam String preAuthToken,
            @RequestParam String mfaCode,
            @RequestParam(defaultValue = "false") boolean isBackupCode,
            @RequestParam(required = false) String clientIp) {
        
        Map<String, Object> result = authenticationService.completeMfaAuthentication(
            preAuthToken, mfaCode, isBackupCode, clientIp);
        return ResponseEntity.ok(result);
    }

    /**
     * Refresh token - no additional permissions required beyond valid refresh token
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshToken(@RequestParam String refreshToken) {
        Map<String, Object> result = authenticationService.refreshToken(refreshToken);
        return ResponseEntity.ok(result);
    }

    /**
     * Admin endpoint - requires system admin permission
     */
    @GetMapping("/admin/users")
    @RequirePermission("user:read")
    public ResponseEntity<String> getUsers() {
        return ResponseEntity.ok("User list - admin access granted");
    }

    /**
     * Monitor data endpoint - requires monitor data read permission
     */
    @GetMapping("/monitor/data")
    @RequirePermission("monitor:data:read")
    public ResponseEntity<String> getMonitorData() {
        return ResponseEntity.ok("Monitor data - read access granted");
    }

    /**
     * System config endpoint - requires system config permission
     */
    @PostMapping("/system/config")
    @RequirePermission("system:config")
    public ResponseEntity<String> updateSystemConfig() {
        return ResponseEntity.ok("System config updated - admin access granted");
    }

    /**
     * Multi-permission endpoint - requires multiple permissions
     */
    @PostMapping("/admin/security")
    @RequirePermission({"system:admin", "security:audit:read"})
    public ResponseEntity<String> manageSecuritySettings() {
        return ResponseEntity.ok("Security settings - full admin access granted");
    }

    /**
     * Alternative permission endpoint - requires any of the specified permissions
     */
    @GetMapping("/reports")
    @RequirePermission(anyOf = {"report:read", "monitor:data:read", "system:audit"})
    public ResponseEntity<String> getReports() {
        return ResponseEntity.ok("Reports - read access granted");
    }

    /**
     * Certificate management - requires certificate management permission
     */
    @PostMapping("/certificates/manage")
    @RequirePermission("security:cert:manage")
    public ResponseEntity<String> manageCertificates() {
        return ResponseEntity.ok("Certificate management - access granted");
    }

    /**
     * WebSocket connection - requires WebSocket permission
     */
    @PostMapping("/websocket/connect")
    @RequirePermission("websocket:connect")
    public ResponseEntity<String> connectWebSocket() {
        return ResponseEntity.ok("WebSocket connection - access granted");
    }
}