package cn.flying.monitor.auth.controller;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.security.RequirePermission;
import cn.flying.monitor.common.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * User management controller with role-based access control
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    /**
     * Get all active users - requires user read permission
     */
    @GetMapping
    @RequirePermission("user:read")
    public ResponseEntity<List<User>> getAllActiveUsers() {
        List<User> users = userService.findActiveUsers();
        return ResponseEntity.ok(users);
    }

    /**
     * Get user by ID - requires user read permission
     */
    @GetMapping("/{userId}")
    @RequirePermission("user:read")
    public ResponseEntity<User> getUserById(@PathVariable Long userId) {
        Optional<User> userOpt = userService.findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(userOpt.get());
    }

    /**
     * Create new user - requires user create permission
     */
    @PostMapping
    @RequirePermission("user:create")
    public ResponseEntity<User> createUser(
            @RequestParam String username,
            @RequestParam String email,
            @RequestParam String password,
            @RequestParam(required = false) String defaultRole) {
        
        User user = userService.createUser(username, email, password, defaultRole);
        return ResponseEntity.ok(user);
    }

    /**
     * Update user password - requires user update permission
     */
    @PutMapping("/{userId}/password")
    @RequirePermission("user:update")
    public ResponseEntity<String> updateUserPassword(
            @PathVariable Long userId,
            @RequestParam String oldPassword,
            @RequestParam String newPassword) {
        
        boolean success = userService.updatePassword(userId, oldPassword, newPassword);
        if (success) {
            return ResponseEntity.ok("Password updated successfully");
        } else {
            return ResponseEntity.badRequest().body("Password update failed");
        }
    }

    /**
     * Lock user account - requires user update permission
     */
    @PostMapping("/{userId}/lock")
    @RequirePermission("user:update")
    public ResponseEntity<String> lockUser(
            @PathVariable Long userId,
            @RequestParam String reason) {
        
        userService.lockAccount(userId, reason);
        return ResponseEntity.ok("User account locked");
    }

    /**
     * Unlock user account - requires user update permission
     */
    @PostMapping("/{userId}/unlock")
    @RequirePermission("user:update")
    public ResponseEntity<String> unlockUser(@PathVariable Long userId) {
        userService.unlockAccount(userId);
        return ResponseEntity.ok("User account unlocked");
    }

    /**
     * Assign role to user - requires role assignment permission
     */
    @PostMapping("/{userId}/roles/{roleId}")
    @RequirePermission("user:role:assign")
    public ResponseEntity<String> assignRoleToUser(
            @PathVariable Long userId,
            @PathVariable Long roleId) {
        
        userService.assignRole(userId, roleId);
        return ResponseEntity.ok("Role assigned to user");
    }

    /**
     * Remove role from user - requires role assignment permission
     */
    @DeleteMapping("/{userId}/roles/{roleId}")
    @RequirePermission("user:role:assign")
    public ResponseEntity<String> removeRoleFromUser(
            @PathVariable Long userId,
            @PathVariable Long roleId) {
        
        userService.removeRole(userId, roleId);
        return ResponseEntity.ok("Role removed from user");
    }

    /**
     * Get user roles - requires user read permission
     */
    @GetMapping("/{userId}/roles")
    @RequirePermission("user:read")
    public ResponseEntity<List<Role>> getUserRoles(@PathVariable Long userId) {
        List<Role> roles = userService.getUserRoles(userId);
        return ResponseEntity.ok(roles);
    }

    /**
     * Get user permissions - requires user read permission
     */
    @GetMapping("/{userId}/permissions")
    @RequirePermission("user:read")
    public ResponseEntity<List<String>> getUserPermissions(@PathVariable Long userId) {
        List<String> permissions = userService.getUserPermissions(userId);
        return ResponseEntity.ok(permissions);
    }

    /**
     * Check if user has specific permission - requires user read permission
     */
    @GetMapping("/{userId}/permissions/{permission}/check")
    @RequirePermission("user:read")
    public ResponseEntity<Boolean> checkUserPermission(
            @PathVariable Long userId,
            @PathVariable String permission) {
        
        boolean hasPermission = userService.hasPermission(userId, permission);
        return ResponseEntity.ok(hasPermission);
    }

    /**
     * Enable MFA for user - requires MFA management permission
     */
    @PostMapping("/{userId}/mfa/enable")
    @RequirePermission("security:mfa:manage")
    public ResponseEntity<String> enableMfaForUser(
            @PathVariable Long userId,
            @RequestParam String secret) {
        
        userService.enableMfa(userId, secret);
        return ResponseEntity.ok("MFA enabled for user");
    }

    /**
     * Disable MFA for user - requires MFA management permission
     */
    @PostMapping("/{userId}/mfa/disable")
    @RequirePermission("security:mfa:manage")
    public ResponseEntity<String> disableMfaForUser(@PathVariable Long userId) {
        userService.disableMfa(userId);
        return ResponseEntity.ok("MFA disabled for user");
    }

    /**
     * Get users by role - requires user read permission
     */
    @GetMapping("/by-role/{roleName}")
    @RequirePermission("user:read")
    public ResponseEntity<List<User>> getUsersByRole(@PathVariable String roleName) {
        List<User> users = userService.findUsersByRole(roleName);
        return ResponseEntity.ok(users);
    }
}