package cn.flying.monitor.client.plugin;

import cn.flying.monitor.client.entity.RuntimeDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plugin manager for custom metric collection plugins
 */
@Slf4j
@Component
public class PluginManager {

    @Value("${monitor.client.metrics.custom-metrics-enabled:false}")
    private boolean customMetricsEnabled;

    @Value("${monitor.client.metrics.plugin-timeout-seconds:5}")
    private int pluginTimeoutSeconds;

    private final List<MetricCollectionPlugin> plugins = new ArrayList<>();
    private volatile boolean initialized = false;

    @PostConstruct
    public void initialize() {
        if (!customMetricsEnabled) {
            log.info("Custom metrics plugins are disabled");
            return;
        }

        log.info("Initializing plugin manager...");
        
        // Auto-discover plugins (in a real implementation, this would scan classpath or plugin directory)
        discoverPlugins();
        
        // Initialize all discovered plugins
        initializePlugins();
        
        initialized = true;
        log.info("Plugin manager initialized with {} plugins", plugins.size());
    }

    /**
     * Discover available plugins
     */
    private void discoverPlugins() {
        // In a real implementation, this would:
        // 1. Scan classpath for plugin implementations
        // 2. Load plugins from external JAR files
        // 3. Read plugin configuration from files
        
        // For now, we'll add some example plugins
        plugins.add(new DatabaseMetricsPlugin());
        plugins.add(new ApplicationMetricsPlugin());
        
        log.info("Discovered {} plugins", plugins.size());
    }

    /**
     * Initialize all plugins
     */
    private void initializePlugins() {
        List<MetricCollectionPlugin> failedPlugins = new ArrayList<>();
        
        for (MetricCollectionPlugin plugin : plugins) {
            try {
                if (plugin.isEnabled() && plugin.validateConfiguration()) {
                    plugin.initialize();
                    log.info("Initialized plugin: {} v{}", plugin.getName(), plugin.getVersion());
                } else {
                    log.info("Plugin {} is disabled or has invalid configuration", plugin.getName());
                    failedPlugins.add(plugin);
                }
            } catch (Exception e) {
                log.error("Failed to initialize plugin: {}", plugin.getName(), e);
                failedPlugins.add(plugin);
            }
        }
        
        // Remove failed plugins
        plugins.removeAll(failedPlugins);
    }

    /**
     * Collect metrics from all enabled plugins
     */
    public void collectPluginMetrics(RuntimeDetail runtimeDetail) {
        if (!initialized || !customMetricsEnabled || plugins.isEmpty()) {
            return;
        }

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (MetricCollectionPlugin plugin : plugins) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    plugin.collectMetrics(runtimeDetail);
                    log.debug("Collected metrics from plugin: {}", plugin.getName());
                } catch (Exception e) {
                    log.error("Error collecting metrics from plugin: {}", plugin.getName(), e);
                }
            }).orTimeout(pluginTimeoutSeconds, TimeUnit.SECONDS)
              .exceptionally(throwable -> {
                  log.error("Plugin {} timed out or failed", plugin.getName(), throwable);
                  return null;
              });
            
            futures.add(future);
        }
        
        // Wait for all plugins to complete (with timeout)
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(pluginTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Some plugins did not complete within timeout", e);
        }
    }

    /**
     * Get list of active plugins
     */
    public List<String> getActivePlugins() {
        return plugins.stream()
                .map(plugin -> plugin.getName() + " v" + plugin.getVersion())
                .toList();
    }

    /**
     * Get plugin statistics
     */
    public String getPluginStats() {
        if (!initialized) {
            return "Plugin manager not initialized";
        }
        
        long enabledCount = plugins.stream().filter(MetricCollectionPlugin::isEnabled).count();
        return String.format("Plugins: %d total, %d enabled, %d active", 
                plugins.size(), enabledCount, plugins.size());
    }

    @PreDestroy
    public void cleanup() {
        log.info("Cleaning up plugin manager...");
        
        for (MetricCollectionPlugin plugin : plugins) {
            try {
                plugin.cleanup();
                log.debug("Cleaned up plugin: {}", plugin.getName());
            } catch (Exception e) {
                log.error("Error cleaning up plugin: {}", plugin.getName(), e);
            }
        }
        
        plugins.clear();
        initialized = false;
        log.info("Plugin manager cleanup completed");
    }

    /**
     * Example database metrics plugin
     */
    private static class DatabaseMetricsPlugin implements MetricCollectionPlugin {
        @Override
        public String getName() { return "Database Metrics"; }

        @Override
        public String getVersion() { return "1.0.0"; }

        @Override
        public boolean isEnabled() { 
            return System.getProperty("monitor.plugin.database.enabled", "false").equals("true"); 
        }

        @Override
        public void initialize() throws Exception {
            // Initialize database connections, etc.
        }

        @Override
        public void collectMetrics(RuntimeDetail runtimeDetail) throws Exception {
            // Collect database-specific metrics
            if (runtimeDetail.getCustomMetrics() == null) {
                runtimeDetail.setCustomMetrics(new java.util.HashMap<>());
            }
            runtimeDetail.getCustomMetrics().put("db_connections", 10);
            runtimeDetail.getCustomMetrics().put("db_query_time_avg", 25.5);
        }

        @Override
        public void cleanup() {
            // Cleanup database connections
        }
    }

    /**
     * Example application metrics plugin
     */
    private static class ApplicationMetricsPlugin implements MetricCollectionPlugin {
        @Override
        public String getName() { return "Application Metrics"; }

        @Override
        public String getVersion() { return "1.0.0"; }

        @Override
        public boolean isEnabled() { 
            return System.getProperty("monitor.plugin.application.enabled", "false").equals("true"); 
        }

        @Override
        public void initialize() throws Exception {
            // Initialize application monitoring
        }

        @Override
        public void collectMetrics(RuntimeDetail runtimeDetail) throws Exception {
            // Collect application-specific metrics
            if (runtimeDetail.getCustomMetrics() == null) {
                runtimeDetail.setCustomMetrics(new java.util.HashMap<>());
            }
            runtimeDetail.getCustomMetrics().put("app_requests_per_sec", 150.0);
            runtimeDetail.getCustomMetrics().put("app_error_rate", 0.02);
        }

        @Override
        public void cleanup() {
            // Cleanup application monitoring
        }
    }
}