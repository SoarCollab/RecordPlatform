package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.tenant.TenantContext;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.admin.TenantInvitationService;
import cn.flying.service.admin.TenantMemberCommandService;
import cn.flying.service.admin.TenantMemberQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises the real role-plus-permission method-security proxy. */
@SpringJUnitConfig(TenantUserAdminControllerSecurityTest.Config.class)
class TenantUserAdminControllerSecurityTest {

    @Autowired private TenantUserAdminController controller;
    @Autowired private TenantMemberQueryService queryService;
    @Autowired private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        reset(queryService, permissionService);
        TenantContext.setTenantId(11L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void adminWithTenantUserPermissionIsAllowed() {
        authenticate("admin");
        when(permissionService.hasPermission("tenant:user:admin")).thenReturn(true);

        controller.list(1, 20, null, null, null);

        verify(queryService).list(11L, 1, 20, null, null, null);
    }

    @Test
    void adminWithoutPermissionIsRejected() {
        authenticate("admin");
        when(permissionService.hasPermission("tenant:user:admin")).thenReturn(false);

        assertThatThrownBy(() -> controller.list(1, 20, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queryService);
    }

    @Test
    void transferablePermissionDoesNotAllowUserRole() {
        authenticate("user");
        when(permissionService.hasPermission("tenant:user:admin")).thenReturn(true);

        assertThatThrownBy(() -> controller.list(1, 20, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(queryService);
    }

    @Test
    void transferablePermissionDoesNotAllowMonitorOrPlatformRole() {
        when(permissionService.hasPermission("tenant:user:admin")).thenReturn(true);

        for (String role : List.of("monitor", "platform_admin")) {
            authenticate(role);
            assertThatThrownBy(() -> controller.list(1, 20, null, null, null))
                    .isInstanceOf(AccessDeniedException.class);
        }
        verifyNoInteractions(queryService);
    }

    @Test
    void everyTenantMemberControllerOperationIsAudited() {
        List<Method> operations = Arrays.stream(TenantUserAdminController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        org.assertj.core.api.Assertions.assertThat(operations)
                .hasSize(8)
                .allSatisfy(method -> org.assertj.core.api.Assertions.assertThat(
                                method.getAnnotation(OperationLog.class))
                        .as(method.getName() + " must carry operation audit metadata")
                        .isNotNull());
    }

    private void authenticate(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        "operator", "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class Config {
        @Bean
        static MethodSecurityExpressionHandler methodSecurityExpressionHandler(PermissionService permissionService) {
            return new CustomMethodSecurityExpressionHandler(permissionService);
        }

        @Bean PermissionService permissionService() { return mock(PermissionService.class); }
        @Bean TenantMemberQueryService queryService() { return mock(TenantMemberQueryService.class); }
        @Bean TenantMemberCommandService commandService() { return mock(TenantMemberCommandService.class); }
        @Bean TenantInvitationService invitationService() { return mock(TenantInvitationService.class); }

        @Bean
        TenantUserAdminController controller(TenantMemberQueryService queryService,
                                             TenantMemberCommandService commandService,
                                             TenantInvitationService invitationService) {
            return new TenantUserAdminController(queryService, commandService, invitationService);
        }
    }
}
