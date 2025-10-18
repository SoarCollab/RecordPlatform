package cn.flying.monitor.auth.controller;

import cn.flying.monitor.common.dto.MfaSetupRequest;
import cn.flying.monitor.common.dto.MfaSetupResponse;
import cn.flying.monitor.common.dto.MfaVerificationRequest;
import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.security.MfaService;
import cn.flying.monitor.common.security.Permission;
import cn.flying.monitor.common.security.RequirePermission;
import cn.flying.monitor.common.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Multi-Factor Authentication controller
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/auth/mfa")
@Tag(name = "Multi-Factor Authentication", description = "MFA setup and verification endpoints")
@RequiredArgsConstructor
public class MfaController {
    
    private final MfaService mfaService;
    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    
    /**
     * Get MFA status for current user
     */
    @GetMapping("/status")
    @Operation(summary = "Get MFA status")
    public ResponseEntity<Map<String, Object>> getMfaStatus(Authentication authentication) {
        Optional<User> userOpt = userService.findByUsernameOrEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        User user = userOpt.get();
        boolean enabled = user.requiresMfa();
        
        // 通过服务判断是否存在备份码（不暴露具体数量）
        boolean hasBackupCodes = mfaService.hasBackupCodes(user.getId());
        
        return ResponseEntity.ok(Map.of(
            "enabled", enabled,
            "hasBackupCodes", hasBackupCodes
        ));
    }
    
    /**
     * Setup MFA for current user
     */
    @PostMapping("/setup")
    @Operation(summary = "Setup MFA")
    public ResponseEntity<MfaSetupResponse> setupMfa(
            @Valid @RequestBody MfaSetupRequest request,
            Authentication authentication) {
        
        Optional<User> userOpt = userService.findByUsernameOrEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        User user = userOpt.get();
        
        // Verify password
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            return ResponseEntity.badRequest().build();
        }
        
        // Generate MFA setup
        MfaSetupResponse response = mfaService.setupMfaForUser(user.getId(), user.getUsername());
        
        // Verify TOTP code to confirm setup
        if (mfaService.confirmMfaSetup(user.getId(), request.getTotpCode())) {
            response.setConfirmed(true);
            log.info("MFA setup completed for user: {}", user.getUsername());
            return ResponseEntity.ok(response);
        } else {
            log.warn("MFA setup failed - invalid TOTP code for user: {}", user.getUsername());
            return ResponseEntity.badRequest().build();
        }
    }
    
    /**
     * Verify MFA code
     */
    @PostMapping("/verify")
    @Operation(summary = "Verify MFA code")
    public ResponseEntity<Map<String, Object>> verifyMfa(
            @Valid @RequestBody MfaVerificationRequest request,
            Authentication authentication) {
        
        Optional<User> userOpt = userService.findByUsernameOrEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        User user = userOpt.get();
        
        boolean verified = mfaService.verifyMfaCode(user.getId(), request.getCode(), request.isBackupCode());
        
        if (verified) {
            log.info("MFA verification successful for user: {}", user.getUsername());
            return ResponseEntity.ok(Map.of("verified", true));
        } else {
            log.warn("MFA verification failed for user: {}", user.getUsername());
            return ResponseEntity.ok(Map.of("verified", false));
        }
    }
    
    /**
     * Disable MFA for current user
     */
    @DeleteMapping("/disable")
    @Operation(summary = "Disable MFA")
    public ResponseEntity<Map<String, Object>> disableMfa(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        
        Optional<User> userOpt = userService.findByUsernameOrEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        User user = userOpt.get();
        String password = request.get("password");
        
        // Verify password
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return ResponseEntity.badRequest().build();
        }
        
        mfaService.disableMfaForUser(user.getId());
        log.info("MFA disabled for user: {}", user.getUsername());
        
        return ResponseEntity.ok(Map.of("disabled", true));
    }
    
    /**
     * Regenerate backup codes
     */
    @PostMapping("/backup-codes/regenerate")
    @Operation(summary = "Regenerate backup codes")
    public ResponseEntity<Map<String, Object>> regenerateBackupCodes(
            @RequestBody Map<String, String> request,
            Authentication authentication) {
        
        Optional<User> userOpt = userService.findByUsernameOrEmail(authentication.getName());
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        User user = userOpt.get();
        
        if (!user.requiresMfa()) {
            return ResponseEntity.badRequest().build();
        }
        
        String password = request.get("password");
        
        // Verify password
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return ResponseEntity.badRequest().build();
        }
        
        List<String> backupCodes = mfaService.regenerateBackupCodes(user.getId());
        log.info("Backup codes regenerated for user: {}", user.getUsername());
        
        return ResponseEntity.ok(Map.of("backupCodes", backupCodes));
    }
    
    /**
     * Admin endpoint to disable MFA for any user
     */
    @DeleteMapping("/admin/disable/{userId}")
    @Operation(summary = "Admin: Disable MFA for user")
    @RequirePermission(anyOf = {"user:update", "security:mfa:manage"})
    public ResponseEntity<Map<String, Object>> adminDisableMfa(@PathVariable Long userId) {
        Optional<User> userOpt = userService.findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        
        User user = userOpt.get();
        mfaService.disableMfaForUser(userId);
        log.info("MFA disabled by admin for user: {}", user.getUsername());
        
        return ResponseEntity.ok(Map.of("disabled", true));
    }
}