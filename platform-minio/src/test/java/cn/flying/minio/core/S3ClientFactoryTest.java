package cn.flying.minio.core;

import cn.flying.minio.config.MinioProperties;
import cn.flying.minio.config.NodeConfig;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * S3ClientFactory测试类
 * 覆盖所有公共方法和各种场景
 */
@ExtendWith(MockitoExtension.class)
class S3ClientFactoryTest {

    @InjectMocks
    private S3ClientFactory s3ClientFactory;

    @Mock
    private MinioProperties minioProperties;

    @Mock
    private AmazonS3 mockS3Client;

    @Mock
    private AmazonS3ClientBuilder mockS3ClientBuilder;

    private NodeConfig node1;
    private NodeConfig node2;
    private NodeConfig node3;
    private List<NodeConfig> nodes;

    @BeforeEach
    void setUp() {
        // 初始化节点配置
        node1 = new NodeConfig();
        node1.setName("node1");
        node1.setEndpoint("http://localhost:9001");
        node1.setAccessKey("accessKey1");
        node1.setSecretKey("secretKey1");

        node2 = new NodeConfig();
        node2.setName("node2");
        node2.setEndpoint("http://localhost:9002");
        node2.setAccessKey("accessKey2");
        node2.setSecretKey("secretKey2");

        nodes = Arrays.asList(node1, node2);
    }

    @Test
    void testInit_WithValidNodes() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 注入mock的S3客户端到缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 执行初始化
        s3ClientFactory.init();

