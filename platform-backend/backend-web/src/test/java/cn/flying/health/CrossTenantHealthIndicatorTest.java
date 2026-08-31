package cn.flying.health;

import cn.flying.aspect.TenantScopeAspect;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.FileSagaMapper;
import cn.flying.dao.mapper.OutboxEventMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Proxy/context contract only; CrossTenantHealthIndicatorIT verifies actual cross-tenant SQL. */
class CrossTenantHealthIndicatorTest {

    /** Removes test-owned context after every proxy scenario. */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Covers both proxy models, both detail policies, and all prior context combinations. */
    static Stream<Arguments> contexts() {
        List<Arguments> cases = new ArrayList<>();
        for (boolean outbox : List.of(true, false)) {
            for (boolean classProxy : List.of(true, false)) {
                for (boolean details : List.of(true, false)) {
                    for (Long tenant : new Long[]{null, 42L}) {
                        for (boolean ignored : List.of(true, false)) {
                            cases.add(Arguments.of(outbox, classProxy, details, tenant, ignored));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    /** Exercises the actual Actuator entry point without inventing a system tenant. */
    @ParameterizedTest
    @MethodSource("contexts")
    void actuatorEntryShouldAggregateAndRestoreContext(boolean outbox, boolean classProxy,
            boolean details, Long tenant, boolean ignored) {
        TenantContext.setTenantId(tenant);
        TenantContext.setIgnoreIsolation(ignored);
        List<Boolean> observedIsolation = new ArrayList<>();
        HealthIndicator indicator = proxy(outbox, classProxy, observedIsolation, false);

        Health health = indicator.getHealth(details);

        assertThat(observedIsolation).hasSize(outbox ? 3 : 4).containsOnly(true);
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().isEmpty()).isEqualTo(!details);
        assertThat(TenantContext.getTenantId()).isEqualTo(tenant);
        assertThat(TenantContext.isIgnoreIsolation()).isEqualTo(ignored);
    }

    /** Keeps failure status and restores context even when the aggregate query fails. */
    @ParameterizedTest
    @MethodSource("contexts")
    void actuatorFailureShouldRestoreContextAndHonorDetails(boolean outbox, boolean classProxy,
            boolean details, Long tenant, boolean ignored) {
        TenantContext.setTenantId(tenant);
        TenantContext.setIgnoreIsolation(ignored);
        List<Boolean> observedIsolation = new ArrayList<>();
        HealthIndicator indicator = proxy(outbox, classProxy, observedIsolation, true);

        Health health = indicator.getHealth(details);

        assertThat(observedIsolation).containsExactly(true);
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().isEmpty()).isEqualTo(!details);
        assertThat(TenantContext.getTenantId()).isEqualTo(tenant);
        assertThat(TenantContext.isIgnoreIsolation()).isEqualTo(ignored);
    }

    /** Uses the production aspect and indicator with read-only mocked mapper boundaries. */
    private HealthIndicator proxy(boolean outbox, boolean classProxy,
            List<Boolean> observedIsolation, boolean fail) {
        org.mockito.stubbing.Answer<Object> answer = invocation -> {
            if (invocation.getMethod().getName().startsWith("count")) {
                observedIsolation.add(TenantContext.isIgnoreIsolation());
                if (fail) {
                    throw new IllegalStateException("synthetic database failure");
                }
                return 0L;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        };
        HealthIndicator target = outbox
                ? new OutboxHealthIndicator(mock(OutboxEventMapper.class, answer))
                : new SagaHealthIndicator(mock(FileSagaMapper.class, answer));
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(classProxy);
        factory.addAspect(new TenantScopeAspect());
        return factory.getProxy();
    }
}
