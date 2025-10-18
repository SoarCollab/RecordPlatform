package cn.flying.monitor.common.security;

import cn.flying.monitor.common.dto.MfaSetupResponse;
import cn.flying.monitor.common.entity.User;
import cn.flying.monitor.common.service.UserService;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced Multi-Factor Authentication service with User entity integration
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MfaService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final UserService userService;
    
    @Value("${monitor.mfa.issuer:Monitor System}")
    private String issuer;
    
    private static final String MFA_SECRET_PREFIX = "mfa:secret:";
    private static final String MFA_BACKUP_CODES_PREFIX = "mfa:backup:";
    private static final String MFA_ATTEMPT_PREFIX = "mfa:attempt:";
    private static final int TOTP_WINDOW = 30; // 30秒窗口
    private static final int BACKUP_CODES_COUNT = 10;
    private static final int MAX_ATTEMPTS = 5;

    /**
     * 生成MFA密钥
     */
    public String generateMfaSecret() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * 为用户设置MFA密钥
     */
    public void setupMfaForUser(String userId, String secret) {
        redisTemplate.opsForValue().set(MFA_SECRET_PREFIX + userId, secret);
        log.info("MFA secret set for user: {}", userId);
    }

    /**
     * 生成备份码
     */
    public List<String> generateBackupCodes(String userId) {
        List<String> backupCodes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODES_COUNT; i++) {
            backupCodes.add(RandomUtil.randomString(8));
        }
        
        // 存储备份码的哈希值
        List<String> hashedCodes = backupCodes.stream()
                .map(DigestUtil::sha256Hex)
                .toList();
        
        redisTemplate.opsForValue().set(MFA_BACKUP_CODES_PREFIX + userId, hashedCodes);
        log.info("Generated {} backup codes for user: {}", BACKUP_CODES_COUNT, userId);
        
        return backupCodes;
    }

    /**
     * 验证TOTP码
     */
    public boolean verifyTotpCode(String userId, String code) {
        if (isUserLocked(userId)) {
            log.warn("User {} is locked due to too many MFA attempts", userId);
            return false;
        }

        String secret = (String) redisTemplate.opsForValue().get(MFA_SECRET_PREFIX + userId);
        if (secret == null) {
            log.warn("No MFA secret found for user: {}", userId);
            return false;
        }

        try {
            long currentTime = System.currentTimeMillis() / 1000L / TOTP_WINDOW;
            
            // 检查当前时间窗口和前后一个窗口
            for (int i = -1; i <= 1; i++) {
                String expectedCode = generateTotpCode(secret, currentTime + i);
                if (code.equals(expectedCode)) {
                    resetFailedAttempts(userId);
                    log.info("TOTP verification successful for user: {}", userId);
                    return true;
                }
            }
            
            incrementFailedAttempts(userId);
            log.warn("TOTP verification failed for user: {}", userId);
            return false;
            
        } catch (Exception e) {
            log.error("Error verifying TOTP code for user: {}", userId, e);
            return false;
        }
    }

    /**
     * 验证备份码
     */
    @SuppressWarnings("unchecked")
    public boolean verifyBackupCode(String userId, String code) {
        if (isUserLocked(userId)) {
            log.warn("User {} is locked due to too many MFA attempts", userId);
            return false;
        }

        List<String> backupCodes = (List<String>) redisTemplate.opsForValue()
                .get(MFA_BACKUP_CODES_PREFIX + userId);
        
        if (backupCodes == null || backupCodes.isEmpty()) {
            log.warn("No backup codes found for user: {}", userId);
            return false;
        }

        String hashedCode = DigestUtil.sha256Hex(code);
        if (backupCodes.contains(hashedCode)) {
            // 移除已使用的备份码
            backupCodes.remove(hashedCode);
            redisTemplate.opsForValue().set(MFA_BACKUP_CODES_PREFIX + userId, backupCodes);
            
            resetFailedAttempts(userId);
            log.info("Backup code verification successful for user: {}", userId);
            return true;
        }

        incrementFailedAttempts(userId);
        log.warn("Backup code verification failed for user: {}", userId);
        return false;
    }

    /**
     * 生成TOTP码
     */
    private String generateTotpCode(String secret, long timeCounter) 
            throws NoSuchAlgorithmException, InvalidKeyException {
        
        byte[] key = Base64.getDecoder().decode(secret);
        byte[] data = ByteBuffer.allocate(8).putLong(timeCounter).array();
        
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        
        int offset = hash[hash.length - 1] & 0x0F;
        int code = ((hash[offset] & 0x7F) << 24) |
                   ((hash[offset + 1] & 0xFF) << 16) |
                   ((hash[offset + 2] & 0xFF) << 8) |
                   (hash[offset + 3] & 0xFF);
        
        code = code % 1000000;
        return String.format("%06d", code);
    }

    /**
     * 检查用户是否被锁定
     */
    private boolean isUserLocked(String userId) {
        Integer attempts = (Integer) redisTemplate.opsForValue().get(MFA_ATTEMPT_PREFIX + userId);
        return attempts != null && attempts >= MAX_ATTEMPTS;
    }

    /**
     * 增加失败尝试次数
     */
    private void incrementFailedAttempts(String userId) {
        String key = MFA_ATTEMPT_PREFIX + userId;
        Integer attempts = (Integer) redisTemplate.opsForValue().get(key);
        attempts = (attempts == null) ? 1 : attempts + 1;
        
        redisTemplate.opsForValue().set(key, attempts, Duration.ofMinutes(15));
        
        if (attempts >= MAX_ATTEMPTS) {
            log.warn("User {} locked due to {} failed MFA attempts", userId, attempts);
        }
    }

    /**
     * 重置失败尝试次数
     */
    private void resetFailedAttempts(String userId) {
        redisTemplate.delete(MFA_ATTEMPT_PREFIX + userId);
    }

    /**
     * 检查用户是否启用了MFA
     */
    public boolean isMfaEnabled(String userId) {
        return redisTemplate.hasKey(MFA_SECRET_PREFIX + userId);
    }

    /**
     * 禁用用户MFA
     */
    public void disableMfaForUser(String userId) {
        redisTemplate.delete(MFA_SECRET_PREFIX + userId);
        redisTemplate.delete(MFA_BACKUP_CODES_PREFIX + userId);
        redisTemplate.delete(MFA_ATTEMPT_PREFIX + userId);
        log.info("MFA disabled for user: {}", userId);
    }
    
    /**
     * Setup MFA for user with User entity integration
     */
    public MfaSetupResponse setupMfaForUser(Long userId, String username) {
        String secret = generateMfaSecret();
        List<String> backupCodes = generateBackupCodes(userId.toString());
        
        // Store in Redis temporarily until user confirms
        redisTemplate.opsForValue().set(MFA_SECRET_PREFIX + "temp:" + userId, secret, Duration.ofMinutes(10));
        
        String qrCodeUrl = generateQrCodeUrl(username, secret);
        
        return new MfaSetupResponse(secret, qrCodeUrl, backupCodes, false);
    }
    
    /**
     * Confirm MFA setup after user verifies TOTP code
     */
    public boolean confirmMfaSetup(Long userId, String totpCode) {
        String tempSecret = (String) redisTemplate.opsForValue().get(MFA_SECRET_PREFIX + "temp:" + userId);
        if (tempSecret == null) {
            log.warn("No temporary MFA secret found for user: {}", userId);
            return false;
        }
        
        // Verify the TOTP code with temporary secret
        if (verifyTotpCodeWithSecret(tempSecret, totpCode)) {
            // Move secret from temporary to permanent storage
            userService.enableMfa(userId, tempSecret);
            redisTemplate.delete(MFA_SECRET_PREFIX + "temp:" + userId);
            
            log.info("MFA setup confirmed for user: {}", userId);
            return true;
        }
        
        return false;
    }
    
    /**
     * Disable MFA for user with User entity integration
     */
    public void disableMfaForUser(Long userId) {
        userService.disableMfa(userId);
        redisTemplate.delete(MFA_SECRET_PREFIX + userId);
        redisTemplate.delete(MFA_BACKUP_CODES_PREFIX + userId);
        redisTemplate.delete(MFA_ATTEMPT_PREFIX + userId);
        log.info("MFA disabled for user: {}", userId);
    }
    
    /**
     * Verify MFA code for user (TOTP or backup code)
     */
    public boolean verifyMfaCode(Long userId, String code, boolean isBackupCode) {
        Optional<User> userOpt = userService.findByIdWithRoles(userId);
        if (userOpt.isEmpty() || !userOpt.get().requiresMfa()) {
            return false;
        }
        
        if (isBackupCode) {
            return userService.useBackupCode(userId, code);
        } else {
            return verifyTotpCodeForUser(userId, code);
        }
    }
    
    /**
     * Verify TOTP code for specific user
     */
    public boolean verifyTotpCodeForUser(Long userId, String code) {
        Optional<User> userOpt = userService.findByIdWithRoles(userId);
        if (userOpt.isEmpty() || userOpt.get().getMfaSecret() == null) {
            return false;
        }
        
        return verifyTotpCodeWithSecret(userOpt.get().getMfaSecret(), code);
    }
    
    /**
     * Verify TOTP code with given secret
     */
    private boolean verifyTotpCodeWithSecret(String secret, String code) {
        if (isUserLocked(secret)) {
            log.warn("User is locked due to too many MFA attempts");
            return false;
        }

        try {
            long currentTime = System.currentTimeMillis() / 1000L / TOTP_WINDOW;
            
            // 检查当前时间窗口和前后一个窗口
            for (int i = -1; i <= 1; i++) {
                String expectedCode = generateTotpCode(secret, currentTime + i);
                if (code.equals(expectedCode)) {
                    resetFailedAttempts(secret);
                    log.info("TOTP verification successful");
                    return true;
                }
            }
            
            incrementFailedAttempts(secret);
            log.warn("TOTP verification failed");
            return false;
            
        } catch (Exception e) {
            log.error("Error verifying TOTP code", e);
            return false;
        }
    }
    
    /**
     * Generate QR code URL for TOTP setup
     */
    private String generateQrCodeUrl(String username, String secret) {
        try {
            String encodedIssuer = URLEncoder.encode(issuer, "UTF-8");
            String encodedUsername = URLEncoder.encode(username, "UTF-8");
            
            return String.format(
                "otpauth://totp/%s:%s?secret=%s&issuer=%s&algorithm=SHA1&digits=6&period=30",
                encodedIssuer, encodedUsername, secret, encodedIssuer
            );
        } catch (UnsupportedEncodingException e) {
            log.error("Error generating QR code URL", e);
            return null;
        }
    }
    
    /**
     * Generate new backup codes for user
     */
    public List<String> regenerateBackupCodes(Long userId) {
        List<String> backupCodes = new ArrayList<>();
        for (int i = 0; i < BACKUP_CODES_COUNT; i++) {
            backupCodes.add(RandomUtil.randomString(8));
        }
        userService.setBackupCodes(userId, backupCodes);
        log.info("Regenerated backup codes for user: {}", userId);
        return backupCodes;
    }
    
    /**
     * Check if user has MFA enabled
     */
    public boolean isMfaEnabledForUser(Long userId) {
        Optional<User> userOpt = userService.findByIdWithRoles(userId);
        return userOpt.map(User::requiresMfa).orElse(false);
    }

    /**
     * 检查用户是否存在备份码
     */
    @SuppressWarnings("unchecked")
    public boolean hasBackupCodes(Long userId) {
        Object val = redisTemplate.opsForValue().get(MFA_BACKUP_CODES_PREFIX + userId);
        if (val instanceof List<?>) {
            return !((List<?>) val).isEmpty();
        }
        return false;
    }
}