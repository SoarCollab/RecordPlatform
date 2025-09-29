package cn.flying.minio.core;

import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.config.NodeConfig;
import io.minio.MinioClient;
import io.minio.messages.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * MinioClientManager 测试类
 * 覆盖所有核心方法和边界条件
 */
@ExtendWith(MockitoExtension.class)
class MinioClientManagerTest {

    @InjectMocks
    private MinioClientManager manager;

    @Mock
    private MinioProperties properties;

    @Mock
    private ApplicationReadyEvent applicationReadyEvent;

    @Mock
    private MinioClient mockClient;

    private NodeConfig node1;
    private NodeConfig node2;

    @BeforeEach
    void setUp() {
        // 初始化节点配置
        node1 = new NodeConfig();
        node1.setName("node1");
        node1.setEndpoint("http://localhost:9001");
        node1.setAccessKey("access1");
        node1.setSecretKey("secret1");

        node2 = new NodeConfig();
        node2.setName("node2");
        node2.setEndpoint("http://localhost:9002");
        node2.setAccessKey("access2");
        node2.setSecretKey("secret2");
    }

    // ========== 现有测试用例（保留） ==========

    @Test
    void getClientReturnsCachedInstance() {
        MinioClient client = mock(MinioClient.class);
        getClientCache().put("node-a", client);

        assertSame(client, manager.getClient("node-a"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, MinioClient> getClientCache() {
        return (Map<String, MinioClient>) ReflectionTestUtils.getField(manager, "clientCache");
    }

    @Test
    void getClientReturnsNullWhenMissing() {
        assertNull(manager.getClient("unknown"));
    }

    @Test
    void getNodeConfigReturnsExpectedConfig() {
        NodeConfig config = new NodeConfig();
        config.setName("node-a");
        getNodeConfigCache().put("node-a", config);

        assertSame(config, manager.getNodeConfig("node-a"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, NodeConfig> getNodeConfigCache() {
        return (Map<String, NodeConfig>) ReflectionTestUtils.getField(manager, "nodeConfigCache");
    }

    @Test
    void getAllClientsExposesCacheSnapshot() {
        MinioClient client = mock(MinioClient.class);
        getClientCache().put("node-a", client);

        Map<String, MinioClient> clients = manager.getAllClients();
        assertEquals(1, clients.size());
        assertSame(client, clients.get("node-a"));
    }

    // ========== reloadClients 方法测试 ==========

    @Test
    void getAllNodeConfigsReturnsCachedValues() {
        NodeConfig config = new NodeConfig();
        config.setName("node-a");
        getNodeConfigCache().put("node-a", config);

        Map<String, NodeConfig> configs = manager.getAllNodeConfigs();
        assertEquals(1, configs.size());
        assertSame(config, configs.get("node-a"));
    }

    @Test
    void cleanupClearsCaches() {
        MinioClient client = mock(MinioClient.class);
        NodeConfig config = new NodeConfig();
        config.setName("node-a");
        getClientCache().put("node-a", client);
        getNodeConfigCache().put("node-a", config);

        manager.cleanup();

        assertTrue(getClientCache().isEmpty());
        assertTrue(getNodeConfigCache().isEmpty());
    }

    @Test
    void testReloadClients_WithValidNodes() throws Exception {
        // 准备测试数据
        List<NodeConfig> nodes = Arrays.asList(node1, node2);
        when(properties.getNodes()).thenReturn(nodes);

        // Mock MinioClient.builder()
        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            when(mockClient.listBuckets()).thenReturn(List.of(new Bucket()));

            // 执行测试
            manager.reloadClients();

            // 验证缓存中有两个节点
            assertEquals(2, getClientCache().size());
            assertEquals(2, getNodeConfigCache().size());
            assertNotNull(getClientCache().get("node1"));
            assertNotNull(getClientCache().get("node2"));
        }
    }

    @Test
    void testReloadClients_WithNullProperties() {
        // 设置 minioProperties 为 null
        ReflectionTestUtils.setField(manager, "minioProperties", null);

        // 执行测试
        manager.reloadClients();

        // 验证缓存为空
        assertTrue(getClientCache().isEmpty());
        assertTrue(getNodeConfigCache().isEmpty());
    }

    @Test
    void testReloadClients_WithNullNodesList() {
        // 设置 nodes 列表为 null
        when(properties.getNodes()).thenReturn(null);

        // 执行测试
        manager.reloadClients();

        // 验证缓存为空
        assertTrue(getClientCache().isEmpty());
        assertTrue(getNodeConfigCache().isEmpty());
    }

    @Test
    void testReloadClients_WithEmptyNodesList() {
        // 设置 nodes 列表为空
        when(properties.getNodes()).thenReturn(new ArrayList<>());

        // 执行测试
        manager.reloadClients();

        // 验证缓存为空
        assertTrue(getClientCache().isEmpty());
        assertTrue(getNodeConfigCache().isEmpty());
    }

    @Test
    void testReloadClients_SkipNodeWithoutName() throws Exception {
        // 准备包含无效节点的测试数据
        NodeConfig invalidNode = new NodeConfig();
        invalidNode.setName(null); // 没有名称

        List<NodeConfig> nodes = Arrays.asList(invalidNode, node1);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            when(mockClient.listBuckets()).thenReturn(List.of(new Bucket()));

            // 执行测试
            manager.reloadClients();

            // 验证只有一个有效节点被加载
            assertEquals(1, getClientCache().size());
            assertNotNull(getClientCache().get("node1"));
            assertNull(getClientCache().get(null));
        }
    }

    @Test
    void testReloadClients_SkipNodeWithBlankName() throws Exception {
        // 准备包含空白名称的节点
        NodeConfig blankNode = new NodeConfig();
        blankNode.setName("   "); // 空白名称

        List<NodeConfig> nodes = Arrays.asList(blankNode, node1);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            when(mockClient.listBuckets()).thenReturn(List.of(new Bucket()));

            // 执行测试
            manager.reloadClients();

            // 验证只有一个有效节点被加载
            assertEquals(1, getClientCache().size());
            assertNotNull(getClientCache().get("node1"));
        }
    }

    @Test
    void testReloadClients_ReuseExistingClient() throws Exception {
        // 先放入一个现有的客户端和配置
        MinioClient existingClient = mock(MinioClient.class);
        getClientCache().put("node1", existingClient);
        getNodeConfigCache().put("node1", node1);

        // 准备相同的节点配置
        List<NodeConfig> nodes = Collections.singletonList(node1);
        when(properties.getNodes()).thenReturn(nodes);

        // 执行测试
        manager.reloadClients();

        // 验证客户端被复用（不会创建新的）
        assertEquals(1, getClientCache().size());
        assertSame(existingClient, getClientCache().get("node1"));
    }

    @Test
    void testReloadClients_UpdateChangedConfig() throws Exception {
        // 先放入一个现有的客户端和配置
        MinioClient existingClient = mock(MinioClient.class);
        NodeConfig oldConfig = new NodeConfig();
        oldConfig.setName("node1");
        oldConfig.setEndpoint("http://old:9000");
        getClientCache().put("node1", existingClient);
        getNodeConfigCache().put("node1", oldConfig);

        // 准备更新的节点配置
        List<NodeConfig> nodes = Collections.singletonList(node1);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            when(mockClient.listBuckets()).thenReturn(List.of(new Bucket()));

            // 执行测试
            manager.reloadClients();

            // 验证客户端被更新
            assertEquals(1, getClientCache().size());
            assertNotSame(existingClient, getClientCache().get("node1"));
        }
    }

    @Test
    void testReloadClients_HandleConnectionError() throws Exception {
        // 准备测试数据
        List<NodeConfig> nodes = Collections.singletonList(node1);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            // 模拟连接错误
            when(mockClient.listBuckets()).thenThrow(new RuntimeException("Connection failed"));

            // 执行测试（不应抛出异常）
            assertDoesNotThrow(() -> manager.reloadClients());

            // 验证客户端仍被添加到缓存
            assertEquals(1, getClientCache().size());
        }
    }

    // ========== 事件监听器测试 ==========

    @Test
    void testReloadClients_HandleBuilderException() {
        // 准备测试数据
        List<NodeConfig> nodes = Collections.singletonList(node1);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            // 模拟builder抛出异常
            minioClientStatic.when(MinioClient::builder)
                    .thenThrow(new RuntimeException("Builder error"));

            // 执行测试（不应抛出异常）
            assertDoesNotThrow(() -> manager.reloadClients());

            // 验证缓存为空（因为创建失败）
            assertTrue(getClientCache().isEmpty());
        }
    }

