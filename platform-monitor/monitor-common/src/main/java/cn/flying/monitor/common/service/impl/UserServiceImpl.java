package cn.flying.monitor.common.service.impl;

import cn.flying.monitor.common.entity.Role;
import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.entity.UserRole;
import cn.flying.monitor.common.mapper.UserMapper;
import cn.flying.monitor.common.mapper.RoleMapper;
import cn.flying.monitor.common.mapper.UserRoleMapper;
import cn.flying.monitor.common.security.RbacService;
import cn.flying.monitor.common.service.UserService;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Enhanced User service implementation with RBAC support
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final RbacService rbacService;
    private final PasswordEncoder passwordEncoder;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String USER_BACKUP_CODES_PREFIX = "user:backup:codes:";
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCKOUT_DURATION_MINUTES = 30;

    @Override
    public Optional<User> findByUsernameOrEmail(String usernameOrEmail) {
        User user = userMapper.findByUsernameOrEmail(usernameOrEmail);
        return Optional.ofNullable(user);
    }

    @Override
    public Optional<User> findByIdWithRoles(Long userId) {
        User user = userMapper.selectById(userId);
        if (user != null) {
            List<Role> roles = getUserRoles(userId);
            // Set roles in user object if needed
            log.debug("Found user {} with {} roles", user.getUsername(), roles.size());
        }
        return Optional.ofNullable(user);
    }

    @Override
    @Transactional
    public User createUser(String username, String email, String password, String defaultRole) {
        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .status(User.UserStatus.ACTIVE)
                .mfaEnabled(false)
                .failedLoginAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deleted(false)
                .build();

        userMapper.insert(user);

        // Assign default role if specified
        if (defaultRole != null) {
            Role role = roleMapper.findByCode(defaultRole);
            if (role != null) {
                assignRole(user.getId(), role.getId());
            }
        }

        log.info("Created user: {} with email: {}", username, email);
        return user;
    }

    @Override
    @Transactional
    public boolean updatePassword(Long userId, String oldPassword, String newPassword) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            log.warn("Password update failed - incorrect old password for user: {}", user.getUsername());
            return false;
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS)); // 90 days expiry
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);
        log.info("Password updated for user: {}", user.getUsername());
        return true;
    }

    @Override
    @Transactional
    public void lockAccount(Long userId, String reason) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        user.setStatus(User.UserStatus.LOCKED);
        user.setLockedUntil(Instant.now().plus(LOCKOUT_DURATION_MINUTES, ChronoUnit.MINUTES));
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);
        log.warn("Account locked for user: {} - Reason: {}", user.getUsername(), reason);
    }

    @Override
    @Transactional
    public void unlockAccount(Long userId) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        user.setStatus(User.UserStatus.ACTIVE);
        user.setLockedUntil(null);
        user.setFailedLoginAttempts(0);
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);
        log.info("Account unlocked for user: {}", user.getUsername());
    }

    @Override
    @Transactional
    public void incrementFailedLoginAttempts(Long userId) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        int attempts = (user.getFailedLoginAttempts() != null ? user.getFailedLoginAttempts() : 0) + 1;
        user.setFailedLoginAttempts(attempts);
        user.setUpdatedAt(Instant.now());

        if (attempts >= MAX_FAILED_ATTEMPTS) {
            lockAccount(userId, "Too many failed login attempts");
        } else {
            userMapper.updateById(user);
        }

        log.warn("Failed login attempt {} for user: {}", attempts, user.getUsername());
    }

    @Override
    @Transactional
    public void resetFailedLoginAttempts(Long userId) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        user.setFailedLoginAttempts(0);
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);
    }

    @Override
    @Transactional
    public void updateLastLogin(Long userId, String ipAddress) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        user.setLastLoginTime(Instant.now());
        user.setLastLoginIp(ipAddress);
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);
    }

    @Override
    @Transactional
    public void assignRole(Long userId, Long roleId) {
        // Check if assignment already exists
        UserRole existing = userRoleMapper.findByUserIdAndRoleId(userId, roleId);
        if (existing != null) {
            return;
        }

        UserRole userRole = UserRole.builder()
                .userId(userId)
                .roleId(roleId)
                .assignedAt(Instant.now())
                .deleted(false)
                .build();

        userRoleMapper.insert(userRole);

        // Update RBAC cache
        Role role = roleMapper.selectById(roleId);
        if (role != null) {
            rbacService.assignRoleToUser(userId.toString(), role.getCode());
        }

        log.info("Assigned role {} to user {}", roleId, userId);
    }

    @Override
    @Transactional
    public void removeRole(Long userId, Long roleId) {
        UserRole userRole = userRoleMapper.findByUserIdAndRoleId(userId, roleId);
        if (userRole != null) {
            userRoleMapper.deleteById(userRole.getId());

            // Update RBAC cache
            Role role = roleMapper.selectById(roleId);
            if (role != null) {
                rbacService.removeRoleFromUser(userId.toString(), role.getCode());
            }

            log.info("Removed role {} from user {}", roleId, userId);
        }
    }

    @Override
    public List<Role> getUserRoles(Long userId) {
        return roleMapper.findByUserId(userId);
    }

    @Override
    public List<String> getUserPermissions(Long userId) {
        return new ArrayList<>(rbacService.getUserPermissions(userId.toString()));
    }

    @Override
    public boolean hasPermission(Long userId, String permission) {
        return rbacService.hasPermission(userId.toString(), permission);
    }

    @Override
    @Transactional
    public void enableMfa(Long userId, String secret) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        user.setMfaEnabled(true);
        user.setMfaSecret(secret);
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);
        log.info("MFA enabled for user: {}", user.getUsername());
    }

    @Override
    @Transactional
    public void disableMfa(Long userId) {
        Optional<User> userOpt = findByIdWithRoles(userId);
        if (userOpt.isEmpty()) {
            return;
        }

        User user = userOpt.get();
        user.setMfaEnabled(false);
        user.setMfaSecret(null);
        user.setUpdatedAt(Instant.now());

        userMapper.updateById(user);

        // Clear backup codes
        redisTemplate.delete(USER_BACKUP_CODES_PREFIX + userId);

        log.info("MFA disabled for user: {}", user.getUsername());
    }

    @Override
    @Transactional
    public void setBackupCodes(Long userId, List<String> backupCodes) {
        // Hash backup codes before storing
        List<String> hashedCodes = backupCodes.stream()
                .map(DigestUtil::sha256Hex)
                .collect(Collectors.toList());

        redisTemplate.opsForValue().set(USER_BACKUP_CODES_PREFIX + userId, hashedCodes);
        log.info("Set {} backup codes for user: {}", backupCodes.size(), userId);
    }

    @Override
    @Transactional
    @SuppressWarnings("unchecked")
    public boolean useBackupCode(Long userId, String code) {
        List<String> backupCodes = (List<String>) redisTemplate.opsForValue()
                .get(USER_BACKUP_CODES_PREFIX + userId);

        if (backupCodes == null || backupCodes.isEmpty()) {
            return false;
        }

        String hashedCode = DigestUtil.sha256Hex(code);
        if (backupCodes.contains(hashedCode)) {
            // Remove used backup code
            backupCodes.remove(hashedCode);
            redisTemplate.opsForValue().set(USER_BACKUP_CODES_PREFIX + userId, backupCodes);

            log.info("Backup code used for user: {}", userId);
            return true;
        }

        return false;
    }

    @Override
    public List<User> findUsersByRole(String roleName) {
        return userMapper.findByRoleName(roleName);
    }

    @Override
    public List<User> findActiveUsers() {
        return userMapper.findByStatus(User.UserStatus.ACTIVE);
    }

    @Override
    @Transactional
    public User migrateFromAccount(Object legacyAccount) {
        log.warn("Account migration not implemented - account: {}", legacyAccount);
        return null;
    }
}