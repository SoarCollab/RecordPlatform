package cn.flying.monitor.client.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Client configuration for enhanced monitoring client
 */
@Configuration
@EnableRetry
public class ClientConfiguration {
    // Configuration is now handled by ServerConfiguration
    // This class enables retry functionality for the client
}