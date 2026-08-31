package cn.flying.health;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.SimpleHttpCodeStatusMapper;
import org.springframework.boot.actuate.health.SimpleStatusAggregator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HealthStatusConfigurationTest {

    /** Preserves degraded components in aggregate health without changing readiness HTTP semantics. */
    @Test
    void aggregateShouldPreserveDegradedAndExistingHttpMappings() throws Exception {
        MockEnvironment environment = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        String order = environment.getProperty("management.endpoint.health.status.order");
        SimpleStatusAggregator aggregator = order == null ? new SimpleStatusAggregator()
                : new SimpleStatusAggregator(Arrays.asList(order.split(",")));
        Status degraded = new Status("DEGRADED");

        assertThat(aggregator.getAggregateStatus(Set.of(Status.UP, degraded))).isEqualTo(degraded);
        assertThat(aggregator.getAggregateStatus(Set.of(Status.DOWN, degraded))).isEqualTo(Status.DOWN);
        assertThat(aggregator.getAggregateStatus(Set.of(Status.OUT_OF_SERVICE, degraded)))
                .isEqualTo(Status.OUT_OF_SERVICE);
        assertThat(aggregator.getAggregateStatus(Set.of(Status.UP, Status.UNKNOWN))).isEqualTo(Status.UP);
        SimpleHttpCodeStatusMapper mapper = new SimpleHttpCodeStatusMapper();
        assertThat(mapper.getStatusCode(degraded)).isEqualTo(200);
        assertThat(mapper.getStatusCode(Status.DOWN)).isEqualTo(503);
        assertThat(mapper.getStatusCode(Status.OUT_OF_SERVICE)).isEqualTo(503);
        assertThat(environment.getProperty("management.endpoint.health.status.http-mapping")).isNull();
    }
}
