package cn.flying.health;

import cn.flying.service.key.KeyWrappingProviderDiagnostics;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 暴露不含 secret 和原始 provider 异常的 key wrapping 健康摘要。
 */
@Component("keyWrappingHealthIndicator")
public class KeyWrappingHealthIndicator implements HealthIndicator {

    private final KeyWrappingProviderRegistry registry;

    /**
     * 创建 active provider 健康检查。
     */
    public KeyWrappingHealthIndicator(KeyWrappingProviderRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回 provider id、contract、能力和安全配置状态。
     */
    @Override
    public Health health() {
        try {
            KeyWrappingProviderDiagnostics diagnostics = registry.activeDiagnostics();
            Health.Builder builder = diagnostics.available() ? Health.up() : Health.down();
            return builder
                    .withDetail("provider", diagnostics.providerId())
                    .withDetail("contractVersion", diagnostics.contractVersion())
                    .withDetail("capabilities", diagnostics.capabilities().stream()
                            .map(Enum::name)
                            .sorted()
                            .toList())
                    .withDetail("availability", diagnostics.available() ? "available" : "unavailable")
                    .withDetail("configurationState", diagnostics.configurationState())
                    .build();
        } catch (RuntimeException exception) {
            return Health.down()
                    .withDetail("availability", "unavailable")
                    .withDetail("configurationState", "invalid_registry")
                    .build();
        }
    }
}
