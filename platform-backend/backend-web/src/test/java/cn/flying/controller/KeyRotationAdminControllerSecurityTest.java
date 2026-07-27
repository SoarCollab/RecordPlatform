package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.admin.KeyRotationItemVO;
import cn.flying.dao.vo.admin.KeyRotationPolicyVO;
import cn.flying.dao.vo.admin.KeyRotationRunVO;
import cn.flying.security.CustomMethodSecurityExpressionHandler;
import cn.flying.service.PermissionService;
import cn.flying.service.key.rotation.KeyRotationPolicyService;
import cn.flying.service.key.rotation.KeyRotationRunCreationService;
import cn.flying.service.key.rotation.KeyRotationRunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises the real method-security proxy and response redaction contract for rotation administration.
 */
@SpringJUnitConfig(KeyRotationAdminControllerSecurityTest.MethodSecurityTestConfiguration.class)
class KeyRotationAdminControllerSecurityTest {

    private static final Long TENANT_ID = 11L;

    @Autowired
    private KeyRotationAdminController controller;

    @Autowired
    private KeyRotationPolicyService policyService;

    @Autowired
    private KeyRotationRunCreationService runCreationService;

    @Autowired
    private KeyRotationRunService runService;

    /**
     * Establishes an authenticated identity while leaving the tested role explicit in MDC.
     */
    @BeforeEach
    void setUp() {
        reset(policyService, runCreationService, runService);
        TenantContext.setTenantId(TENANT_ID);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("operator", "n/a", List.of()));
    }

    /**
     * Clears thread-local authorization and tenant state after each proxy invocation.
     */
    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
        MDC.clear();
    }

    /**
     * Proves an authenticated tenant member cannot read key-governance state.
     */
    @Test
    void shouldRejectNonAdminBeforeServiceInvocation() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_DEFAULT.getRole());

        assertThatThrownBy(() -> controller.listRuns(TENANT_ID, 10))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(policyService, runCreationService, runService);
    }

    /**
     * Proves a tenant administrator reaches the tenant-scoped run service.
     */
    @Test
    void shouldAllowAdminWithinTenantBoundary() {
        MDC.put(Const.ATTR_USER_ROLE, UserRole.ROLE_ADMINISTER.getRole());
        when(runService.listRuns(TENANT_ID, 10)).thenReturn(List.of());

        assertThat(controller.listRuns(TENANT_ID, 10).getData()).isEmpty();

        verify(runService).listRuns(TENANT_ID, 10);
    }

    /**
     * Proves every public control-plane operation is audited and policy bodies are not retained.
     */
    @Test
    void shouldAuditEveryOperationAndSuppressPolicyRequestBody() throws NoSuchMethodException {
        List<java.lang.reflect.Method> operations = Arrays.stream(
                        KeyRotationAdminController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();

        assertThat(operations).isNotEmpty();
        assertThat(operations).allSatisfy(method -> assertThat(method.getAnnotation(OperationLog.class))
                .as(method.getName() + " must be audited")
                .isNotNull());
        OperationLog savePolicy = KeyRotationAdminController.class
                .getDeclaredMethod("savePolicy", Long.class, Long.class,
                        cn.flying.dao.vo.admin.KeyRotationPolicyRequest.class)
                .getAnnotation(OperationLog.class);
        assertThat(savePolicy.saveRequestData()).isFalse();
    }

    /**
     * Proves response records cannot expose raw provider key or recipient envelope identifiers.
     */
    @Test
    void shouldKeepRawKeyAndRecipientIdentifiersOutOfResponseRecords() {
        Set<String> forbidden = Set.of(
                "targetKeyId", "kmsKeyId", "sourceEnvelopeId", "candidateEnvelopeId", "recipientId",
                "snapshotMaxEnvelopeId");

        for (Class<?> type : List.of(
                KeyRotationPolicyVO.class, KeyRotationRunVO.class, KeyRotationItemVO.class)) {
            Set<String> fields = Arrays.stream(type.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .collect(Collectors.toSet());
            assertThat(fields).doesNotContainAnyElementsOf(forbidden);
        }
    }

    /**
     * Minimal context that enables the production custom method-security expression handler.
     */
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurityTestConfiguration {

        /**
         * Registers the project's custom isAdmin expression before security advisors initialize.
         */
        @Bean
        static MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
            return new CustomMethodSecurityExpressionHandler(mock(PermissionService.class));
        }

        /**
         * Supplies the policy dependency without invoking persistence.
         */
        @Bean
        KeyRotationPolicyService keyRotationPolicyService() {
            return mock(KeyRotationPolicyService.class);
        }

        /**
         * Supplies the run factory dependency without invoking persistence.
         */
        @Bean
        KeyRotationRunCreationService keyRotationRunCreationService() {
            return mock(KeyRotationRunCreationService.class);
        }

        /**
         * Supplies the lifecycle dependency observed by the proxied controller.
         */
        @Bean
        KeyRotationRunService keyRotationRunService() {
            return mock(KeyRotationRunService.class);
        }

        /**
         * Creates the real controller target that Spring wraps with method security.
         */
        @Bean
        KeyRotationAdminController keyRotationAdminController(
                KeyRotationPolicyService policyService,
                KeyRotationRunCreationService runCreationService,
                KeyRotationRunService runService
        ) {
            return new KeyRotationAdminController(policyService, runCreationService, runService);
        }
    }
}
