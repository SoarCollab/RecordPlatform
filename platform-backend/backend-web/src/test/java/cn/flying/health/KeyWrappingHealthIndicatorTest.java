package cn.flying.health;

import cn.flying.service.key.KeyWrappingCapability;
import cn.flying.service.key.KeyWrappingProviderDiagnostics;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KeyWrappingHealthIndicatorTest {

    /**
     * 验证健康摘要只暴露稳定 provider 元数据与低敏能力信息。
     */
    @Test
    void shouldExposeSafeProviderSummary() {
        KeyWrappingProviderRegistry registry = mock(KeyWrappingProviderRegistry.class);
        when(registry.activeDiagnostics()).thenReturn(new KeyWrappingProviderDiagnostics(
                "vault-transit",
                1,
                Set.of(KeyWrappingCapability.WRAP, KeyWrappingCapability.UNWRAP),
                true,
                "configured"));

        Health health = new KeyWrappingHealthIndicator(registry).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("provider", "vault-transit")
                .containsEntry("contractVersion", 1)
                .containsEntry("availability", "available")
                .containsEntry("configurationState", "configured");
        assertThat(health.toString())
                .doesNotContain("token", "keyId", "address", "namespace", "ciphertext", "masterKey");
    }

    /**
     * 验证 registry 异常不会把原始错误或潜在 secret 写入健康详情。
     */
    @Test
    void shouldHideRegistryExceptionDetails() {
        KeyWrappingProviderRegistry registry = mock(KeyWrappingProviderRegistry.class);
        when(registry.activeDiagnostics()).thenThrow(new IllegalStateException(
                "raw-vault-token-and-provider-error"));

        Health health = new KeyWrappingHealthIndicator(registry).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("availability", "unavailable")
                .containsEntry("configurationState", "invalid_registry");
        assertThat(health.toString()).doesNotContain("raw-vault-token-and-provider-error");
    }
}
