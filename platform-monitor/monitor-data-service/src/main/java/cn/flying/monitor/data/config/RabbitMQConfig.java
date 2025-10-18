package cn.flying.monitor.data.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for real-time event streaming
 * Optimized for high-throughput metrics processing
 */
@Configuration
public class RabbitMQConfig {
    
    // Exchange names
    public static final String METRICS_EXCHANGE = "monitor.metrics.exchange";
    public static final String ALERTS_EXCHANGE = "monitor.alerts.exchange";
    
    // Queue names
    public static final String WEBSOCKET_QUEUE = "monitor.websocket.queue";
    public static final String ALERT_PROCESSING_QUEUE = "monitor.alert.processing.queue";
    public static final String HEALTH_MONITORING_QUEUE = "monitor.health.queue";
    
    // Routing keys
    public static final String WEBSOCKET_ROUTING_KEY = "websocket.broadcast";
    public static final String ALERT_ROUTING_KEY = "alert.process";
    public static final String HEALTH_ROUTING_KEY = "health.update";
    
    /**
     * Metrics exchange for real-time data streaming
     */
    @Bean
    public TopicExchange metricsExchange() {
        return ExchangeBuilder
                .topicExchange(METRICS_EXCHANGE)
                .durable(true)
                .build();
    }
    
    /**
     * Alerts exchange for alert processing
     */
    @Bean
    public TopicExchange alertsExchange() {
        return ExchangeBuilder
                .topicExchange(ALERTS_EXCHANGE)
                .durable(true)
                .build();
    }
    
    /**
     * WebSocket broadcasting queue
     */
    @Bean
    public Queue websocketQueue() {
        return QueueBuilder
                .durable(WEBSOCKET_QUEUE)
                .withArgument("x-message-ttl", 30000) // 30 second TTL for real-time data
                .withArgument("x-max-length", 10000) // Limit queue size
                .build();
    }
    
    /**
     * Alert processing queue
     */
    @Bean
    public Queue alertProcessingQueue() {
        return QueueBuilder
                .durable(ALERT_PROCESSING_QUEUE)
                .withArgument("x-message-ttl", 300000) // 5 minute TTL for alerts
                .build();
    }
    
    /**
     * Health monitoring queue
     */
    @Bean
    public Queue healthMonitoringQueue() {
        return QueueBuilder
                .durable(HEALTH_MONITORING_QUEUE)
                .withArgument("x-message-ttl", 60000) // 1 minute TTL for health data
                .build();
    }
    
    /**
     * Binding for WebSocket broadcasting
     */
    @Bean
    public Binding websocketBinding() {
        return BindingBuilder
                .bind(websocketQueue())
                .to(metricsExchange())
                .with(WEBSOCKET_ROUTING_KEY);
    }
    
    /**
     * Binding for alert processing
     */
    @Bean
    public Binding alertProcessingBinding() {
        return BindingBuilder
                .bind(alertProcessingQueue())
                .to(alertsExchange())
                .with(ALERT_ROUTING_KEY);
    }
    
    /**
     * Binding for health monitoring
     */
    @Bean
    public Binding healthMonitoringBinding() {
        return BindingBuilder
                .bind(healthMonitoringQueue())
                .to(metricsExchange())
                .with(HEALTH_ROUTING_KEY);
    }
    
    /**
     * JSON message converter for structured data
     */
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
    
    /**
     * RabbitTemplate with JSON converter
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter());
        
        // Enable publisher confirms for reliability
        template.setMandatory(true);
        
        return template;
    }
    
    /**
     * Listener container factory for high-performance processing
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter());
        
        // Performance optimizations
        factory.setConcurrentConsumers(5);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(100); // Prefetch for better throughput
        
        // Acknowledgment mode for reliability
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        
        return factory;
    }
}