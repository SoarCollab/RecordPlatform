package cn.flying.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("RabbitMQHealthIndicator Tests")
class RabbitMQHealthIndicatorTest {

    @Test
    @DisplayName("health() should dispatch probe via configured executor")
    void health_shouldUseConfiguredExecutor() {
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        when(rabbitTemplate.execute(any())).thenReturn(Health.up().build());

        AtomicInteger executed = new AtomicInteger();
        ExecutorService executor = new AbstractExecutorService() {
            private volatile boolean shutdown;

            @Override
            public void shutdown() {
                shutdown = true;
            }

            @Override
            public List<Runnable> shutdownNow() {
                shutdown = true;
                return List.of();
            }

            @Override
            public boolean isShutdown() {
                return shutdown;
            }

            @Override
            public boolean isTerminated() {
                return shutdown;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) {
                return true;
            }

            @Override
            public void execute(Runnable command) {
                executed.incrementAndGet();
                command.run();
            }
        };

        RabbitMQHealthIndicator indicator = new RabbitMQHealthIndicator(rabbitTemplate, executor);
        ReflectionTestUtils.setField(indicator, "healthQueue", "");

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(executed.get()).isEqualTo(1);
    }
}
