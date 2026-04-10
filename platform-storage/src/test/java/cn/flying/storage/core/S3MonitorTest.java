package cn.flying.storage.core;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S3Monitor Unit Tests")
class S3MonitorTest {

    @Mock
    private S3ClientManager clientManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private StorageProperties storageProperties;

    private MeterRegistry meterRegistry;

    private S3Monitor monitor;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        monitor = new S3Monitor(meterRegistry);
        ReflectionTestUtils.setField(monitor, "clientManager", clientManager);
        ReflectionTestUtils.setField(monitor, "eventPublisher", eventPublisher);
        ReflectionTestUtils.setField(monitor, "storageProperties", storageProperties);
    }

    @Test
    @DisplayName("Should clear cached capacity metrics when node config list is empty")
    void shouldClearCachedCapacityMetricsWhenNodeConfigListIsEmpty() {
        String nodeName = "node-a";
        S3Monitor.NodeMetrics metrics = createMetrics(1_024L, 256L);
        knownNodes().add(nodeName);
        onlineNodes().add(nodeName);
        nodeMetricsCache().put(nodeName, metrics);

        // Pre-register gauges by triggering online status
        ReflectionTestUtils.invokeMethod(monitor, "getOrCreateOnlineStatusValue", nodeName);
        ReflectionTestUtils.invokeMethod(monitor, "getOrCreateLoadScoreValue", nodeName);

        when(clientManager.getAllNodeConfigs()).thenReturn(Collections.emptyMap());

        monitor.scheduledCheckNodes();

        assertThat(onlineNodes()).doesNotContain(nodeName);
        assertThat(knownNodes()).contains(nodeName);
        assertThat(monitor.getNodeMetrics(nodeName)).isNull();
        assertThat(gaugeValue("s3_node_online_status", nodeName)).isEqualTo(0D);
        assertThat(findGauge("s3_node_load_score", nodeName)).isNull();
    }

    @Test
    @DisplayName("Should keep cached metrics when node transitions offline")
    void shouldKeepCachedMetricsWhenNodeTransitionsOffline() {
        String nodeName = "node-a";
        S3Monitor.NodeMetrics metrics = createMetrics(2_048L, 1_024L);
        knownNodes().add(nodeName);
        onlineNodes().add(nodeName);
        nodeMetricsCache().put(nodeName, metrics);

        // Pre-register gauges
        ReflectionTestUtils.invokeMethod(monitor, "getOrCreateOnlineStatusValue", nodeName);
        ReflectionTestUtils.invokeMethod(monitor, "getOrCreateLoadScoreValue", nodeName);

        when(storageProperties.getNodes()).thenReturn(List.of(createNode(nodeName, "domain-a")));

        ReflectionTestUtils.invokeMethod(monitor, "markNodeOffline", nodeName, "simulated outage");

        assertThat(monitor.isNodeOnline(nodeName)).isFalse();
        assertThat(knownNodes()).contains(nodeName);
        assertThat(monitor.getNodeMetrics(nodeName)).isSameAs(metrics);
        assertThat(gaugeValue("s3_node_online_status", nodeName)).isEqualTo(0D);
        assertThat(findGauge("s3_node_load_score", nodeName)).isNull();
    }

    /**
     * 构造带容量采样时间的节点指标，模拟最近一次 Prometheus 抓取结果。
     */
    private S3Monitor.NodeMetrics createMetrics(long totalBytes, long usedBytes) {
        S3Monitor.NodeMetrics metrics = new S3Monitor.NodeMetrics();
        metrics.setDiskTotalBytes(totalBytes);
        metrics.setDiskUsedBytes(usedBytes);
        metrics.setDiskUsagePercent((double) usedBytes / totalBytes * 100);
        metrics.setFetchTime(Instant.now());
        return metrics;
    }

    /**
     * 构造用于拓扑事件与故障域解析的最小节点配置。
     */
    private NodeConfig createNode(String name, String faultDomain) {
        NodeConfig node = new NodeConfig();
        node.setName(name);
        node.setFaultDomain(faultDomain);
        node.setEnabled(true);
        return node;
    }

    @SuppressWarnings("unchecked")
    private Set<String> onlineNodes() {
        return (Set<String>) ReflectionTestUtils.getField(monitor, "onlineNodes");
    }

    @SuppressWarnings("unchecked")
    private Set<String> knownNodes() {
        return (Set<String>) ReflectionTestUtils.getField(monitor, "knownNodes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, S3Monitor.NodeMetrics> nodeMetricsCache() {
        return (Map<String, S3Monitor.NodeMetrics>) ReflectionTestUtils.getField(monitor, "nodeMetricsCache");
    }

    /**
     * 从 Micrometer 注册表中查找 gauge 值
     */
    private Double gaugeValue(String metricName, String nodeName) {
        Gauge gauge = findGauge(metricName, nodeName);
        return gauge != null ? gauge.value() : null;
    }

    /**
     * 从 Micrometer 注册表中查找 gauge
     */
    private Gauge findGauge(String metricName, String nodeName) {
        return meterRegistry.find(metricName)
                .tag("node", nodeName)
                .gauge();
    }
}
