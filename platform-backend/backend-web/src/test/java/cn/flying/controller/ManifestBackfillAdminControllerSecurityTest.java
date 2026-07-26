package cn.flying.controller;

import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.manifest.backfill.ManifestBackfillRunService;
import cn.flying.service.manifest.backfill.ManifestReferenceCensusService;
import cn.flying.service.manifest.backfill.ManifestReferenceSweepService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the real method-security proxy around manifest governance administration.
 */
@SpringJUnitConfig(ManifestBackfillAdminControllerSecurityTest.TestConfiguration.class)
class ManifestBackfillAdminControllerSecurityTest {

    private static final Long TENANT_ID = 11L;

    @Autowired
    private ManifestBackfillAdminController controller;

    @Autowired
    private ManifestBackfillRunService runService;

    /**
     * Establishes an authenticated request identity while keeping the tested role explicit in MDC.
     */
    @BeforeEach
    void setUp() {
        reset(runService);
        TenantContext.setTenantId(TENANT_ID);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator", "n/a", List.of()));
    }

    /**
     * Clears thread-local security and tenant state after each proxy invocation.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /**
     * Rejects an authenticated non-admin before any governance service method executes.
     */
    @Test
    void shouldRejectNonAdminAtRuntime() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());

        assertThatThrownBy(() -> controller.listRuns(TENANT_ID, 10))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(runService);
    }

    /**
     * Allows an authenticated administrator through the same method-security proxy.
     */
    @Test
    void shouldAllowAdminAtRuntime() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        when(runService.getRuns(TENANT_ID, 10)).thenReturn(List.of());

        assertThat(controller.listRuns(TENANT_ID, 10).getData()).isEmpty();

        verify(runService).getRuns(TENANT_ID, 10);
    }

    /**
     * Minimal context that enables the production custom method-security expression handler.
     */
    @Configuration
    @EnableMethodSecurity
    static class TestConfiguration {

        /**
         * Registers the custom expression handler before method-security advisors initialize.
         */
        @Bean
        static MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
            return new CustomMethodSecurityExpressionHandler(mock(PermissionService.class));
        }

        /**
         * Supplies the governance run service observed by the proxied controller.
         */
        @Bean
        ManifestBackfillRunService manifestBackfillRunService() {
            return mock(ManifestBackfillRunService.class);
        }

        /**
         * Supplies the census service dependency without invoking infrastructure.
         */
        @Bean
        ManifestReferenceCensusService manifestReferenceCensusService() {
            return mock(ManifestReferenceCensusService.class);
        }

        /**
         * Supplies the sweep service dependency without invoking infrastructure.
         */
        @Bean
        ManifestReferenceSweepService manifestReferenceSweepService() {
            return mock(ManifestReferenceSweepService.class);
        }

        /**
         * Creates the real controller target that Spring wraps with method security.
         */
        @Bean
        ManifestBackfillAdminController manifestBackfillAdminController(
                ManifestBackfillRunService runService,
                ManifestReferenceCensusService censusService,
                ManifestReferenceSweepService sweepService
        ) {
            return new ManifestBackfillAdminController(runService, censusService, sweepService);
        }
    }
}
