package cn.flying.common.util;

import cn.flying.common.constant.UserRole;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SecurityUtils 测试类
 * 测试安全工具类的核心功能
 */
class SecurityUtilsTest {

    @AfterEach
    void tearDown() {
        // 清理MDC上下文
        MDC.clear();
    }

    @Test
    @DisplayName("测试获取登录用户角色 - 管理员")
    void testGetLoginUserRole_admin() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());

        // When
        UserRole role = SecurityUtils.getLoginUserRole();

        // Then
        assertEquals(UserRole.ROLE_ADMINISTER, role, "应该返回管理员角色");
    }

    @Test
    @DisplayName("测试获取登录用户角色 - 普通用户")
    void testGetLoginUserRole_user() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());

        // When
        UserRole role = SecurityUtils.getLoginUserRole();

        // Then
        assertEquals(UserRole.ROLE_DEFAULT, role, "应该返回普通用户角色");
    }

    @Test
    @DisplayName("测试获取登录用户角色 - null情况")
    void testGetLoginUserRole_null() {
        // Given - MDC中没有设置角色

        // When
        UserRole role = SecurityUtils.getLoginUserRole();

        // Then
        assertEquals(UserRole.ROLE_NOOP, role, "MDC中没有角色时应该返回ROLE_NOOP");
    }

    @Test
    @DisplayName("测试判断是否为管理员 - 是管理员")
    void testIsAdmin_true() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());

        // When
        boolean isAdmin = SecurityUtils.isAdmin();

        // Then
        assertTrue(isAdmin, "应该识别为管理员");
    }

    @Test
    @DisplayName("测试判断是否为管理员 - 不是管理员")
    void testIsAdmin_false() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());

        // When
        boolean isAdmin = SecurityUtils.isAdmin();

        // Then
        assertFalse(isAdmin, "普通用户不应该被识别为管理员");
    }

    @Test
    @DisplayName("测试判断是否为管理员 - null角色")
    void testIsAdmin_nullRole() {
        // Given - MDC中没有设置角色

        // When
        boolean isAdmin = SecurityUtils.isAdmin();

        // Then
        assertFalse(isAdmin, "null角色不应该被识别为管理员");
    }

    @Test
    @DisplayName("测试MDC上下文隔离")
    void testMDCContextIsolation() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());

        // When
        UserRole role1 = SecurityUtils.getLoginUserRole();
        
        // 修改MDC上下文
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());
        UserRole role2 = SecurityUtils.getLoginUserRole();

        // Then
        assertEquals(UserRole.ROLE_ADMINISTER, role1, "第一次应该是管理员");
        assertEquals(UserRole.ROLE_DEFAULT, role2, "第二次应该是普通用户");
    }

    @Test
    @DisplayName("测试多次调用getLoginUserRole的一致性")
    void testGetLoginUserRole_consistency() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());

        // When
        UserRole role1 = SecurityUtils.getLoginUserRole();
        UserRole role2 = SecurityUtils.getLoginUserRole();
        UserRole role3 = SecurityUtils.getLoginUserRole();

        // Then
        assertEquals(role1, role2, "多次调用应该返回相同的角色");
        assertEquals(role2, role3, "多次调用应该返回相同的角色");
    }

    @Test
    @DisplayName("测试多次调用isAdmin的一致性")
    void testIsAdmin_consistency() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());

        // When
        boolean result1 = SecurityUtils.isAdmin();
        boolean result2 = SecurityUtils.isAdmin();
        boolean result3 = SecurityUtils.isAdmin();

        // Then
        assertEquals(result1, result2, "多次调用应该返回相同的结果");
        assertEquals(result2, result3, "多次调用应该返回相同的结果");
        assertTrue(result1, "应该都是true");
    }

    @Test
    @DisplayName("测试清除MDC后获取角色")
    void testGetLoginUserRole_afterClear() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        UserRole roleBefore = SecurityUtils.getLoginUserRole();

        // When
        MDC.clear();
        UserRole roleAfter = SecurityUtils.getLoginUserRole();

        // Then
        assertEquals(UserRole.ROLE_ADMINISTER, roleBefore, "清除前应该有角色");
        assertEquals(UserRole.ROLE_NOOP, roleAfter, "清除后应该返回ROLE_NOOP");
    }

    @Test
    @DisplayName("测试无效角色字符串")
    void testGetLoginUserRole_invalidRole() {
        // Given
        MDC.put(Const.ATTR_USER_ROLE, "INVALID_ROLE");

        // When
        UserRole role = SecurityUtils.getLoginUserRole();

        // Then
        // 根据UserRole.getRole的实现，无效角色应该返回ROLE_NOOP
        assertEquals(UserRole.ROLE_NOOP, role, "无效角色应该返回ROLE_NOOP");
    }
}
