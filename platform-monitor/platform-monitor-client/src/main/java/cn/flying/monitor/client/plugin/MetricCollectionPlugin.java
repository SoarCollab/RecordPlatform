package cn.flying.monitor.client.plugin;

import cn.flying.monitor.client.entity.RuntimeDetail;

/**
 * Interface for custom metric collection plugins
 */
public interface MetricCollectionPlugin {
    
    /**
     * Get the name of this plugin
     */
    String getName();
    
    /**
     * Get the version of this plugin
     */
    String getVersion();
    
    /**
     * Check if this plugin is enabled
     */
    boolean isEnabled();
    
    /**
     * Initialize the plugin
     */
    void initialize() throws Exception;
    
    /**
     * Collect custom metrics and add them to the runtime detail
     */
    void collectMetrics(RuntimeDetail runtimeDetail) throws Exception;
    
    /**
     * Cleanup resources when plugin is disabled or system shuts down
     */
    void cleanup();
    
    /**
     * Get plugin configuration properties
     */
    default java.util.Map<String, Object> getConfiguration() {
        return java.util.Collections.emptyMap();
    }
    
    /**
     * Validate plugin configuration
     */
    default boolean validateConfiguration() {
        return true;
    }
}