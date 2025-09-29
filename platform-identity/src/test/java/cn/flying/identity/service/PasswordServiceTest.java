package cn.flying.identity.service;

import cn.flying.identity.config.ApplicationProperties;
import cn.flying.identity.service.impl.PasswordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 密码服务单元测试
 * 测试范围：BCrypt密码加密、验证、异常处理
 *
 * @author 王贝强
 * @create 2025-01-13
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PasswordServiceTest {

    @InjectMocks
    private PasswordServiceImpl passwordService;

    @Mock
    private ApplicationProperties applicationProperties;

    @Mock
    private ApplicationProperties.Password passwordConfig;

    // 测试数据常量
    private static final String TEST_RAW_PASSWORD = "Test123456";
    private static final int DEFAULT_BCRYPT_STRENGTH = 10;

    @BeforeEach
    void setUp() {
        // 配置BCrypt强度
        when(applicationProperties.getPassword()).thenReturn(passwordConfig);
        when(passwordConfig.getStrength()).thenReturn(DEFAULT_BCRYPT_STRENGTH);
    }

    @Test
    void testEncode_Success() {
        // 执行测试：加密密码
        String encodedPassword = passwordService.encode(TEST_RAW_PASSWORD);

        // 验证结果
        assertNotNull(encodedPassword);
        assertTrue(encodedPassword.startsWith("$2a$")); // BCrypt格式
        assertNotEquals(TEST_RAW_PASSWORD, encodedPassword); // 加密后不等于原始密码

        // 验证配置被调用
        verify(applicationProperties).getPassword();
        verify(passwordConfig).getStrength();
    }

    @Test
    void testEncode_NullPassword() {
        // 执行测试：空密码应该抛出异常
        assertThrows(IllegalArgumentException.class, () -> {
            passwordService.encode(null);
        });
    }

    @Test
    void testMatches_Success() {
        // 准备测试数据：先加密密码
        String encodedPassword = passwordService.encode(TEST_RAW_PASSWORD);

        // 执行测试：验证密码
        boolean matches = passwordService.matches(TEST_RAW_PASSWORD, encodedPassword);

        // 验证结果
        assertTrue(matches);
    }

    @Test
    void testMatches_Failure() {
        // 准备测试数据：先加密密码
        String encodedPassword = passwordService.encode(TEST_RAW_PASSWORD);

        // 执行测试：使用错误的密码验证
        boolean matches = passwordService.matches("WrongPassword123", encodedPassword);

        // 验证结果
        assertFalse(matches);
    }

    @Test
    void testMatches_NullInput() {
        // 执行测试：null输入应该返回false
        assertFalse(passwordService.matches(null, "$2a$10$hashedpassword"));
        assertFalse(passwordService.matches(TEST_RAW_PASSWORD, null));
        assertFalse(passwordService.matches(null, null));
    }

    @Test
    void testEncode_DifferentResults() {
        // 执行测试：相同密码应该产生不同的哈希（因为盐值不同）
        String encoded1 = passwordService.encode(TEST_RAW_PASSWORD);
        String encoded2 = passwordService.encode(TEST_RAW_PASSWORD);

        // 验证结果
        assertNotEquals(encoded1, encoded2); // 不同的盐值导致不同的哈希

        // 但两者都应该能验证原始密码
        assertTrue(passwordService.matches(TEST_RAW_PASSWORD, encoded1));
        assertTrue(passwordService.matches(TEST_RAW_PASSWORD, encoded2));
    }
}
