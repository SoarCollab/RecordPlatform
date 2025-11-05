package cn.flying.identity.config;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

/**
 * TrafficMonitorConfig 配置绑定测试
 */
class TrafficMonitorConfigTest {

    @Test
    void bind_shouldPopulateNestedMonitorAndRateLimit() {
        Map<String, Object> propertyMap = Map.of(
                "gateway.traffic.monitor.enabled", "false",
                "gateway.traffic.monitor.time-window", "120",
                "gateway.traffic.rate-limit.enabled", "true",
                "gateway.traffic.rate-limit.ip-requests-per-minute", "42",
                "gateway.traffic.rate-limit.user-requests-per-minute", "84",
                "gateway.traffic.blocking.whitelist-ips[0]", "10.0.0.1",
                "gateway.traffic.blocking.block-response-code", "451"
        );

        TrafficMonitorConfig config = bind(propertyMap);

        Assertions.assertFalse(config.getMonitor().isEnabled());
        Assertions.assertEquals(120, config.getMonitor().getTimeWindow());
        Assertions.assertTrue(config.getRateLimit().isEnabled());
        Assertions.assertEquals(42, config.getRateLimit().getIpRequestsPerMinute());
        Assertions.assertEquals(84, config.getRateLimit().getUserRequestsPerMinute());
        Assertions.assertArrayEquals(new String[]{"10.0.0.1"}, config.getBlocking().getWhitelistIps());
        Assertions.assertEquals(451, config.getBlocking().getBlockResponseCode());
    }

    private TrafficMonitorConfig bind(Map<String, Object> propertyMap) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(propertyMap);
        Binder binder = new Binder(source);
        return binder.bind("gateway.traffic", Bindable.of(TrafficMonitorConfig.class)).get();
    }
}
