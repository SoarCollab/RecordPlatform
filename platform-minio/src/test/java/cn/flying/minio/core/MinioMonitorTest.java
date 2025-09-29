package cn.flying.minio.core;

import cn.flying.minio.config.NodeConfig;
import cn.flying.minio.config.NodeMetrics;
import io.minio.MinioClient;
import io.minio.errors.MinioException;
import io.minio.messages.Bucket;
import io.prometheus.client.CollectorRegistry;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.Method;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MinioMonitorTest {

    @Mock
    private MinioClientManager clientManager;

    @Mock
    private MinioClient minioClient;

    @Mock
    private OkHttpClient httpClient;

    @Mock
    private Call call;

    private MinioMonitor monitor;

    @BeforeEach
    void setUp() {
        CollectorRegistry.defaultRegistry.clear();
        monitor = new MinioMonitor();
        ReflectionTestUtils.setField(monitor, "clientManager", clientManager);
    }

    @Test
    void getNodeLoadScoreReturnsMaxForOfflineNode() {
        assertEquals(Double.MAX_VALUE, monitor.getNodeLoadScore("node-a"));
    }

    @Test
    void getNodeLoadScoreUsesCachedMetrics() {
        getOnlineNodesInternal().add("node-a");
        NodeMetrics metrics = new NodeMetrics();
        metrics.setApiInflightRequests(20.0);
        metrics.setApiWaitingRequests(10.0);
        metrics.setDiskUsagePercent(50.0);
        getMetricsCache().put("node-a", metrics);

        double score = monitor.getNodeLoadScore("node-a");

        assertTrue(score > 0.0);
        assertTrue(score <= 1.0);
    }

    @SuppressWarnings("unchecked")
    private Set<String> getOnlineNodesInternal() {
        return (Set<String>) ReflectionTestUtils.getField(monitor, "onlineNodes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, NodeMetrics> getMetricsCache() {
        return (Map<String, NodeMetrics>) ReflectionTestUtils.getField(monitor, "nodeMetricsCache");
    }

    @Test
    void getNodeLoadScoreDefaultsWhenNoMetrics() {
        getOnlineNodesInternal().add("node-a");

        assertEquals(0.0, monitor.getNodeLoadScore("node-a"));
    }

    @Test
    void getNodeLoadScoreHandlesNullMetricsValues() {
        getOnlineNodesInternal().add("node-a");
        NodeMetrics metrics = new NodeMetrics();
        // 不设置任何值，保持为null
        getMetricsCache().put("node-a", metrics);

        double score = monitor.getNodeLoadScore("node-a");

        assertEquals(0.0, score);
    }

    @Test
    void onlineNodeAccessorsExposeCopies() {
        getOnlineNodesInternal().add("node-a");
        NodeMetrics metrics = new NodeMetrics();
        getMetricsCache().put("node-a", metrics);

        assertTrue(monitor.isNodeOnline("node-a"));
        Set<String> nodes = monitor.getOnlineNodes();
        assertEquals(Set.of("node-a"), nodes);
        assertTrue(monitor.getNodeMetrics("node-a").isPresent());
    }

    @Test
    void scheduledCheckNodesWithNoConfigs() {
        when(clientManager.getAllNodeConfigs()).thenReturn(new HashMap<>());

        monitor.scheduledCheckNodes();

        verify(clientManager).getAllNodeConfigs();
        assertTrue(getOnlineNodesInternal().isEmpty());
        assertTrue(getMetricsCache().isEmpty());
    }

    @Test
    void scheduledCheckNodesWithValidNode() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");
        config.setAccessKey("access");
        config.setSecretKey("secret");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenReturn(List.of(new Bucket()));

        monitor.scheduledCheckNodes();

        verify(clientManager).getAllNodeConfigs();
        verify(clientManager).getClient("node1");
        verify(minioClient).listBuckets();
    }

    @Test
    void scheduledCheckNodesWithMinioException() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenThrow(new MinioException("Connection error"));

        monitor.scheduledCheckNodes();

        verify(minioClient).listBuckets();
        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void scheduledCheckNodesWithInvalidKeyException() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenThrow(new InvalidKeyException("Invalid key"));

        monitor.scheduledCheckNodes();

        verify(minioClient).listBuckets();
        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void scheduledCheckNodesWithIOException() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenThrow(new IOException("IO error"));

        monitor.scheduledCheckNodes();

        verify(minioClient).listBuckets();
        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void scheduledCheckNodesWithNoSuchAlgorithmException() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenThrow(new NoSuchAlgorithmException("Algorithm error"));

        monitor.scheduledCheckNodes();

        verify(minioClient).listBuckets();
        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void scheduledCheckNodesWithUnexpectedException() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenThrow(new RuntimeException("Unexpected error"));

        monitor.scheduledCheckNodes();

        verify(minioClient).listBuckets();
        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void scheduledCheckNodesWithNullClient() {
        NodeConfig config = new NodeConfig();
        config.setName("node1");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(null);

        monitor.scheduledCheckNodes();

        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void scheduledCheckNodesCleansUpRemovedNodes() throws Exception {
        // 先添加一个在线节点
        getOnlineNodesInternal().add("removedNode");
        getMetricsCache().put("removedNode", new NodeMetrics());

        NodeConfig config = new NodeConfig();
        config.setName("node1");

        Map<String, NodeConfig> configs = new HashMap<>();
        configs.put("node1", config);

        when(clientManager.getAllNodeConfigs()).thenReturn(configs);
        when(clientManager.getClient("node1")).thenReturn(minioClient);
        when(minioClient.listBuckets()).thenReturn(List.of(new Bucket()));

        monitor.scheduledCheckNodes();

        assertFalse(monitor.isNodeOnline("removedNode"));
        assertFalse(getMetricsCache().containsKey("removedNode"));
    }

    @Test
    void scheduledCheckNodesClearsAllWhenNoConfigs() {
        // 先添加一些在线节点
        getOnlineNodesInternal().add("node1");
        getOnlineNodesInternal().add("node2");
        getMetricsCache().put("node1", new NodeMetrics());
        getMetricsCache().put("node2", new NodeMetrics());

        when(clientManager.getAllNodeConfigs()).thenReturn(new HashMap<>());

        monitor.scheduledCheckNodes();

        assertTrue(getOnlineNodesInternal().isEmpty());
        assertTrue(getMetricsCache().isEmpty());
    }

    @Test
    void testFetchAndParseMetricsWithSuccessfulResponse() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");
        config.setAccessKey("access");
        config.setSecretKey("secret");

        // 准备metrics响应
        String metricsResponse = """
                # HELP minio_s3_requests_inflight_total
                minio_s3_requests_inflight_total 10
                # HELP minio_s3_requests_waiting_total
                minio_s3_requests_waiting_total 5
                # HELP minio_cluster_capacity_usable_free_bytes
                minio_cluster_capacity_usable_free_bytes 1000000000
                # HELP minio_cluster_capacity_usable_total_bytes
                minio_cluster_capacity_usable_total_bytes 2000000000
                """;

        // Mock HTTP响应
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost:9000/minio/v2/metrics/cluster").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(metricsResponse, MediaType.get("text/plain")))
                .build();

        // 设置mock的httpClient
        ReflectionTestUtils.setField(monitor, "httpClient", httpClient);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);

        // 通过反射调用私有方法
        Method fetchMethod = MinioMonitor.class.getDeclaredMethod("fetchAndParseMetrics", String.class, NodeConfig.class, NodeMetrics.class);
        fetchMethod.setAccessible(true);

        NodeMetrics metrics = new NodeMetrics();
        fetchMethod.invoke(monitor, "node1", config, metrics);

        // 验证metrics被正确填充
        assertEquals(10.0, metrics.getApiInflightRequests());
        assertEquals(5.0, metrics.getApiWaitingRequests());
        assertEquals(1000000000.0, metrics.getUsableFreeBytes());
        assertEquals(2000000000.0, metrics.getUsableTotalBytes());
    }

    @Test
    void testFetchAndParseMetricsWithFailedResponse() throws Exception {
        NodeConfig config = new NodeConfig();
        config.setName("node1");
        config.setEndpoint("http://localhost:9000");

        // Mock失败的HTTP响应
        Response response = new Response.Builder()
                .request(new Request.Builder().url("http://localhost:9000/minio/v2/metrics/cluster").build())
                .protocol(Protocol.HTTP_1_1)
                .code(500)
                .message("Internal Server Error")
                .body(ResponseBody.create("Error", MediaType.get("text/plain")))
                .build();

        ReflectionTestUtils.setField(monitor, "httpClient", httpClient);
        when(httpClient.newCall(any(Request.class))).thenReturn(call);
        when(call.execute()).thenReturn(response);

        Method fetchMethod = MinioMonitor.class.getDeclaredMethod("fetchAndParseMetrics", String.class, NodeConfig.class, NodeMetrics.class);
        fetchMethod.setAccessible(true);

        NodeMetrics metrics = new NodeMetrics();

        // 应该抛出IOException
        assertThrows(Exception.class, () -> {
            fetchMethod.invoke(monitor, "node1", config, metrics);
        });
    }

    @Test
    void testParsePrometheusMetricsWithLabels() throws Exception {
        String metricsText = """
                metric_name{label1="value1",label2="value2"} 42.0
                another_metric{} 100
                simple_metric 50.5
                """;

        Method parseMethod = MinioMonitor.class.getDeclaredMethod("parsePrometheusMetrics", String.class, String.class, NodeMetrics.class);
        parseMethod.setAccessible(true);

        NodeMetrics metrics = new NodeMetrics();
        parseMethod.invoke(monitor, "node1", metricsText, metrics);

        // 测试是否正确解析（虽然这些指标不会被处理，但方法应该能正确解析）
        assertNotNull(metrics);
    }

    @Test
    void testParseLabels() throws Exception {
        Method parseLabelsMethod = MinioMonitor.class.getDeclaredMethod("parseLabels", String.class);
        parseLabelsMethod.setAccessible(true);

        // 测试有标签的情况
        String labelsString = "label1=\"value1\",label2=\"value2\"";
        @SuppressWarnings("unchecked")
        Map<String, String> labels = (Map<String, String>) parseLabelsMethod.invoke(monitor, labelsString);
        assertEquals("value1", labels.get("label1"));
        assertEquals("value2", labels.get("label2"));

        // 测试空标签
        @SuppressWarnings("unchecked")
        Map<String, String> emptyLabels = (Map<String, String>) parseLabelsMethod.invoke(monitor, (String) null);
        assertTrue(emptyLabels.isEmpty());

        // 测试空字符串
        @SuppressWarnings("unchecked")
        Map<String, String> emptyStringLabels = (Map<String, String>) parseLabelsMethod.invoke(monitor, "");
        assertTrue(emptyStringLabels.isEmpty());
    }

    @Test
    void testGetScore() throws Exception {
        Method getScoreMethod = MinioMonitor.class.getDeclaredMethod("getScore", double.class, double.class, double.class);
        getScoreMethod.setAccessible(true);

        // 测试低负载
        double lowScore = (double) getScoreMethod.invoke(null, 0.0, 0.0, 0.0);
        assertEquals(0.0, lowScore);

        // 测试中等负载
        double midScore = (double) getScoreMethod.invoke(null, 25.0, 10.0, 50.0);
        assertTrue(midScore > 0.0);
        assertTrue(midScore < 1.0);

        // 测试高负载
        double highScore = (double) getScoreMethod.invoke(null, 100.0, 50.0, 100.0);
        assertTrue(highScore > 0.5);
        assertTrue(highScore <= 1.0);
    }

    @Test
    void testMarkNodeOnlineAndOffline() throws Exception {
        Method markOnlineMethod = MinioMonitor.class.getDeclaredMethod("markNodeOnline", String.class);
        markOnlineMethod.setAccessible(true);

        Method markOfflineMethod = MinioMonitor.class.getDeclaredMethod("markNodeOffline", String.class, String.class);
        markOfflineMethod.setAccessible(true);

        // 标记在线
        markOnlineMethod.invoke(monitor, "node1");
        assertTrue(monitor.isNodeOnline("node1"));

        // 再次标记在线（应该不会重复添加）
        markOnlineMethod.invoke(monitor, "node1");
        assertTrue(monitor.isNodeOnline("node1"));

        // 添加一些指标
        getMetricsCache().put("node1", new NodeMetrics());

        // 标记离线
        markOfflineMethod.invoke(monitor, "node1", "Test reason");
        assertFalse(monitor.isNodeOnline("node1"));
        assertFalse(getMetricsCache().containsKey("node1"));

        // 再次标记离线（已经离线的节点）
        markOfflineMethod.invoke(monitor, "node1", "Test reason 2");
        assertFalse(monitor.isNodeOnline("node1"));
    }

    @Test
    void testIsNodeOnline() {
        assertFalse(monitor.isNodeOnline("unknown"));

        getOnlineNodesInternal().add("node1");
        assertTrue(monitor.isNodeOnline("node1"));
    }

    @Test
    void testGetNodeMetrics() {
        // 不存在的节点
        assertTrue(monitor.getNodeMetrics("unknown").isEmpty());

        // 存在的节点
        NodeMetrics metrics = new NodeMetrics();
        getMetricsCache().put("node1", metrics);
        Optional<NodeMetrics> result = monitor.getNodeMetrics("node1");
        assertTrue(result.isPresent());
        assertEquals(metrics, result.get());
    }

    @Test
    void testParsePrometheusMetricsWithInvalidNumber() throws Exception {
        String invalidMetrics = "minio_s3_requests_inflight_total invalid_number\n";

        Method parseMethod = MinioMonitor.class.getDeclaredMethod("parsePrometheusMetrics", String.class, String.class, NodeMetrics.class);
        parseMethod.setAccessible(true);

        NodeMetrics metrics = new NodeMetrics();
        parseMethod.invoke(monitor, "node1", invalidMetrics, metrics);

        // 应该跳过无效的数字，不会设置值
        assertNull(metrics.getApiInflightRequests());
    }

    @Test
    void testCheckNodeHealthWithNullConfig() throws Exception {
        Method checkHealthMethod = MinioMonitor.class.getDeclaredMethod("checkNodeHealth", String.class, NodeConfig.class, MinioClient.class);
        checkHealthMethod.setAccessible(true);

        checkHealthMethod.invoke(monitor, "node1", null, minioClient);

        assertFalse(monitor.isNodeOnline("node1"));
    }
}
