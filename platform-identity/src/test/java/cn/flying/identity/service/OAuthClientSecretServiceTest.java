package cn.flying.identity.service;

import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.service.impl.OAuthClientSecretServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * OAuth客户端密钥服务单元测试
 * 测试范围：密钥生成、加密、验证、强度检测
 *
 * @author 王贝强
 * @create 2025-01-13
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthClientSecretServiceTest {

    @InjectMocks
    private OAuthClientSecretServiceImpl oauthClientSecretService;

    @Mock
    private OAuthConfig oauthConfig;

    @Mock
    private PasswordService passwordService;

    // 测试数据常量
    private static final String TEST_RAW_SECRET = "testClientSecret123456789012";
    private static final String TEST_BCRYPT_SECRET = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"; // 真实的60字符BCrypt哈希
    private static final String WEAK_SECRET_SHORT = "weak123";
    private static final String WEAK_SECRET_NO_DIGIT = "weakpasswordonly";
    private static final String WEAK_SECRET_NO_LETTER = "123456789012345678";
    private static final String STRONG_SECRET = "StrongSecret123456789";

    @BeforeEach
    void setUp() {
        // 默认配置：启用BCrypt加密
        when(oauthConfig.isUseBcrypt()).thenReturn(true);
    }

    @Test
    void testGenerateClientSecret_Success() {
        // 执行测试：生成新的客户端密钥
        String secret = oauthClientSecretService.generateClientSecret();

        // 验证结果
        assertNotNull(secret);
        assertEquals(32, secret.length()); // 密钥长度应为32
        assertTrue(secret.matches("[A-Za-z0-9]+"));  // 只包含字母和数字

        // 验证密钥强度
        assertTrue(oauthClientSecretService.validateSecretStrength(secret));
    }

    @Test
    void testGenerateClientSecret_Uniqueness() {
        // 执行测试：生成多个密钥，验证唯一性
        String secret1 = oauthClientSecretService.generateClientSecret();
        String secret2 = oauthClientSecretService.generateClientSecret();

        // 验证结果：两次生成的密钥应该不同
        assertNotEquals(secret1, secret2);
    }

    @Test
    void testEncodeClientSecret_WithBCrypt() {
        // 配置：启用BCrypt加密
        when(oauthConfig.isUseBcrypt()).thenReturn(true);
        when(passwordService.encode(TEST_RAW_SECRET))
                .thenReturn(TEST_BCRYPT_SECRET);

        // 执行测试
        String encoded = oauthClientSecretService.encodeClientSecret(TEST_RAW_SECRET);

        // 验证结果
        assertEquals(TEST_BCRYPT_SECRET, encoded);

        // 验证passwordService被调用
        verify(passwordService).encode(TEST_RAW_SECRET);
    }

    @Test
    void testEncodeClientSecret_WithoutBCrypt() {
        // 配置：禁用BCrypt加密（明文存储）
        when(oauthConfig.isUseBcrypt()).thenReturn(false);

        // 执行测试
        String encoded = oauthClientSecretService.encodeClientSecret(TEST_RAW_SECRET);

        // 验证结果：应该返回原始密钥
        assertEquals(TEST_RAW_SECRET, encoded);

        // 验证passwordService未被调用
        verify(passwordService, never()).encode(anyString());
    }

    @Test
    void testEncodeClientSecret_NullSecret() {
        // 执行测试：null密钥应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            oauthClientSecretService.encodeClientSecret(null);
        });
    }

    @Test
    void testEncodeClientSecret_EmptySecret() {
        // 执行测试：空字符串密钥应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            oauthClientSecretService.encodeClientSecret("");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            oauthClientSecretService.encodeClientSecret("   ");
        });
    }

    @Test
    void testMatches_BCryptSecret_Success() {
        // Mock密钥验证
        when(passwordService.matches(TEST_RAW_SECRET, TEST_BCRYPT_SECRET))
                .thenReturn(true);

        // 执行测试：验证BCrypt加密的密钥
        boolean matches = oauthClientSecretService.matches(TEST_RAW_SECRET, TEST_BCRYPT_SECRET);

        // 验证结果
        assertTrue(matches);

        // 验证passwordService被调用
        verify(passwordService).matches(TEST_RAW_SECRET, TEST_BCRYPT_SECRET);
    }

    @Test
    void testMatches_BCryptSecret_Failure() {
        // Mock密钥验证失败
        when(passwordService.matches("wrongSecret", TEST_BCRYPT_SECRET))
                .thenReturn(false);

        // 执行测试：使用错误的密钥
        boolean matches = oauthClientSecretService.matches("wrongSecret", TEST_BCRYPT_SECRET);

        // 验证结果
        assertFalse(matches);
    }

    @Test
    void testMatches_PlainTextSecret_Success() {
        // 执行测试：验证明文密钥（向后兼容）
        boolean matches = oauthClientSecretService.matches(TEST_RAW_SECRET, TEST_RAW_SECRET);

        // 验证结果
        assertTrue(matches);

        // 验证passwordService未被调用（因为是明文比较）
        verify(passwordService, never()).matches(anyString(), anyString());
    }

    @Test
    void testMatches_PlainTextSecret_Failure() {
        // 执行测试：明文密钥不匹配
        boolean matches = oauthClientSecretService.matches("wrongSecret", TEST_RAW_SECRET);

        // 验证结果
        assertFalse(matches);
    }

    @Test
    void testMatches_NullInput() {
        // 执行测试：null输入应该返回false
        assertFalse(oauthClientSecretService.matches(null, TEST_BCRYPT_SECRET));
        assertFalse(oauthClientSecretService.matches(TEST_RAW_SECRET, null));
        assertFalse(oauthClientSecretService.matches(null, null));
    }

    @Test
    void testIsEncrypted_BCryptFormat() {
        // 执行测试：检测BCrypt格式
        assertTrue(oauthClientSecretService.isEncrypted(TEST_BCRYPT_SECRET));

        // 测试其他BCrypt前缀（都是真实的60字符BCrypt哈希）
        assertTrue(oauthClientSecretService.isEncrypted("$2b$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
        assertTrue(oauthClientSecretService.isEncrypted("$2x$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
        assertTrue(oauthClientSecretService.isEncrypted("$2y$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"));
    }

    @Test
    void testIsEncrypted_PlainText() {
        // 执行测试：明文密钥应该返回false
        assertFalse(oauthClientSecretService.isEncrypted(TEST_RAW_SECRET));
        assertFalse(oauthClientSecretService.isEncrypted("plainTextSecret123"));
        assertFalse(oauthClientSecretService.isEncrypted(null));
        assertFalse(oauthClientSecretService.isEncrypted(""));
    }

    @Test
    void testValidateSecretStrength_Strong() {
        // 执行测试：强密钥应该通过验证
        assertTrue(oauthClientSecretService.validateSecretStrength(STRONG_SECRET));
        assertTrue(oauthClientSecretService.validateSecretStrength(TEST_RAW_SECRET));

        // 生成的密钥也应该通过验证
        String generated = oauthClientSecretService.generateClientSecret();
        assertTrue(oauthClientSecretService.validateSecretStrength(generated));
    }

    @Test
    void testValidateSecretStrength_Weak() {
        // 执行测试：弱密钥应该验证失败

        // 长度不足（<16位）
        assertFalse(oauthClientSecretService.validateSecretStrength(WEAK_SECRET_SHORT));

        // 没有数字
        assertFalse(oauthClientSecretService.validateSecretStrength(WEAK_SECRET_NO_DIGIT));

        // 没有字母
        assertFalse(oauthClientSecretService.validateSecretStrength(WEAK_SECRET_NO_LETTER));

        // null和空字符串
        assertFalse(oauthClientSecretService.validateSecretStrength(null));
        assertFalse(oauthClientSecretService.validateSecretStrength(""));
    }

    @Test
    void testValidateSecretStrength_EdgeCases() {
        // 执行测试：边界情况

        // 恰好16位且包含字母数字
        assertTrue(oauthClientSecretService.validateSecretStrength("Abc1234567890123"));

        // 15位（不足）
        assertFalse(oauthClientSecretService.validateSecretStrength("Abc123456789012"));

        // 16位但只有字母
        assertFalse(oauthClientSecretService.validateSecretStrength("Abcdefghijklmnop"));

        // 16位但只有数字
        assertFalse(oauthClientSecretService.validateSecretStrength("1234567890123456"));
    }
}
