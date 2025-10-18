package cn.flying.monitor.common.security;

import cn.flying.monitor.common.test.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MFA服务测试
 */
class MfaServiceTest extends BaseIntegrationTest {

    @Autowired
    private MfaService mfaService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private final String testUserId = "test_user_123";

    @BeforeEach
    void setUp() {
        // 清理测试数据
        redisTemplate.delete("mfa:secret:" + testUserId);
        redisTemplate.delete("mfa:backup:" + testUserId);
        redisTemplate.delete("mfa:attempt:" + testUserId);
    }

    @Test
    void shouldGenerateMfaSecret() {
        // When
        String secret = mfaService.generateMfaSecret();

        // Then
        assertNotNull(secret);
        assertFalse(secret.isEmpty());
        assertTrue(secret.length() > 20); // Base64编码的20字节应该大于20个字符
    }

    @Test
    void shouldSetupMfaForUser() {
        // Given
        String secret = mfaService.generateMfaSecret();

        // When
        mfaService.setupMfaForUser(testUserId, secret);

        // Then
        assertTrue(mfaService.isMfaEnabled(testUserId));
    }

    @Test
    void shouldGenerateBackupCodes() {
        // When
        List<String> backupCodes = mfaService.generateBackupCodes(testUserId);

        // Then
        assertNotNull(backupCodes);
        assertEquals(10, backupCodes.size());
        backupCodes.forEach(code -> {
            assertNotNull(code);
            assertEquals(8, code.length());
        });
    }

    @Test
    void shouldVerifyBackupCodeCorrectly() {
        // Given
        List<String> backupCodes = mfaService.generateBackupCodes(testUserId);
        String validCode = backupCodes.get(0);
        String invalidCode = "invalid123";

        // When & Then
        assertTrue(mfaService.verifyBackupCode(testUserId, validCode));
        assertFalse(mfaService.verifyBackupCode(testUserId, invalidCode));
        
        // 验证备份码只能使用一次
        assertFalse(mfaService.verifyBackupCode(testUserId, validCode));
    }

    @Test
    void shouldHandleFailedAttemptsCorrectly() {
        // Given
        String secret = mfaService.generateMfaSecret();
        mfaService.setupMfaForUser(testUserId, secret);

        // When - 多次失败尝试
        for (int i = 0; i < 5; i++) {
            assertFalse(mfaService.verifyTotpCode(testUserId, "000000"));
        }

        // Then - 用户应该被锁定
        assertFalse(mfaService.verifyTotpCode(testUserId, "000000"));
    }

    @Test
    void shouldDisableMfaForUser() {
        // Given
        String secret = mfaService.generateMfaSecret();
        mfaService.setupMfaForUser(testUserId, secret);
        mfaService.generateBackupCodes(testUserId);
        assertTrue(mfaService.isMfaEnabled(testUserId));

        // When
        mfaService.disableMfaForUser(testUserId);

        // Then
        assertFalse(mfaService.isMfaEnabled(testUserId));
    }

    @Test
    void shouldReturnFalseForNonExistentUser() {
        // Given
        String nonExistentUserId = "non_existent_user";

        // When & Then
        assertFalse(mfaService.isMfaEnabled(nonExistentUserId));
        assertFalse(mfaService.verifyTotpCode(nonExistentUserId, "123456"));
        assertFalse(mfaService.verifyBackupCode(nonExistentUserId, "testcode"));
    }
}