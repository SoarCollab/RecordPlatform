package cn.flying.service.monitor;

import cn.flying.platformapi.response.StorageCapacityVO;
import cn.flying.platformapi.response.StorageNodeCapacityVO;
import cn.flying.service.SystemMonitorService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StorageCapacityMetricsBinder Tests")
class StorageCapacityMetricsBinderTest {

    @Mock
    private SystemMonitorService systemMonitorService;

    private SimpleMeterRegistry meterRegistry;
    private StorageCapacityMetricsBinder binder;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        binder = new StorageCapacityMetricsBinder(meterRegistry, systemMonitorService);
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("Should publish bridged node metrics from prometheus snapshot")
    void shouldPublishBridgedNodeMetricsFromPrometheusSnapshot() {
        when(systemMonitorService.getStorageCapacity()).thenReturn(storageCapacity(
                "prometheus",
                List.of(
                        new StorageNodeCapacityVO("node-a", "domain-a", true, 100L, 45L, 45D),
                        new StorageNodeCapacityVO("node-b", "domain-b", false, 100L, 87L, 87D)
                )
        ));

        binder.refreshStorageNodeMetrics();

        assertThat(gaugeValue("s3.node.online.status", "node-a", "domain-a")).isEqualTo(1D);
        assertThat(gaugeValue("s3.node.online.status", "node-b", "domain-b")).isEqualTo(0D);
        assertThat(gaugeValue("s3.node.usage.percent", "node-a", "domain-a")).isEqualTo(45D);
        assertThat(gaugeValue("s3.node.usage.percent", "node-b", "domain-b")).isEqualTo(87D);
    }

    @Test
    @DisplayName("Should keep last bridged metrics when fallback snapshot has no node breakdown")
    void shouldKeepLastBridgedMetricsWhenFallbackSnapshotHasNoNodeBreakdown() {
        when(systemMonitorService.getStorageCapacity())
                .thenReturn(storageCapacity(
                        "prometheus",
                        List.of(new StorageNodeCapacityVO("node-a", "domain-a", true, 100L, 55L, 55D))
                ))
                .thenReturn(storageCapacity("fallback", List.of()));

        binder.refreshStorageNodeMetrics();
        binder.refreshStorageNodeMetrics();

        assertThat(gaugeValue("s3.node.online.status", "node-a", "domain-a")).isEqualTo(1D);
        assertThat(gaugeValue("s3.node.usage.percent", "node-a", "domain-a")).isEqualTo(55D);
    }

    @Test
    @DisplayName("Should remove bridged metrics when storage reports no configured nodes")
    void shouldRemoveBridgedMetricsWhenStorageReportsNoConfiguredNodes() {
        when(systemMonitorService.getStorageCapacity())
                .thenReturn(storageCapacity(
                        "prometheus",
                        List.of(new StorageNodeCapacityVO("node-a", "domain-a", true, 100L, 35L, 35D))
                ))
                .thenReturn(storageCapacity("prometheus-no-nodes", List.of()));

        binder.refreshStorageNodeMetrics();
        binder.refreshStorageNodeMetrics();

        assertThat(gaugeValue("s3.node.online.status", "node-a", "domain-a")).isNull();
        assertThat(gaugeValue("s3.node.usage.percent", "node-a", "domain-a")).isNull();
    }

    /**
     * 构造最小化的存储容量快照，供桥接逻辑验证使用。
     */
    private StorageCapacityVO storageCapacity(String source, List<StorageNodeCapacityVO> nodes) {
        return new StorageCapacityVO(0L, 0L, 0L, false, source, nodes, List.of());
    }

    /**
     * 按节点标签读取当前注册表中的 gauge 值。
     */
    private Double gaugeValue(String meterName, String nodeName, String faultDomain) {
        var gauge = meterRegistry.find(meterName)
                .tag("node", nodeName)
                .tag("fault_domain", faultDomain)
                .gauge();
        return gauge == null ? null : gauge.value();
    }
}
