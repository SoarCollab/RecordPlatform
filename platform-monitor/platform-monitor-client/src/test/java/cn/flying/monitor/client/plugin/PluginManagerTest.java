package cn.flying.monitor.client.plugin;

import cn.flying.monitor.client.entity.RuntimeDetail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for plugin management and custom metric collection
 */
class PluginManagerTest {

    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        pluginManager = new PluginManager();
        
        // Set test configuration
        ReflectionTestUtils.setField(pluginManager, "customMetricsEnabled", true);
        ReflectionTestUtils.setField(pluginManager, "pluginTimeoutSeconds", 2);
    }

    @Test
    void testPluginManagerInitialization() {
        // When
        pluginManager.initialize();

        // Then
        List<String> activePlugins = pluginManager.getActivePlugins();
        assertNotNull(activePlugins);
        
        String stats = pluginManager.getPluginStats();
        assertNotNull(stats);
        assertTrue(stats.contains("Plugins:"));
    }

    @Test
    void testPluginManagerDisabled() {
        // Given
        ReflectionTestUtils.setField(pluginManager, "customMetricsEnabled", false);

        // When
        pluginManager.initialize();

        // Then
        List<String> activePlugins = pluginManager.getActivePlugins();
        assertTrue(activePlugins.isEmpty());
    }

    @Test
    void testCollectPluginMetricsWhenDisabled() {
        // Given
        ReflectionTestUtils.setField(pluginManager, "customMetricsEnabled", false);
        pluginManager.initialize();
        
        RuntimeDetail runtimeDetail = new RuntimeDetail();

        // When
        assertDoesNotThrow(() -> pluginManager.collectPluginMetrics(runtimeDetail));

        // Then - should not add any custom metrics
        assertNull(runtimeDetail.getCustomMetrics());
    }

    @Test
    void testCollectPluginMetricsWhenEnabled() {
        // Given
        // Enable plugins via system properties
        System.setProperty("monitor.plugin.database.enabled", "true");
        System.setProperty("monitor.plugin.application.enabled", "true");
        
        pluginManager.initialize();
        RuntimeDetail runtimeDetail = new RuntimeDetail();

        // When
        pluginManager.collectPluginMetrics(runtimeDetail);

        // Then
        assertNotNull(runtimeDetail.getCustomMetrics());
        assertTrue(runtimeDetail.getCustomMetrics().containsKey("db_connections"));
        assertTrue(runtimeDetail.getCustomMetrics().containsKey("app_requests_per_sec"));
        
        // Cleanup
        System.clearProperty("monitor.plugin.database.enabled");
        System.clearProperty("monitor.plugin.application.enabled");
    }

    @Test
    void testPluginTimeout() {
        // Given
        ReflectionTestUtils.setField(pluginManager, "pluginTimeoutSeconds", 1);
        
        // Create a slow plugin for testing
        TestSlowPlugin slowPlugin = new TestSlowPlugin();
        List<MetricCollectionPlugin> plugins = (List<MetricCollectionPlugin>) 
                ReflectionTestUtils.getField(pluginManager, "plugins");
        plugins.clear();
        plugins.add(slowPlugin);
        
        ReflectionTestUtils.setField(pluginManager, "initialized", true);
        
        RuntimeDetail runtimeDetail = new RuntimeDetail();

        // When
        long startTime = System.currentTimeMillis();
        pluginManager.collectPluginMetrics(runtimeDetail);
        long endTime = System.currentTimeMillis();

        // Then - should complete within reasonable time despite slow plugin
        assertTrue(endTime - startTime < 3000); // Should timeout and complete quickly
    }

    @Test
    void testGetActivePlugins() {
        // Given
        System.setProperty("monitor.plugin.database.enabled", "true");
        pluginManager.initialize();

        // When
        List<String> activePlugins = pluginManager.getActivePlugins();

        // Then
        assertNotNull(activePlugins);
        assertTrue(activePlugins.stream().anyMatch(plugin -> plugin.contains("Database Metrics")));
        
        // Cleanup
        System.clearProperty("monitor.plugin.database.enabled");
    }

    @Test
    void testGetPluginStatsWhenNotInitialized() {
        // When
        String stats = pluginManager.getPluginStats();

        // Then
        assertEquals("Plugin manager not initialized", stats);
    }

    @Test
    void testCleanup() {
        // Given
        pluginManager.initialize();

        // When
        assertDoesNotThrow(() -> pluginManager.cleanup());

        // Then
        String stats = pluginManager.getPluginStats();
        assertEquals("Plugin manager not initialized", stats);
    }

    @Test
    void testPluginValidation() {
        // Given
        TestInvalidPlugin invalidPlugin = new TestInvalidPlugin();
        List<MetricCollectionPlugin> plugins = (List<MetricCollectionPlugin>) 
                ReflectionTestUtils.getField(pluginManager, "plugins");
        plugins.clear();
        plugins.add(invalidPlugin);

        // When
        pluginManager.initialize();

        // Then - invalid plugin should be removed
        List<String> activePlugins = pluginManager.getActivePlugins();
        assertTrue(activePlugins.isEmpty());
    }

    /**
     * Test plugin that takes a long time to execute
     */
    private static class TestSlowPlugin implements MetricCollectionPlugin {
        @Override
        public String getName() { return "Slow Plugin"; }

        @Override
        public String getVersion() { return "1.0.0"; }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public void initialize() throws Exception {}

        @Override
        public void collectMetrics(RuntimeDetail runtimeDetail) throws Exception {
            // Simulate slow operation
            Thread.sleep(5000);
            if (runtimeDetail.getCustomMetrics() == null) {
                runtimeDetail.setCustomMetrics(new java.util.HashMap<>());
            }
            runtimeDetail.getCustomMetrics().put("slow_metric", "value");
        }

        @Override
        public void cleanup() {}
    }

    /**
     * Test plugin with invalid configuration
     */
    private static class TestInvalidPlugin implements MetricCollectionPlugin {
        @Override
        public String getName() { return "Invalid Plugin"; }

        @Override
        public String getVersion() { return "1.0.0"; }

        @Override
        public boolean isEnabled() { return true; }

        @Override
        public boolean validateConfiguration() { return false; } // Invalid configuration

        @Override
        public void initialize() throws Exception {}

        @Override
        public void collectMetrics(RuntimeDetail runtimeDetail) throws Exception {}

        @Override
        public void cleanup() {}
    }
}