package cn.flying.health;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RabbitMQHealthIndicator Tests")
class RabbitMQHealthIndicatorTest {

    @Test
    @DisplayName("health() should dispatch probe via configured executor")
    void health_shouldUseConfiguredExecutor() {
        RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
        when(rabbitTemplate.execute(any())).thenReturn(Health.up().build());

        AtomicInteger executed = new AtomicInteger();
        ExecutorService executor = new SynchronousExecutorService() {
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

    @Nested
    @DisplayName("Timeout scenarios")
    class TimeoutTests {

        @Test
        @DisplayName("should return DOWN when health check times out")
        void shouldReturnDownOnTimeout() {
            RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
            // Simulate a blocking call that never completes
            when(rabbitTemplate.execute(any())).thenAnswer(inv -> {
                Thread.sleep(10_000);
                return Health.up().build();
            });

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                RabbitMQHealthIndicator indicator = new RabbitMQHealthIndicator(rabbitTemplate, executor);
                ReflectionTestUtils.setField(indicator, "healthQueue", "");

                Health health = indicator.health();

                assertThat(health.getStatus()).isEqualTo(Status.DOWN);
                assertThat(health.getDetails()).containsKey("reason");
                assertThat((String) health.getDetails().get("reason")).contains("timed out");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Exception scenarios")
    class ExceptionTests {

        @Test
        @DisplayName("should return DOWN when executor throws ExecutionException")
        void shouldReturnDownOnExecutionException() {
            RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
            when(rabbitTemplate.execute(any())).thenThrow(new RuntimeException("Connection refused"));

            ExecutorService executor = new SynchronousExecutorService();

            RabbitMQHealthIndicator indicator = new RabbitMQHealthIndicator(rabbitTemplate, executor);
            ReflectionTestUtils.setField(indicator, "healthQueue", "");

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        }

        @Test
        @DisplayName("should return DOWN with exception detail on RabbitTemplate failure")
        void shouldReturnDownWithExceptionOnRabbitTemplateFailure() {
            RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
            when(rabbitTemplate.execute(any())).thenThrow(
                    new org.springframework.amqp.AmqpConnectException(
                            new java.net.ConnectException("Connection refused")));

            ExecutorService executor = new SynchronousExecutorService();

            RabbitMQHealthIndicator indicator = new RabbitMQHealthIndicator(rabbitTemplate, executor);
            ReflectionTestUtils.setField(indicator, "healthQueue", "");

            Health health = indicator.health();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("reason");
        }

        @Test
        @DisplayName("should degrade when queue probe fails but channel is open")
        void shouldReturnDegradedWhenQueueProbeFails() {
            RabbitTemplate rabbitTemplate = Mockito.mock(RabbitTemplate.class);
            when(rabbitTemplate.execute(any())).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                var callback = (org.springframework.amqp.rabbit.core.ChannelCallback<Health>) invocation.getArgument(0);
                Channel channel = mock(Channel.class);
                when(channel.isOpen()).thenReturn(true);
                when(channel.queueDeclarePassive("file.stored")).thenThrow(new RuntimeException("queue not found"));
                return callback.doInRabbit(channel);
            });

            ExecutorService executor = new SynchronousExecutorService();
            RabbitMQHealthIndicator indicator = new RabbitMQHealthIndicator(rabbitTemplate, executor);
            ReflectionTestUtils.setField(indicator, "healthQueue", "file.stored");

            Health health = indicator.health();

            assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
            assertThat(health.getDetails()).containsEntry("queue", "file.stored");
            assertThat((String) health.getDetails().get("warning")).contains("Queue probe failed");
        }
    }

    /** Simple synchronous executor for testing that runs tasks on the calling thread. */
    private static class SynchronousExecutorService extends AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() { shutdown = true; }

        @Override
        public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }

        @Override
        public boolean isShutdown() { return shutdown; }

        @Override
        public boolean isTerminated() { return shutdown; }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }

        @Override
        public void execute(Runnable command) { command.run(); }
    }
}
