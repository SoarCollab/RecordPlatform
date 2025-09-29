package cn.flying.identity.util;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.constant.UserRole;
import cn.flying.identity.dto.Account;
import cn.flying.identity.mapper.AccountMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 安全工具类单元测试
 * 测试范围：用户角色获取、权限检查、登录状态检查、MDC操作
 *
 * @author 王贝强
 * @create 2025-01-14
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityUtilsTest {

    // 测试数据常量
    private static final Long TEST_USER_ID = 123L;
    private static final String TEST_USERNAME = "testuser";
    private static final String TEST_EMAIL = "test@example.com";
    private static final String TEST_TOKEN = "test-token-123";
    private static final String TEST_PERMISSION = "user:read";
    private static final String TEST_ROLE = "admin";
    @Mock
    private ApplicationContext applicationContext;
    @Mock
    private AccountMapper accountMapper;

    @BeforeEach
    void setUp() {
        // 设置ApplicationContext Mock
        when(applicationContext.getBean(AccountMapper.class)).thenReturn(accountMapper);

        // 通过反射设置静态字段
        ReflectionTestUtils.setField(SecurityUtils.class, "applicationContext", applicationContext);

        // 清理MDC
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        // 清理MDC
        MDC.clear();
    }

    // ==================== 获取用户角色测试 ====================

    @Test
    void testGetLoginUserRole_FromMDC() {
        // 在MDC中设置用户角色
        MDC.put("userRole", "admin");

        // 执行测试
        UserRole result = SecurityUtils.getLoginUserRole();

        // 验证结果
        assertEquals(UserRole.ROLE_ADMINISTER, result);
    }

    @Test
    void testGetLoginUserRole_FromSaToken() {
        // MDC中没有userRole
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            // Mock Sa-Token登录状态
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginId).thenReturn(TEST_USER_ID);

            // Mock数据库查询
            Account mockAccount = createMockAccount("user");
            when(accountMapper.selectById(TEST_USER_ID)).thenReturn(mockAccount);

            // 执行测试
            UserRole result = SecurityUtils.getLoginUserRole();

            // 验证结果
            assertEquals(UserRole.ROLE_DEFAULT, result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
            stpUtil.verify(StpUtil::getLoginId);
            verify(accountMapper).selectById(TEST_USER_ID);
        }
    }

    /**
     * 创建模拟的账户对象
     */
    private Account createMockAccount(String role) {
        Account account = new Account();
        account.setId(TEST_USER_ID);
        account.setUsername(TEST_USERNAME);
        account.setEmail(TEST_EMAIL);
        account.setRole(role);
        account.setDeleted(0);
        return account;
    }

    @Test
    void testGetLoginUserRole_NotLoggedIn() {
        // MDC中没有userRole，且用户未登录
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            UserRole result = SecurityUtils.getLoginUserRole();

            // 验证结果
            assertEquals(UserRole.ROLE_NOOP, result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
            stpUtil.verify(StpUtil::getLoginId, never());
            verify(accountMapper, never()).selectById(anyLong());
        }
    }

    @Test
    void testGetLoginUserRole_AccountNotFound() {
        // MDC中没有userRole，用户已登录但账户不存在
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginId).thenReturn(TEST_USER_ID);

            // Mock数据库查询返回null
            when(accountMapper.selectById(TEST_USER_ID)).thenReturn(null);

            // 执行测试
            UserRole result = SecurityUtils.getLoginUserRole();

            // 验证结果
            assertEquals(UserRole.ROLE_NOOP, result);

            // 验证方法调用
            verify(accountMapper).selectById(TEST_USER_ID);
        }
    }

    @Test
    void testGetLoginUserRole_ExceptionHandling() {
        // 模拟异常情况
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenThrow(new RuntimeException("Sa-Token异常"));

            // 执行测试
            UserRole result = SecurityUtils.getLoginUserRole();

            // 验证结果 - 异常时应返回默认角色
            assertEquals(UserRole.ROLE_NOOP, result);
        }
    }

    // ==================== 角色判断方法测试 ====================

    @Test
    void testGetLoginUserRole_ApplicationContextNull() {
        // 设置ApplicationContext为null
        ReflectionTestUtils.setField(SecurityUtils.class, "applicationContext", null);

        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginId).thenReturn(TEST_USER_ID);

            // 执行测试
            UserRole result = SecurityUtils.getLoginUserRole();

            // 验证结果 - ApplicationContext为null时应返回默认角色
            assertEquals(UserRole.ROLE_NOOP, result);
        }
    }

    @Test
    void testIsAdmin_True() {
        // 在MDC中设置管理员角色
        MDC.put("userRole", "admin");

        // 执行测试
        boolean result = SecurityUtils.isAdmin();

        // 验证结果
        assertTrue(result);
    }

    @Test
    void testIsAdmin_False() {
        // 在MDC中设置普通用户角色
        MDC.put("userRole", "user");

        // 执行测试
        boolean result = SecurityUtils.isAdmin();

        // 验证结果
        assertFalse(result);
    }

    @Test
    void testIsMonitor_True() {
        // 在MDC中设置监控员角色
        MDC.put("userRole", "monitor");

        // 执行测试
        boolean result = SecurityUtils.isMonitor();

        // 验证结果
        assertTrue(result);
    }

    @Test
    void testIsMonitor_False() {
        // 在MDC中设置普通用户角色
        MDC.put("userRole", "user");

        // 执行测试
        boolean result = SecurityUtils.isMonitor();

        // 验证结果
        assertFalse(result);
    }

    @Test
    void testIsUser_True() {
        // 在MDC中设置普通用户角色
        MDC.put("userRole", "user");

        // 执行测试
        boolean result = SecurityUtils.isUser();

        // 验证结果
        assertTrue(result);
    }

    // ==================== 获取用户ID测试 ====================

    @Test
    void testIsUser_False() {
        // 在MDC中设置管理员角色
        MDC.put("userRole", "admin");

        // 执行测试
        boolean result = SecurityUtils.isUser();

        // 验证结果
        assertFalse(result);
    }

    @Test
    void testGetLoginUserId_Success() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginId).thenReturn(TEST_USER_ID);

            // 执行测试
            Long result = SecurityUtils.getLoginUserId();

            // 验证结果
            assertEquals(TEST_USER_ID, result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
            stpUtil.verify(StpUtil::getLoginId);
        }
    }

    @Test
    void testGetLoginUserId_NotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            Long result = SecurityUtils.getLoginUserId();

            // 验证结果
            assertNull(result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
            stpUtil.verify(StpUtil::getLoginId, never());
        }
    }

    // ==================== 获取用户信息测试 ====================

    @Test
    void testGetLoginUserId_Exception() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenThrow(new RuntimeException("获取登录状态失败"));

            // 执行测试
            Long result = SecurityUtils.getLoginUserId();

            // 验证结果
            assertNull(result);
        }
    }

    @Test
    void testGetLoginUser_Success() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginId).thenReturn(TEST_USER_ID);

            // Mock数据库查询
            Account mockAccount = createMockAccount("user");
            when(accountMapper.selectById(TEST_USER_ID)).thenReturn(mockAccount);

            // 执行测试
            Account result = SecurityUtils.getLoginUser();

            // 验证结果
            assertNotNull(result);
            assertEquals(TEST_USER_ID, result.getId());
            assertEquals(TEST_USERNAME, result.getUsername());
            assertEquals(TEST_EMAIL, result.getEmail());

            // 验证方法调用
            verify(accountMapper).selectById(TEST_USER_ID);
        }
    }

    @Test
    void testGetLoginUser_NotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            Account result = SecurityUtils.getLoginUser();

            // 验证结果
            assertNull(result);

            // 验证数据库查询未被调用
            verify(accountMapper, never()).selectById(anyLong());
        }
    }

    // ==================== 权限检查测试 ====================

    @Test
    void testGetLoginUser_Exception() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getLoginId).thenThrow(new RuntimeException("获取用户ID失败"));

            // 执行测试
            Account result = SecurityUtils.getLoginUser();

            // 验证结果
            assertNull(result);
        }
    }

    @Test
    void testHasPermission_True() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.hasPermission(TEST_PERMISSION)).thenReturn(true);

            // 执行测试
            boolean result = SecurityUtils.hasPermission(TEST_PERMISSION);

            // 验证结果
            assertTrue(result);

            // 验证方法调用
            stpUtil.verify(() -> StpUtil.hasPermission(TEST_PERMISSION));
        }
    }

    @Test
    void testHasPermission_False() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.hasPermission(TEST_PERMISSION)).thenReturn(false);

            // 执行测试
            boolean result = SecurityUtils.hasPermission(TEST_PERMISSION);

            // 验证结果
            assertFalse(result);
        }
    }

    // ==================== 角色检查测试 ====================

    @Test
    void testHasPermission_Exception() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.hasPermission(TEST_PERMISSION))
                    .thenThrow(new RuntimeException("权限检查失败"));

            // 执行测试
            boolean result = SecurityUtils.hasPermission(TEST_PERMISSION);

            // 验证结果 - 异常时应返回false
            assertFalse(result);
        }
    }

    @Test
    void testHasRole_True() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.hasRole(TEST_ROLE)).thenReturn(true);

            // 执行测试
            boolean result = SecurityUtils.hasRole(TEST_ROLE);

            // 验证结果
            assertTrue(result);

            // 验证方法调用
            stpUtil.verify(() -> StpUtil.hasRole(TEST_ROLE));
        }
    }

    @Test
    void testHasRole_False() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.hasRole(TEST_ROLE)).thenReturn(false);

            // 执行测试
            boolean result = SecurityUtils.hasRole(TEST_ROLE);

            // 验证结果
            assertFalse(result);
        }
    }

    // ==================== 登录状态检查测试 ====================

    @Test
    void testHasRole_Exception() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(() -> StpUtil.hasRole(TEST_ROLE))
                    .thenThrow(new RuntimeException("角色检查失败"));

            // 执行测试
            boolean result = SecurityUtils.hasRole(TEST_ROLE);

            // 验证结果 - 异常时应返回false
            assertFalse(result);
        }
    }

    @Test
    void testIsLogin_True() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);

            // 执行测试
            boolean result = SecurityUtils.isLogin();

            // 验证结果
            assertTrue(result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
        }
    }

    @Test
    void testIsLogin_False() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            boolean result = SecurityUtils.isLogin();

            // 验证结果
            assertFalse(result);
        }
    }

    // ==================== Token获取测试 ====================

    @Test
    void testIsLogin_Exception() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin)
                    .thenThrow(new RuntimeException("检查登录状态失败"));

            // 执行测试
            boolean result = SecurityUtils.isLogin();

            // 验证结果 - 异常时应返回false
            assertFalse(result);
        }
    }

    @Test
    void testGetToken_Success() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getTokenValue).thenReturn(TEST_TOKEN);

            // 执行测试
            String result = SecurityUtils.getToken();

            // 验证结果
            assertEquals(TEST_TOKEN, result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
            stpUtil.verify(StpUtil::getTokenValue);
        }
    }

    @Test
    void testGetToken_NotLoggedIn() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(false);

            // 执行测试
            String result = SecurityUtils.getToken();

            // 验证结果
            assertNull(result);

            // 验证方法调用
            stpUtil.verify(StpUtil::isLogin);
            stpUtil.verify(StpUtil::getTokenValue, never());
        }
    }

    // ==================== MDC操作测试 ====================

    @Test
    void testGetToken_Exception() {
        try (MockedStatic<StpUtil> stpUtil = mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::isLogin).thenReturn(true);
            stpUtil.when(StpUtil::getTokenValue)
                    .thenThrow(new RuntimeException("获取Token失败"));

            // 执行测试
            String result = SecurityUtils.getToken();

            // 验证结果
            assertNull(result);
        }
    }

    @Test
    void testSetUserRoleToMDC() {
        // 执行测试
        SecurityUtils.setUserRoleToMDC("admin");

        // 验证结果
        assertEquals("admin", MDC.get("userRole"));
    }

    @Test
    void testSetUserIdToMDC() {
        // 执行测试
        SecurityUtils.setUserIdToMDC(TEST_USER_ID);

        // 验证结果
        assertEquals(TEST_USER_ID.toString(), MDC.get("userId"));
    }

    @Test
    void testSetUserIdToMDC_Null() {
        // 执行测试 - 传入null
        SecurityUtils.setUserIdToMDC(null);

        // 验证结果 - MDC中应该没有userId
        assertNull(MDC.get("userId"));
    }

    // ==================== 辅助方法 ====================

    @Test
    void testClearMDC() {
        // 预设MDC中的数据
        MDC.put("userId", TEST_USER_ID.toString());
        MDC.put("userRole", "admin");

        // 执行测试
        SecurityUtils.clearMDC();

        // 验证结果 - MDC应该被清空
        assertNull(MDC.get("userId"));
        assertNull(MDC.get("userRole"));
    }
}