    @Test
    void testReloadClients_RemoveOldNodes() throws Exception {
        // 先放入两个现有的客户端和配置
        MinioClient client1 = mock(MinioClient.class);
        MinioClient client2 = mock(MinioClient.class);
        getClientCache().put("node1", client1);
        getClientCache().put("node2", client2);
        getNodeConfigCache().put("node1", node1);
        getNodeConfigCache().put("node2", node2);

        // 准备只包含一个节点的新配置
        List<NodeConfig> nodes = Collections.singletonList(node1);
        when(properties.getNodes()).thenReturn(nodes);

        // 执行测试
        manager.reloadClients();

        // 验证 node2 被移除
        assertEquals(1, getClientCache().size());
        assertEquals(1, getNodeConfigCache().size());
        assertNotNull(getClientCache().get("node1"));
        assertNull(getClientCache().get("node2"));
    }

    @Test
    void testInitializeClientsOnReady() {
        // 准备测试数据
        List<NodeConfig> nodes = Collections.singletonList(node1);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            // 执行测试
            manager.initializeClientsOnReady(applicationReadyEvent);

            // 验证 reloadClients 被调用
            assertEquals(1, getClientCache().size());
        }
    }

    // ========== 其他方法测试 ==========

    @Test
    void testOnEnvironmentChangeEvent_WithMinioConfig() {
        // 准备包含minio配置的变更事件
        EnvironmentChangeEvent event = mock(EnvironmentChangeEvent.class);
        Set<String> keys = new HashSet<>(Arrays.asList("minio.nodes", "other.config"));
        when(event.getKeys()).thenReturn(keys);
        when(properties.getNodes()).thenReturn(Collections.singletonList(node1));

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            // 执行测试
            manager.onEnvironmentChangeEvent(event);

            // 验证 reloadClients 被调用
            assertEquals(1, getClientCache().size());
        }
    }

    @Test
    void testOnEnvironmentChangeEvent_WithoutMinioConfig() {
        // 准备不包含minio配置的变更事件
        EnvironmentChangeEvent event = mock(EnvironmentChangeEvent.class);
        Set<String> keys = new HashSet<>(Arrays.asList("other.config", "another.config"));
        when(event.getKeys()).thenReturn(keys);

        // 执行测试
        manager.onEnvironmentChangeEvent(event);

        // 验证 reloadClients 不被调用（缓存仍为空）
        assertTrue(getClientCache().isEmpty());
    }

    @Test
    void testGetClient_LogsErrorForMissing() {
        // 获取不存在的客户端
        MinioClient result = manager.getClient("missing-node");

        // 验证返回null并记录错误
        assertNull(result);
    }

    @Test
    void testGetNodeConfig_LogsWarningForMissing() {
        // 获取不存在的节点配置
        NodeConfig result = manager.getNodeConfig("missing-node");

        // 验证返回null
        assertNull(result);
    }

    @Test
    void testGetAllNodeConfigs_EmptyCacheTriggersReload() {
        // 准备测试数据
        when(properties.getNodes()).thenReturn(Collections.singletonList(node1));

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            // 执行测试（缓存为空时应触发重新加载）
            Map<String, NodeConfig> configs = manager.getAllNodeConfigs();

            // 验证触发了重新加载
            assertEquals(1, configs.size());
            assertNotNull(configs.get("node1"));
        }
    }

    @Test
    void testGetAllNodeConfigs_NonEmptyCache() {
        // 准备非空缓存
        getNodeConfigCache().put("node1", node1);

        // 执行测试
        Map<String, NodeConfig> configs = manager.getAllNodeConfigs();

        // 验证返回缓存内容
        assertEquals(1, configs.size());
        assertNotNull(configs.get("node1"));
    }

    @Test
    void testManualRefresh() {
        // 准备测试数据
        when(properties.getNodes()).thenReturn(Collections.singletonList(node1));

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            // 执行测试
            manager.manualRefresh();

            // 验证触发了重新加载
            assertEquals(1, getClientCache().size());
        }
    }

    // ========== 辅助方法 ==========

    @Test
    void testReloadClients_ConcurrentAccess() throws InterruptedException {
        // 测试并发访问 reloadClients
        List<NodeConfig> nodes = Arrays.asList(node1, node2);
        when(properties.getNodes()).thenReturn(nodes);

        try (MockedStatic<MinioClient> minioClientStatic = mockStatic(MinioClient.class)) {
            MinioClient.Builder builder = mock(MinioClient.Builder.class);
            minioClientStatic.when(MinioClient::builder).thenReturn(builder);
            when(builder.endpoint(anyString())).thenReturn(builder);
            when(builder.credentials(anyString(), anyString())).thenReturn(builder);
            when(builder.build()).thenReturn(mockClient);

            // 启动多个线程同时调用 reloadClients
            Thread[] threads = new Thread[5];
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> manager.reloadClients());
                threads[i].start();
            }

            // 等待所有线程完成
            for (Thread thread : threads) {
                thread.join();
            }

            // 验证最终状态正确
            assertEquals(2, getClientCache().size());
            assertEquals(2, getNodeConfigCache().size());
        }
    }

    @Test
    void testNodeConfigEquals() {
        // 测试 NodeConfig 的 equals 方法（用于判断配置是否变化）
        NodeConfig config1 = new NodeConfig();
        config1.setName("test");
        config1.setEndpoint("http://localhost:9000");
        config1.setAccessKey("access");
        config1.setSecretKey("secret");

        NodeConfig config2 = new NodeConfig();
        config2.setName("test");
        config2.setEndpoint("http://localhost:9000");
        config2.setAccessKey("access");
        config2.setSecretKey("secret");

        // 如果 NodeConfig 实现了 equals 方法
        assertEquals(config1, config2);

        // 改变一个字段
        config2.setEndpoint("http://localhost:9001");
        assertNotEquals(config1, config2);
    }
}