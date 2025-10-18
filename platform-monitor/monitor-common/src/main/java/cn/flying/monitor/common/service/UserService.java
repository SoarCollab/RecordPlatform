package cn.flying.monitor.common.service;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.entity.UserRole;

import java.util.List;
import java.util.Optional;

/**
 * Enhanced User service interface with RBAC support
 */
public interface UserService {
    
    /**
     * Find user by username or email
     */
    Optional<User> findByUsernameOrEmail(String usernameOrEmail);
    
    /**
     * Find user by ID with roles and permissions
     */
    Optional<User> findByIdWithRoles(Long userId);
    
    /**
     * Create new user with default role
     */
    User createUser(String username, String email, String password, String defaultRole);
    
    /**
     * Update user password with security checks
     */
    boolean updatePassword(Long userId, String oldPassword, String newPassword);
    
    /**
     * Lock user account
     */
    void lockAccount(Long userId, String reason);
    
    /**
     * Unlock user account
     */
    void unlockAccount(Long userId);
    
    /**
     * Increment failed login attempts
     */
    void incrementFailedLoginAttempts(Long userId);
    
    /**
     * Reset failed login attempts
     */
    void resetFailedLoginAttempts(Long userId);
    
    /**
     * Update last login information
     */
    void updateLastLogin(Long userId, String ipAddress);
    
    /**
     * Assign role to user
     */
    void assignRole(Long userId, Long roleId);
    
    /**
     * Remove role from user
     */
    void removeRole(Long userId, Long roleId);
    
    /**
     * Get user roles
     */
    List<Role> getUserRoles(Long userId);
    
    /**
     * Get user permissions (from all roles)
     */
    List<String> getUserPermissions(Long userId);
    
    /**
     * Check if user has permission
     */
    boolean hasPermission(Long userId, String permission);
    
    /**
     * Enable MFA for user
     */
    void enableMfa(Long userId, String secret);
    
    /**
     * Disable MFA for user
     */
    void disableMfa(Long userId);
    
    /**
     * Set backup codes for user
     */
    void setBackupCodes(Long userId, List<String> backupCodes);
    
    /**
     * Use backup code
     */
    boolean useBackupCode(Long userId, String code);
    
    /**
     * Find users by role
     */
    List<User> findUsersByRole(String roleName);
    
    /**
     * Get all active users
     */
    List<User> findActiveUsers();
    
    /**
     * Migrate existing account to new user system
     */
    default User migrateFromAccount(Object legacyAccount) {
        return null;
    }
}