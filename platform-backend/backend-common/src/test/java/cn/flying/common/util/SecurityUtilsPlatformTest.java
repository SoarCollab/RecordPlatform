package cn.flying.common.util;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises platform service authorization independently of servlet route authorization. */
class SecurityUtilsPlatformTest {

    /** Establishes the explicit system context used by a valid platform request. */
    @BeforeEach
    void setUp() {
        TenantContext.clear();
        TenantContext.setTenantId(0L);
        MDC.put(Const.ATTR_USER_ID, "17");
        authenticate("ROLE_platform_admin", PlatformPermissions.TENANT_WRITE);
    }

    /** Removes all thread-local identity data before another test reuses the worker thread. */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /** A fully authenticated and explicitly scoped capability returns the positive actor ID. */
    @Test
    void acceptsExactPlatformIdentityAndCapability() {
        assertThat(SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_WRITE)).isEqualTo(17L);
    }

    /** A missing tenant must never inherit the legacy fallback-to-zero behavior. */
    @Test
    void rejectsMissingTenantContext() {
        TenantContext.clear();
        assertDenied();
    }

    /** Cross-tenant bypass and target contexts are not valid platform service entry contexts. */
    @Test
    void rejectsIgnoredIsolationAndTargetTenant() {
        TenantContext.setIgnoreIsolation(true);
        assertDenied();
        TenantContext.setIgnoreIsolation(false);
        TenantContext.setTenantId(91L);
        assertThat(SecurityUtils.isPlatformPrincipal()).isTrue();
        assertDenied();
    }

    /** Tenant roles cannot gain platform authority by carrying a reserved permission string. */
    @ParameterizedTest
    @ValueSource(strings = {"ROLE_admin", "ROLE_monitor", "ROLE_user", "ROLE_PLATFORM_ADMIN"})
    void rejectsTenantAndCaseVariantRoles(String role) {
        authenticate(role, PlatformPermissions.TENANT_WRITE);
        assertDenied();
    }

    /** A role alone or a conflicting second role cannot satisfy the closed identity contract. */
    @Test
    void rejectsMissingCapabilityAndMultipleRoles() {
        authenticate("ROLE_platform_admin");
        assertDenied();
        authenticate("ROLE_platform_admin", "ROLE_admin", PlatformPermissions.TENANT_WRITE);
        assertDenied();
        authenticate(PlatformPermissions.TENANT_WRITE);
        assertDenied();
    }

    /** MDC is not an authentication source and a string principal is not a verified user. */
    @Test
    void rejectsAbsentUnauthenticatedAndNonUserPrincipals() {
        SecurityContextHolder.clearContext();
        MDC.put(Const.ATTR_USER_ROLE, "platform_admin");
        assertDenied();
        authenticate("ROLE_platform_admin", PlatformPermissions.TENANT_WRITE);
        SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
        assertDenied();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("platform", null, List.of()));
        assertDenied();
    }

    /** Every platform mutation has a usable actor, never an anonymous or sentinel identifier. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
    void rejectsInvalidActor(String actor) {
        if (actor == null) {
            MDC.remove(Const.ATTR_USER_ID);
        } else {
            MDC.put(Const.ATTR_USER_ID, actor);
        }
        assertDenied();
    }

    /** Callers cannot authorize future, misspelled or tenant-owned permission codes. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"platform:new:write", " platform:tenant:write", "file:read"})
    void rejectsUnknownCapability(String permission) {
        assertThatThrownBy(() -> SecurityUtils.requirePlatformPermission(permission))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED));
    }

    /** Creates a real Spring Security principal with only the supplied authorities. */
    private void authenticate(String... authorities) {
        User principal = (User) User.withUsername("platform").password("unused").authorities(authorities).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** Requires the public structured denial code without exposing internal identity details. */
    private void assertDenied() {
        assertThatThrownBy(() -> SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_WRITE))
                .isInstanceOfSatisfying(GeneralException.class,
                        error -> assertThat(error.getResultEnum()).isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED));
    }
}
