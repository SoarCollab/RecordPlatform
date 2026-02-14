package cn.flying.service.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 配额治理指标采集组件。
 * 统一记录配额判定结果与对账漂移告警次数，供 Prometheus 抓取与告警策略使用。
 */
@Component
@RequiredArgsConstructor
public class QuotaMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 记录一次配额判定结果。
     *
     * @param mode 当前生效模式（SHADOW/ENFORCE）
     * @param exceeded 是否超限
     */
    public void recordQuotaDecision(String mode, boolean exceeded) {
        Counter.builder("quota.decision.total")
                .description("配额判定总次数")
                .tags(List.of(
                        Tag.of("mode", safeTag(mode)),
                        Tag.of("exceeded", String.valueOf(exceeded))
                ))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 记录一次配额漂移告警。
     *
     * @param scope 告警范围（TENANT/USER）
     * @param reason 告警原因（storage_drift/file_count_drift）
     */
    public void recordDriftAlert(String scope, String reason) {
        Counter.builder("quota.drift.alert.total")
                .description("配额对账漂移告警总次数")
                .tags(List.of(
                        Tag.of("scope", safeTag(scope)),
                        Tag.of("reason", safeTag(reason))
                ))
                .register(meterRegistry)
                .increment();
    }

    /**
     * 归一化指标标签，避免空值导致标签污染。
     *
     * @param value 原始标签值
     * @return 可安全写入指标的标签值
     */
    private String safeTag(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value;
    }
}
