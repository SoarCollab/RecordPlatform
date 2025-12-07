package cn.flying.health;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

@Slf4j
@Component("rabbitmq")
@RequiredArgsConstructor
public class RabbitMQHealthIndicator implements HealthIndicator {

    private final RabbitTemplate rabbitTemplate;

    @Value("${spring.rabbitmq.health.queue:}")
    private String healthQueue;

    private static final int TIMEOUT_SECONDS = 3;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public Health health() {
        Future<Health> future = executor.submit(this::checkRabbitHealth);
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("RabbitMQ health check timed out");
            return Health.down()
                    .withDetail("reason", "Health check timed out after " + TIMEOUT_SECONDS + "s")
                    .build();
        } catch (Exception e) {
            log.error("RabbitMQ health check failed", e);
            return Health.down()
                    .withException(e)
                    .build();
        }
    }

    private Health checkRabbitHealth() {
        try {
            return rabbitTemplate.execute((Channel channel) -> {
                if (!channel.isOpen()) {
                    return Health.down()
                            .withDetail("reason", "Channel not available or closed")
                            .build();
                }

                Health.Builder builder = Health.up()
                        .withDetail("channelOpen", true);

                if (healthQueue != null && !healthQueue.isBlank()) {
                    try {
                        AMQP.Queue.DeclareOk stats = channel.queueDeclarePassive(healthQueue);
                        builder.withDetail("queue", healthQueue)
                                .withDetail("messageCount", stats.getMessageCount())
                                .withDetail("consumerCount", stats.getConsumerCount());
                    } catch (Exception queueEx) {
                        log.warn("RabbitMQ queue health probe failed for {}: {}", healthQueue, queueEx.getMessage());
                        builder.status("DEGRADED")
                                .withDetail("queue", healthQueue)
                                .withDetail("warning", "Queue probe failed: " + queueEx.getMessage());
                    }
                }

                return builder.build();
            });
        } catch (Exception e) {
            log.error("RabbitMQ health check error", e);
            return Health.down()
                    .withDetail("reason", "Failed to connect to RabbitMQ")
                    .withException(e)
                    .build();
        }
    }
}
