package cn.flying.monitor.common.test;

import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.entity.SecurityAuditLog;
import cn.hutool.core.util.RandomUtil;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 测试数据工厂
 */
public class TestDataFactory {

    /**
     * 创建测试用户
     */
    public static User createTestUser() {
        return User.builder()
                .username("test_user_" + RandomUtil.randomString(6))
                .passwordHash("$2a$10$test.hash.value")
                .email("test@example.com")
                .phone("13800138000")
                .realName("测试用户")
                .status(User.UserStatus.ACTIVE)
                .mfaEnabled(false)
                .lastLoginTime(Instant.now().minus(1, ChronoUnit.HOURS))
                .lastLoginIp("127.0.0.1")
                .passwordExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS))
                .failedLoginAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deleted(false)
                .build();
    }

    /**
     * 创建管理员用户
     */
    public static User createAdminUser() {
        return User.builder()
                .username("admin_" + RandomUtil.randomString(6))
                .passwordHash("$2a$10$admin.hash.value")
                .email("admin@example.com")
                .phone("13900139000")
                .realName("管理员")
                .status(User.UserStatus.ACTIVE)
                .mfaEnabled(true)
                .lastLoginTime(Instant.now().minus(30, ChronoUnit.MINUTES))
                .lastLoginIp("192.168.1.100")
                .passwordExpiresAt(Instant.now().plus(90, ChronoUnit.DAYS))
                .failedLoginAttempts(0)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deleted(false)
                .build();
    }

    /**
     * 创建锁定用户
     */
    public static User createLockedUser() {
        return User.builder()
                .username("locked_user_" + RandomUtil.randomString(6))
                .passwordHash("$2a$10$locked.hash.value")
                .email("locked@example.com")
                .phone("13700137000")
                .realName("锁定用户")
                .status(User.UserStatus.LOCKED)
                .mfaEnabled(false)
                .lastLoginTime(Instant.now().minus(2, ChronoUnit.HOURS))
                .lastLoginIp("192.168.1.200")
                .lockedUntil(Instant.now().plus(1, ChronoUnit.HOURS))
                .failedLoginAttempts(5)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .deleted(false)
                .build();
    }

    /**
     * 创建安全审计日志
     */
    public static SecurityAuditLog createAuditLog(SecurityAuditLog.EventType eventType, boolean success) {
        SecurityAuditLog log = SecurityAuditLog.builder()
                .eventType(eventType)
                .userId("test_user_123")
                .username("test_user")
                .clientIp("192.168.1.100")
                .userAgent("Mozilla/5.0 Test Browser")
                .resource("test_resource")
                .action("test_action")
                .success(success)
                .details("测试审计日志详情")
                .timestamp(Instant.now())
                .sessionId("session_" + RandomUtil.randomString(10))
                .requestId("req_" + RandomUtil.randomString(10))
                .createdAt(Instant.now())
                .build();
        
        log.setRiskLevelByEvent();
        return log;
    }

    /**
     * 创建登录成功审计日志
     */
    public static SecurityAuditLog createLoginSuccessLog(String userId, String username) {
        return SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.LOGIN_ATTEMPT)
                .userId(userId)
                .username(username)
                .clientIp("192.168.1.100")
                .userAgent("Mozilla/5.0 Test Browser")
                .success(true)
                .details("登录成功")
                .riskLevel(SecurityAuditLog.RiskLevel.LOW)
                .timestamp(Instant.now())
                .sessionId("session_" + RandomUtil.randomString(10))
                .requestId("req_" + RandomUtil.randomString(10))
                .createdAt(Instant.now())
                .build();
    }

    /**
     * 创建登录失败审计日志
     */
    public static SecurityAuditLog createLoginFailureLog(String userId, String username, String reason) {
        return SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.LOGIN_ATTEMPT)
                .userId(userId)
                .username(username)
                .clientIp("192.168.1.100")
                .userAgent("Mozilla/5.0 Test Browser")
                .success(false)
                .details("登录失败: " + reason)
                .riskLevel(SecurityAuditLog.RiskLevel.MEDIUM)
                .timestamp(Instant.now())
                .sessionId("session_" + RandomUtil.randomString(10))
                .requestId("req_" + RandomUtil.randomString(10))
                .createdAt(Instant.now())
                .build();
    }

    /**
     * 创建MFA验证日志
     */
    public static SecurityAuditLog createMfaVerificationLog(String userId, String username, boolean success) {
        return SecurityAuditLog.builder()
                .eventType(SecurityAuditLog.EventType.MFA_VERIFICATION)
                .userId(userId)
                .username(username)
                .clientIp("192.168.1.100")
                .success(success)
                .details("MFA验证 - TOTP: " + (success ? "成功" : "失败"))
                .riskLevel(success ? SecurityAuditLog.RiskLevel.LOW : SecurityAuditLog.RiskLevel.HIGH)
                .timestamp(Instant.now())
                .sessionId("session_" + RandomUtil.randomString(10))
                .requestId("req_" + RandomUtil.randomString(10))
                .createdAt(Instant.now())
                .build();
    }
}