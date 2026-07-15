package cn.flying.common.annotation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流注解向后兼容合同测试。
 */
class RateLimitTest {

    /**
     * 验证新增配置保持旧调用点默认行为，并完整暴露受支持的客户端 IP 模式。
     */
    @Test
    void shouldKeepLegacyDefaultsAndExposeSupportedClientIpModes() throws NoSuchMethodException {
        Method method = DefaultRateLimitFixture.class.getDeclaredMethod("limitedOperation");
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        assertThat(rateLimit.tenantScoped()).isTrue();
        assertThat(rateLimit.clientIpMode()).isEqualTo(RateLimit.ClientIpMode.LEGACY_FORWARDED);
        assertThat(RateLimit.ClientIpMode.values()).containsExactly(
                RateLimit.ClientIpMode.LEGACY_FORWARDED,
                RateLimit.ClientIpMode.TRUSTED_PEER);
    }

    private static final class DefaultRateLimitFixture {

        /**
         * 提供未显式配置新增属性的旧式限流注解样本。
         */
        @RateLimit
        private void limitedOperation() {
        }
    }
}
