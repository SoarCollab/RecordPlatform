package cn.flying.monitor.common.config;

import brave.sampler.Sampler;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.okhttp3.OkHttpSender;

/**
 * 分布式链路追踪配置
 * 配置Brave和Zipkin集成
 */
@Slf4j
@Configuration
public class TracingConfiguration {

    /**
     * 配置采样器
     * 生产环境建议使用较低的采样率以减少性能影响
     */
    @Bean
    public Sampler alwaysSampler() {
        // 开发环境使用100%采样，生产环境建议使用0.1或更低
        float samplingRate = 0.1f; // 10%采样率
        log.info("配置链路追踪采样率: {}%", samplingRate * 100);
        return Sampler.create(samplingRate);
    }

    /**
     * 配置Zipkin发送器
     */
    @Bean
    @ConditionalOnProperty(name = "management.tracing.zipkin.endpoint", matchIfMissing = false)
    public OkHttpSender sender() {
        String zipkinUrl = "http://localhost:9411/api/v2/spans";
        log.info("配置Zipkin发送器，URL: {}", zipkinUrl);
        return OkHttpSender.create(zipkinUrl);
    }

    /**
     * 配置异步报告器
     */
    @Bean
    @ConditionalOnProperty(name = "management.tracing.zipkin.endpoint", matchIfMissing = false)
    public AsyncReporter<zipkin2.Span> spanReporter(OkHttpSender sender) {
        log.info("配置Zipkin异步报告器");
        return AsyncReporter.create(sender);
    }
}