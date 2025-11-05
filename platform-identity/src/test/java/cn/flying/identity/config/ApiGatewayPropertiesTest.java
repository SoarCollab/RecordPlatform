package cn.flying.identity.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/**
 * ApiGatewayProperties 配置绑定测试
 */
class ApiGatewayPropertiesTest {

    @Test
    void bind_shouldOverrideNestedValuesFromMap() {
        Map<String, Object> propertyMap = Map.of(
                "api-gateway.protocol", "https",
                "api-gateway.http-pool.max-total", "512",
                "api-gateway.http-pool.connect-timeout", "8000",
                "api-gateway.circuit-breaker.failure-rate-threshold", "70.5",
                "api-gateway.circuit-breaker.minimum-number-of-calls", "5",
                "api-gateway.rate-limit.default-qps", "250",
                "api-gateway.rate-limit.ip-rate-limit", "40",
                "api-gateway.load-balance.health-check-interval", "3"
        );

        ApiGatewayProperties properties = bind(propertyMap);

        Assertions.assertEquals("https", properties.getProtocol());
        Assertions.assertEquals(512, properties.getHttpPool().getMaxTotal());
        Assertions.assertEquals(8000, properties.getHttpPool().getConnectTimeout());
        Assertions.assertEquals(70.5f, properties.getCircuitBreaker().getFailureRateThreshold());
        Assertions.assertEquals(5, properties.getCircuitBreaker().getMinimumNumberOfCalls());
        Assertions.assertEquals(250, properties.getRateLimit().getDefaultQps());
        Assertions.assertEquals(40, properties.getRateLimit().getIpRateLimit());
        Assertions.assertEquals(3, properties.getLoadBalance().getHealthCheckInterval());
    }

    @Test
    void defaults_shouldRemainWhenPropertyMissing() {
        ApiGatewayProperties properties = new ApiGatewayProperties();

        Assertions.assertEquals("http", properties.getProtocol());
        Assertions.assertEquals(200, properties.getHttpPool().getMaxTotal());
        Assertions.assertEquals(100, properties.getRateLimit().getDefaultQps());
        Assertions.assertEquals(50, properties.getRateLimit().getIpRateLimit());
        Assertions.assertEquals(1000, properties.getRateLimit().getApiKeyRateLimit());
    }

    private ApiGatewayProperties bind(Map<String, Object> propertyMap) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(propertyMap);
        Binder binder = new Binder(source);
        return binder.bind("api-gateway", Bindable.of(ApiGatewayProperties.class)).get();
    }
}
