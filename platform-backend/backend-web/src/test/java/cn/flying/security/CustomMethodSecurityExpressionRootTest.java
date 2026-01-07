package cn.flying.security;

import cn.flying.common.util.Const;
import cn.flying.service.PermissionService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * CustomMethodSecurityExpressionRoot Unit Tests
 *
 * Tests for custom SpEL expressions used in @PreAuthorize:
 * - hasPerm(code) - single permission check
 * - hasAnyPerm(codes...) - any permission check
 * - hasAllPerm(codes...) - all permissions check
 * - isOwner(ownerId) - resource ownership check
 * - isAdmin() - admin role check
 * - isMonitor() - monitor role check
 * - isAdminOrMonitor() - admin or monitor role check
 */
@DisplayName("CustomMethodSecurityExpressionRoot Tests")
@ExtendWith(MockitoExtension.class)
class CustomMethodSecurityExpressionRootTest {

    @Mock
    private PermissionService permissionService;

    private CustomMethodSecurityExpressionRoot expressionRoot;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        User user = new User("testuser", "password",
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        expressionRoot = new CustomMethodSecurityExpressionRoot(authentication, permissionService);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Nested
    @DisplayName("hasPerm Tests")
    class HasPermTests {

        @Test
        @DisplayName("should return true when user has permission")
        void hasPermission_returnsTrue() {
            when(permissionService.hasPermission("file:read")).thenReturn(true);

            boolean result = expressionRoot.hasPerm("file:read");

            assertThat(result).isTrue();
            verify(permissionService).hasPermission("file:read");
        }

        @Test
        @DisplayName("should return false when user lacks permission")
        void lacksPermission_returnsFalse() {
            when(permissionService.hasPermission("file:admin")).thenReturn(false);

            boolean result = expressionRoot.hasPerm("file:admin");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should handle null permission code gracefully")
        void nullPermissionCode_delegatesToService() {
            when(permissionService.hasPermission(null)).thenReturn(false);

            boolean result = expressionRoot.hasPerm(null);

            assertThat(result).isFalse();
            verify(permissionService).hasPermission(null);
        }
    }

    @Nested
    @DisplayName("hasAnyPerm Tests")
    class HasAnyPermTests {

        @Test
        @DisplayName("should return true when user has first permission")
        void hasFirstPermission_returnsTrue() {
            when(permissionService.hasPermission("file:read")).thenReturn(true);

            boolean result = expressionRoot.hasAnyPerm("file:read", "file:write", "file:delete");

            assertThat(result).isTrue();
            verify(permissionService).hasPermission("file:read");
            verify(permissionService, never()).hasPermission("file:write");
        }

        @Test
        @DisplayName("should return true when user has middle permission")
        void hasMiddlePermission_returnsTrue() {
            when(permissionService.hasPermission("file:read")).thenReturn(false);
            when(permissionService.hasPermission("file:write")).thenReturn(true);

            boolean result = expressionRoot.hasAnyPerm("file:read", "file:write", "file:delete");

            assertThat(result).isTrue();
            verify(permissionService).hasPermission("file:read");
            verify(permissionService).hasPermission("file:write");
            verify(permissionService, never()).hasPermission("file:delete");
        }

        @Test
        @DisplayName("should return true when user has last permission")
        void hasLastPermission_returnsTrue() {
            when(permissionService.hasPermission("file:read")).thenReturn(false);
            when(permissionService.hasPermission("file:write")).thenReturn(false);
            when(permissionService.hasPermission("file:delete")).thenReturn(true);

            boolean result = expressionRoot.hasAnyPerm("file:read", "file:write", "file:delete");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when user has no permissions")
        void hasNoPermissions_returnsFalse() {
            when(permissionService.hasPermission(anyString())).thenReturn(false);

            boolean result = expressionRoot.hasAnyPerm("file:read", "file:write", "file:delete");

            assertThat(result).isFalse();
            verify(permissionService, times(3)).hasPermission(anyString());
        }

        @Test
        @DisplayName("should return false for empty permission array")
        void emptyPermissions_returnsFalse() {
            boolean result = expressionRoot.hasAnyPerm();

            assertThat(result).isFalse();
            verifyNoInteractions(permissionService);
        }
    }

    @Nested
    @DisplayName("hasAllPerm Tests")
    class HasAllPermTests {

        @Test
        @DisplayName("should return true when user has all permissions")
        void hasAllPermissions_returnsTrue() {
            when(permissionService.hasPermission("file:read")).thenReturn(true);
            when(permissionService.hasPermission("file:write")).thenReturn(true);

            boolean result = expressionRoot.hasAllPerm("file:read", "file:write");

            assertThat(result).isTrue();
            verify(permissionService).hasPermission("file:read");
            verify(permissionService).hasPermission("file:write");
        }

        @Test
        @DisplayName("should return false when user lacks first permission")
        void lacksFirstPermission_returnsFalse() {
            when(permissionService.hasPermission("file:read")).thenReturn(false);

            boolean result = expressionRoot.hasAllPerm("file:read", "file:write");

            assertThat(result).isFalse();
            verify(permissionService).hasPermission("file:read");
            verify(permissionService, never()).hasPermission("file:write");
        }

        @Test
        @DisplayName("should return false when user lacks second permission")
        void lacksSecondPermission_returnsFalse() {
            when(permissionService.hasPermission("file:read")).thenReturn(true);
            when(permissionService.hasPermission("file:write")).thenReturn(false);

            boolean result = expressionRoot.hasAllPerm("file:read", "file:write");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true for empty permission array")
        void emptyPermissions_returnsTrue() {
            boolean result = expressionRoot.hasAllPerm();

            assertThat(result).isTrue();
            verifyNoInteractions(permissionService);
        }

        @Test
        @DisplayName("should return true for single permission when user has it")
        void singlePermissionPresent_returnsTrue() {
            when(permissionService.hasPermission("file:read")).thenReturn(true);

            boolean result = expressionRoot.hasAllPerm("file:read");

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("isOwner Tests")
    class IsOwnerTests {

        @Test
        @DisplayName("should return true when user is resource owner")
        void isOwner_returnsTrue() {
            MDC.put(Const.ATTR_USER_ID, "100");
            MDC.put(Const.ATTR_USER_ROLE, "user");

            boolean result = expressionRoot.isOwner(100L);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when user is not resource owner")
        void isNotOwner_returnsFalse() {
            MDC.put(Const.ATTR_USER_ID, "100");
            MDC.put(Const.ATTR_USER_ROLE, "user");

            boolean result = expressionRoot.isOwner(200L);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true when user is admin (can access any resource)")
        void adminCanAccessAnyResource_returnsTrue() {
            MDC.put(Const.ATTR_USER_ID, "100");
            MDC.put(Const.ATTR_USER_ROLE, "admin");

            boolean result = expressionRoot.isOwner(200L);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false when user id is null")
        void nullUserId_returnsFalse() {
            MDC.put(Const.ATTR_USER_ROLE, "user");
            // ATTR_USER_ID not set

            boolean result = expressionRoot.isOwner(100L);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when resource owner id is null")
        void nullResourceOwnerId_returnsFalse() {
            MDC.put(Const.ATTR_USER_ID, "100");
            MDC.put(Const.ATTR_USER_ROLE, "user");

            boolean result = expressionRoot.isOwner(null);

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("isAdmin Tests")
    class IsAdminTests {

        @Test
        @DisplayName("should return true for admin role")
        void adminRole_returnsTrue() {
            MDC.put(Const.ATTR_USER_ROLE, "admin");

            boolean result = expressionRoot.isAdmin();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for user role")
        void userRole_returnsFalse() {
            MDC.put(Const.ATTR_USER_ROLE, "user");

            boolean result = expressionRoot.isAdmin();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for monitor role")
        void monitorRole_returnsFalse() {
            MDC.put(Const.ATTR_USER_ROLE, "monitor");

            boolean result = expressionRoot.isAdmin();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when role is not set")
        void noRole_returnsFalse() {
            // ATTR_USER_ROLE not set

            boolean result = expressionRoot.isAdmin();

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("isMonitor Tests")
    class IsMonitorTests {

        @Test
        @DisplayName("should return true for monitor role")
        void monitorRole_returnsTrue() {
            MDC.put(Const.ATTR_USER_ROLE, "monitor");

            boolean result = expressionRoot.isMonitor();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for admin role")
        void adminRole_returnsFalse() {
            MDC.put(Const.ATTR_USER_ROLE, "admin");

            boolean result = expressionRoot.isMonitor();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for user role")
        void userRole_returnsFalse() {
            MDC.put(Const.ATTR_USER_ROLE, "user");

            boolean result = expressionRoot.isMonitor();

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("isAdminOrMonitor Tests")
    class IsAdminOrMonitorTests {

        @Test
        @DisplayName("should return true for admin role")
        void adminRole_returnsTrue() {
            MDC.put(Const.ATTR_USER_ROLE, "admin");

            boolean result = expressionRoot.isAdminOrMonitor();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true for monitor role")
        void monitorRole_returnsTrue() {
            MDC.put(Const.ATTR_USER_ROLE, "monitor");

            boolean result = expressionRoot.isAdminOrMonitor();

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for user role")
        void userRole_returnsFalse() {
            MDC.put(Const.ATTR_USER_ROLE, "user");

            boolean result = expressionRoot.isAdminOrMonitor();

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false when role is not set")
        void noRole_returnsFalse() {
            boolean result = expressionRoot.isAdminOrMonitor();

            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("Filter/Return Object Tests")
    class FilterReturnObjectTests {

        @Test
        @DisplayName("should set and get filter object")
        void setAndGetFilterObject() {
            Object filterObj = new Object();

            expressionRoot.setFilterObject(filterObj);

            assertThat(expressionRoot.getFilterObject()).isSameAs(filterObj);
        }

        @Test
        @DisplayName("should set and get return object")
        void setAndGetReturnObject() {
            Object returnObj = new Object();

            expressionRoot.setReturnObject(returnObj);

            assertThat(expressionRoot.getReturnObject()).isSameAs(returnObj);
        }

        @Test
        @DisplayName("should set and get this target")
        void setAndGetThis() {
            Object target = new Object();

            expressionRoot.setThis(target);

            assertThat(expressionRoot.getThis()).isSameAs(target);
        }
    }

    @Nested
    @DisplayName("Combined Expression Tests")
    class CombinedExpressionTests {

        @Test
        @DisplayName("should support hasPerm OR isAdmin pattern")
        void hasPermOrIsAdmin_adminWithoutPerm() {
            MDC.put(Const.ATTR_USER_ROLE, "admin");
            when(permissionService.hasPermission("file:admin")).thenReturn(false);

            boolean hasPerm = expressionRoot.hasPerm("file:admin");
            boolean isAdmin = expressionRoot.isAdmin();
            boolean result = hasPerm || isAdmin;

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should support hasPerm OR isAdmin pattern - user with perm")
        void hasPermOrIsAdmin_userWithPerm() {
            MDC.put(Const.ATTR_USER_ROLE, "user");
            when(permissionService.hasPermission("file:admin")).thenReturn(true);

            boolean hasPerm = expressionRoot.hasPerm("file:admin");
            boolean isAdmin = expressionRoot.isAdmin();
            boolean result = hasPerm || isAdmin;

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should support hasPerm OR isAdmin pattern - user without perm")
        void hasPermOrIsAdmin_userWithoutPerm() {
            MDC.put(Const.ATTR_USER_ROLE, "user");
            when(permissionService.hasPermission("file:admin")).thenReturn(false);

            boolean hasPerm = expressionRoot.hasPerm("file:admin");
            boolean isAdmin = expressionRoot.isAdmin();
            boolean result = hasPerm || isAdmin;

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should support isOwner OR hasPerm pattern")
        void isOwnerOrHasPerm_ownerWithoutPerm() {
            MDC.put(Const.ATTR_USER_ID, "100");
            MDC.put(Const.ATTR_USER_ROLE, "user");
            when(permissionService.hasPermission("file:admin")).thenReturn(false);

            boolean isOwner = expressionRoot.isOwner(100L);
            boolean hasPerm = expressionRoot.hasPerm("file:admin");
            boolean result = isOwner || hasPerm;

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should support isOwner OR hasPerm pattern - not owner but has perm")
        void isOwnerOrHasPerm_notOwnerButHasPerm() {
            MDC.put(Const.ATTR_USER_ID, "100");
            MDC.put(Const.ATTR_USER_ROLE, "user");
            when(permissionService.hasPermission("file:admin")).thenReturn(true);

            boolean isOwner = expressionRoot.isOwner(200L);
            boolean hasPerm = expressionRoot.hasPerm("file:admin");
            boolean result = isOwner || hasPerm;

            assertThat(result).isTrue();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("should create with Authentication directly")
        void createWithAuthentication() {
            CustomMethodSecurityExpressionRoot root =
                    new CustomMethodSecurityExpressionRoot(authentication, permissionService);

            assertThat(root).isNotNull();
            assertThat(root.getAuthentication()).isEqualTo(authentication);
        }

        @Test
        @DisplayName("should create with Supplier of Authentication")
        void createWithSupplier() {
            CustomMethodSecurityExpressionRoot root =
                    new CustomMethodSecurityExpressionRoot(() -> authentication, permissionService);

            assertThat(root).isNotNull();
        }
    }
}
