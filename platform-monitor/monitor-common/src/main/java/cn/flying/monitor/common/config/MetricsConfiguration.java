package cn.flying.monitor.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 监控指标配置类
 * 配置Micrometer指标收集和Prometheus集成
 */
@Slf4j
@Configuration
public class MetricsConfiguration {

    /**
     * 配置Prometheus指标注册表
     */
    @Bean
    @ConditionalOnProperty(name = "management.metrics.export.prometheus.enabled", havingValue = "true", matchIfMissing = true)
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        log.info("配置Prometheus指标注册表");
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /**
     * 自定义指标注册表配置
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            // 添加通用标签
            registry.config()
                .commonTags("application", "monitor-system")
                .commonTags("version", "1.0.0")
                // 过滤不需要的指标
                .meterFilter(MeterFilter.deny(id -> {
                    String name = id.getName();
                    // 过滤掉一些不必要的JVM指标
                    return name.startsWith("jvm.classes.unloaded") ||
                           name.startsWith("process.files.max") ||
                           name.startsWith("system.load.average.1m");
                }))
                // 重命名指标
                .meterFilter(MeterFilter.renameTag("monitor.system", "uri", "endpoint"));
            
            log.info("配置指标注册表通用标签和过滤器");
        };
    }

    /**
     * JVM内存指标
     */
    @Bean
    public JvmMemoryMetrics jvmMemoryMetrics() {
        log.debug("启用JVM内存指标收集");
        return new JvmMemoryMetrics();
    }

    /**
     * JVM垃圾回收指标
     */
    @Bean
    public JvmGcMetrics jvmGcMetrics() {
        log.debug("启用JVM垃圾回收指标收集");
        return new JvmGcMetrics();
    }

    /**
     * JVM线程指标
     */
    @Bean
    public JvmThreadMetrics jvmThreadMetrics() {
        log.debug("启用JVM线程指标收集");
        return new JvmThreadMetrics();
    }

    /**
     * 类加载器指标
     */
    @Bean
    public ClassLoaderMetrics classLoaderMetrics() {
        log.debug("启用类加载器指标收集");
        return new ClassLoaderMetrics();
    }

    /**
     * 处理器指标
     */
    @Bean
    public ProcessorMetrics processorMetrics() {
        log.debug("启用处理器指标收集");
        return new ProcessorMetrics();
    }

    /**
     * 系统运行时间指标
     */
    @Bean
    public UptimeMetrics uptimeMetrics() {
        log.debug("启用系统运行时间指标收集");
        return new UptimeMetrics();
    }
}