package cn.flying.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration with dead letter queues.
 */
@Configuration
public class RabbitConfiguration {

    // ==================== Mail Queue (existing) ====================
    @Bean("mailQueue")
    public Queue mailQueue() {
        return QueueBuilder.durable("mail").build();
    }

    // ==================== File Exchange ====================
    @Bean
    public TopicExchange fileExchange() {
        return ExchangeBuilder.topicExchange("file.exchange").durable(true).build();
    }

    // ==================== Dead Letter Exchange ====================
    @Bean
    public DirectExchange fileDlx() {
        return ExchangeBuilder.directExchange("file.dlx").durable(true).build();
    }

    // ==================== File Stored Queue + DLQ ====================
    @Bean
    public Queue fileStoredQueue() {
        return QueueBuilder.durable("file.stored.queue")
                .withArgument("x-dead-letter-exchange", "file.dlx")
                .withArgument("x-dead-letter-routing-key", "file.stored.dlq")
                .build();
    }

    @Bean
    public Binding fileStoredBinding() {
        return BindingBuilder.bind(fileStoredQueue())
                .to(fileExchange())
                .with("file.stored");
    }

    @Bean
    public Queue fileStoredDlq() {
        return QueueBuilder.durable("file.stored.dlq").build();
    }

    @Bean
    public Binding fileStoredDlqBinding() {
        return BindingBuilder.bind(fileStoredDlq())
                .to(fileDlx())
                .with("file.stored.dlq");
    }

    // ==================== File Deleted Queue + DLQ ====================
    @Bean
    public Queue fileDeletedQueue() {
        return QueueBuilder.durable("file.deleted.queue")
                .withArgument("x-dead-letter-exchange", "file.dlx")
                .withArgument("x-dead-letter-routing-key", "file.deleted.dlq")
                .build();
    }

    @Bean
    public Binding fileDeletedBinding() {
        return BindingBuilder.bind(fileDeletedQueue())
                .to(fileExchange())
                .with("file.deleted");
    }

    @Bean
    public Queue fileDeletedDlq() {
        return QueueBuilder.durable("file.deleted.dlq").build();
    }

    @Bean
    public Binding fileDeletedDlqBinding() {
        return BindingBuilder.bind(fileDeletedDlq())
                .to(fileDlx())
                .with("file.deleted.dlq");
    }

    // ==================== Message Converter ====================
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