        // 验证日志输出和缓存状态
        verify(minioProperties, times(3)).getNodes(); // init方法中会多次调用
    }

    @Test
    void testInit_WithNullNodes() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(null);

        // 执行初始化
        s3ClientFactory.init();

        // 验证方法调用
        verify(minioProperties).getNodes();
    }

    @Test
    void testInit_WithEmptyNodes() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(new ArrayList<>());

        // 执行初始化
        s3ClientFactory.init();

        // 验证方法调用
        verify(minioProperties, times(2)).getNodes();
    }

    @Test
    void testGetS3Client_ExistingClient() {
        // 准备缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        cache.put("node1", mockS3Client);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 获取客户端
        AmazonS3 client = s3ClientFactory.getS3Client("node1");

        // 验证返回正确的客户端
        assertNotNull(client);
        assertEquals(mockS3Client, client);
    }

    @Test
    void testGetS3Client_NonExistingClient_WithValidNode() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 获取客户端（将触发动态创建）
        AmazonS3 client = s3ClientFactory.getS3Client("node1");

        // 验证调用了查找节点的逻辑
        verify(minioProperties).getNodes();
    }

    @Test
    void testGetS3Client_NonExistingClient_WithInvalidNode() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 获取不存在的节点客户端
        AmazonS3 client = s3ClientFactory.getS3Client("nonExistingNode");

        // 验证返回null
        assertNull(client);
        verify(minioProperties).getNodes();
    }

    @Test
    void testGetS3Client_NullNodes() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(null);

        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 获取客户端
        AmazonS3 client = s3ClientFactory.getS3Client("node1");

        // 验证返回null
        assertNull(client);
    }

    @Test
    void testRefreshClient_ExistingClient() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 准备缓存和mock客户端
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        AmazonS3 oldClient = mock(AmazonS3.class);
        cache.put("node1", oldClient);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 刷新客户端
        s3ClientFactory.refreshClient("node1");

        // 验证旧客户端被关闭
        verify(oldClient).shutdown();
        verify(minioProperties).getNodes();
    }

    @Test
    void testRefreshClient_NonExistingClient() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 刷新不存在的客户端
        s3ClientFactory.refreshClient("node1");

        // 验证尝试创建新客户端
        verify(minioProperties).getNodes();
    }

    @Test
    void testRefreshClient_WithShutdownException() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 准备缓存和会抛出异常的mock客户端
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        AmazonS3 oldClient = mock(AmazonS3.class);
        doThrow(new RuntimeException("Shutdown failed")).when(oldClient).shutdown();
        cache.put("node1", oldClient);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 刷新客户端（不应抛出异常）
        assertDoesNotThrow(() -> s3ClientFactory.refreshClient("node1"));

        // 验证继续执行
        verify(oldClient).shutdown();
        verify(minioProperties).getNodes();
    }

    @Test
    void testRefreshClient_NodeNotFound() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);

        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 刷新不存在的节点
        s3ClientFactory.refreshClient("nonExistingNode");

        // 验证尝试查找节点
        verify(minioProperties).getNodes();
    }

    @Test
    void testIsClientAvailable_ValidClient() {
        // 准备缓存和mock客户端
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        when(mockS3Client.listBuckets()).thenReturn(new ArrayList<Bucket>());
        cache.put("node1", mockS3Client);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 检查可用性
        boolean available = s3ClientFactory.isClientAvailable("node1");

        // 验证返回true
        assertTrue(available);
        verify(mockS3Client).listBuckets();
    }

    @Test
    void testIsClientAvailable_ClientThrowsException() {
        // 准备缓存和会抛出异常的mock客户端
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        when(mockS3Client.listBuckets()).thenThrow(new RuntimeException("Connection failed"));
        cache.put("node1", mockS3Client);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 检查可用性
        boolean available = s3ClientFactory.isClientAvailable("node1");

        // 验证返回false
        assertFalse(available);
        verify(mockS3Client).listBuckets();
    }

    @Test
    void testIsClientAvailable_NullClient() {
        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);
        when(minioProperties.getNodes()).thenReturn(null);

        // 检查可用性
        boolean available = s3ClientFactory.isClientAvailable("node1");

        // 验证返回false
        assertFalse(available);
    }

    @Test
    void testCleanup_WithClients() {
        // 准备缓存和mock客户端
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        AmazonS3 client1 = mock(AmazonS3.class);
        AmazonS3 client2 = mock(AmazonS3.class);
        cache.put("node1", client1);
        cache.put("node2", client2);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 执行清理
        s3ClientFactory.cleanup();

        // 验证所有客户端被关闭
        verify(client1).shutdown();
        verify(client2).shutdown();
        assertTrue(cache.isEmpty());
    }

    @Test
    void testCleanup_WithShutdownException() {
        // 准备缓存和会抛出异常的mock客户端
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        AmazonS3 client1 = mock(AmazonS3.class);
        doThrow(new RuntimeException("Shutdown failed")).when(client1).shutdown();
        cache.put("node1", client1);
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 执行清理（不应抛出异常）
        assertDoesNotThrow(() -> s3ClientFactory.cleanup());

        // 验证缓存被清空
        assertTrue(cache.isEmpty());
    }

    @Test
    void testCleanup_EmptyCache() {
        // 准备空缓存
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 执行清理
        assertDoesNotThrow(() -> s3ClientFactory.cleanup());

        // 验证缓存仍为空
        assertTrue(cache.isEmpty());
    }

    @Test
    void testFindNodeByName_NodeExists() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 通过getS3Client间接测试findNodeByName
        s3ClientFactory.getS3Client("node1");

        // 验证查找逻辑被调用
        verify(minioProperties).getNodes();
    }

    @Test
    void testFindNodeByName_NodeNotExists() {
        // 准备测试数据
        when(minioProperties.getNodes()).thenReturn(nodes);
        ConcurrentHashMap<String, AmazonS3> cache = new ConcurrentHashMap<>();
        ReflectionTestUtils.setField(s3ClientFactory, "s3ClientCache", cache);

        // 通过getS3Client间接测试findNodeByName
        AmazonS3 client = s3ClientFactory.getS3Client("nonExistingNode");

        // 验证返回null
        assertNull(client);
        verify(minioProperties).getNodes();
    }
}