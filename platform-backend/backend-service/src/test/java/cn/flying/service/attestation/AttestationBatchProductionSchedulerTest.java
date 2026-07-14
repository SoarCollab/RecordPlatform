package cn.flying.service.attestation;

import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.mapper.TenantMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttestationBatchProductionSchedulerTest {

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private AttestationBatchProductionService productionService;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    /**
     * 清理测试留下的租户上下文和指标注册表。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        meterRegistry.clear();
    }

    /**
     * 验证活跃租户逐个运行，异常租户不会阻塞后续租户且原上下文会恢复。
     */
    @Test
    void runScheduledShouldIsolateTenantsAndContinueAfterFailure() {
        AttestationBatchProductionScheduler scheduler = scheduler();
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(7L, 8L));
        when(productionService.runTenant(7L, false)).thenThrow(new IllegalStateException("tenant 7 failed"));
        when(productionService.getStatus(7L)).thenReturn(status(5L, 2L));
        when(productionService.runTenant(8L, false))
                .thenReturn(AttestationBatchProductionRunResult.disabled(false));
        when(productionService.getStatus(8L)).thenReturn(status(3L, 1L));
        TenantContext.setTenantId(99L);

        scheduler.runScheduled();

        verify(productionService).runTenant(7L, false);
        verify(productionService).runTenant(8L, false);
        verify(productionService).getStatus(7L);
        verify(productionService).getStatus(8L);
        assertThat(TenantContext.requireTenantId()).isEqualTo(99L);
        assertThat(meterRegistry.get("app.attestation.candidate.backlog")
                .tag("status", "ready").gauge().value()).isEqualTo(8.0);
        assertThat(meterRegistry.get("app.attestation.candidate.backlog")
                .tag("status", "dead_letter").gauge().value()).isEqualTo(3.0);
    }

    /**
     * 验证调度入口声明周期和分布式锁，且不扩大整个方法的租户绕过范围。
     */
    @Test
    void runScheduledShouldDeclareSchedulingLockWithoutMethodWideTenantBypass() throws Exception {
        Method method = AttestationBatchProductionScheduler.class.getMethod("runScheduled");

        assertThat(method.getAnnotation(Scheduled.class)).isNotNull();
        assertThat(method.getAnnotation(DistributedLock.class).key())
                .isEqualTo("attestation:production:scheduler");
        assertThat(method.getDeclaredAnnotations())
                .extracting(annotation -> annotation.annotationType().getSimpleName())
                .doesNotContain("TenantScope");
        ConditionalOnProperty conditional = AttestationBatchProductionScheduler.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(conditional).isNotNull();
        assertThat(conditional.name()).containsExactly("attestation.production.enabled");
        assertThat(conditional.havingValue()).isEqualTo("true");
    }

    /**
     * 创建使用内存指标注册表的调度器。
     */
    private AttestationBatchProductionScheduler scheduler() {
        return new AttestationBatchProductionScheduler(
                tenantMapper,
                productionService,
                new AttestationBatchProductionMetrics(meterRegistry));
    }

    /**
     * 构造 scheduler 汇总 gauge 使用的租户状态。
     */
    private AttestationBatchProductionStatus status(long ready, long deadLetter) {
        return new AttestationBatchProductionStatus(
                true, 50, 100, 600, 200, 2,
                ready, 0, 0, deadLetter, null, 0);
    }
}
