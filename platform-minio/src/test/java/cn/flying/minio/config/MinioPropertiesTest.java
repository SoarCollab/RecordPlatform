package cn.flying.minio.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MinioProperties 测试类
 * 测试配置属性的各种场景
 */
class MinioPropertiesTest {

    private MinioProperties properties;

    @BeforeEach
    void setUp() {
        properties = new MinioProperties();
    }

    @Test
    void testDefaultValues() {
        // 验证默认值
        assertNull(properties.getNodes());
        assertNull(properties.getLogicalMapping());
    }

    @Test
    void testSetAndGetNodes() {
        // 创建节点配置
        NodeConfig node1 = new NodeConfig();
        node1.setName("node1");
        node1.setEndpoint("http://localhost:9001");
        node1.setAccessKey("access1");
        node1.setSecretKey("secret1");

        NodeConfig node2 = new NodeConfig();
        node2.setName("node2");
        node2.setEndpoint("http://localhost:9002");
        node2.setAccessKey("access2");
        node2.setSecretKey("secret2");

        List<NodeConfig> nodes = new ArrayList<>();
        nodes.add(node1);
        nodes.add(node2);

        // 设置节点
        properties.setNodes(nodes);

        // 验证
        assertNotNull(properties.getNodes());
        assertEquals(2, properties.getNodes().size());
        assertEquals("node1", properties.getNodes().get(0).getName());
        assertEquals("node2", properties.getNodes().get(1).getName());
    }

    @Test
    void testSetAndGetLogicalMapping() {
        LogicNodeMapping mapping1 = new LogicNodeMapping();
        mapping1.setLogicNodeName("logic1");
        mapping1.setPhysicalNodePair(Arrays.asList("node1", "node2"));

        LogicNodeMapping mapping2 = new LogicNodeMapping();
        mapping2.setLogicNodeName("logic2");
        mapping2.setPhysicalNodePair(Arrays.asList("node3", "node4"));

        List<LogicNodeMapping> mappings = Arrays.asList(mapping1, mapping2);
        properties.setLogicalMapping(mappings);

        assertNotNull(properties.getLogicalMapping());
        assertEquals(2, properties.getLogicalMapping().size());
        assertEquals("logic1", properties.getLogicalMapping().get(0).getLogicNodeName());
        assertEquals("logic2", properties.getLogicalMapping().get(1).getLogicNodeName());
    }

    @Test
    void testEmptyNodesList() {
        properties.setNodes(new ArrayList<>());

        assertNotNull(properties.getNodes());
        assertTrue(properties.getNodes().isEmpty());
    }

    @Test
    void testNullNodesList() {
        properties.setNodes(null);

        assertNull(properties.getNodes());
    }

    @Test
    void testModifyNodesList() {
        // 创建初始节点列表
        List<NodeConfig> nodes = new ArrayList<>();
        NodeConfig node1 = new NodeConfig();
        node1.setName("node1");
        nodes.add(node1);

        properties.setNodes(nodes);
        assertEquals(1, properties.getNodes().size());

        // 修改列表
        NodeConfig node2 = new NodeConfig();
        node2.setName("node2");
        properties.getNodes().add(node2);

        // 验证修改生效
        assertEquals(2, properties.getNodes().size());
    }

    @Test
    void testNodeConfigProperties() {
        NodeConfig node = new NodeConfig();

        // 测试所有属性
        node.setName("test-node");
        node.setEndpoint("http://localhost:9000");
        node.setAccessKey("minioadmin");
        node.setSecretKey("minioadmin");

        assertEquals("test-node", node.getName());
        assertEquals("http://localhost:9000", node.getEndpoint());
        assertEquals("minioadmin", node.getAccessKey());
        assertEquals("minioadmin", node.getSecretKey());
    }

    @Test
    void testNodeConfigEquals() {
        NodeConfig node1 = new NodeConfig();
        node1.setName("node");
        node1.setEndpoint("http://localhost:9000");
        node1.setAccessKey("access");
        node1.setSecretKey("secret");

        NodeConfig node2 = new NodeConfig();
        node2.setName("node");
        node2.setEndpoint("http://localhost:9000");
        node2.setAccessKey("access");
        node2.setSecretKey("secret");

        // 相同配置应该相等
        assertEquals(node1, node2);
        assertEquals(node1.hashCode(), node2.hashCode());

        // 修改任何属性后应该不相等
        node2.setName("different");
        assertNotEquals(node1, node2);

        // 重置并测试端点不同
        node2.setName("node");
        node2.setEndpoint("http://localhost:9001");
        assertNotEquals(node1, node2);
    }

    @Test
    void testNodeConfigWithNullValues() {
        NodeConfig node = new NodeConfig();

        // 所有属性初始应为null
        assertNull(node.getName());
        assertNull(node.getEndpoint());
        assertNull(node.getAccessKey());
        assertNull(node.getSecretKey());
    }

    @Test
    void testNodeConfigWithEmptyStrings() {
        NodeConfig node = new NodeConfig();

        node.setName("");
        node.setEndpoint("");
        node.setAccessKey("");
        node.setSecretKey("");

        assertEquals("", node.getName());
        assertEquals("", node.getEndpoint());
        assertEquals("", node.getAccessKey());
        assertEquals("", node.getSecretKey());
    }

    @Test
    void testNodeConfigWithSpecialCharacters() {
        NodeConfig node = new NodeConfig();

        String specialName = "node-测试_123!@#";
        String specialEndpoint = "http://端点.example.com:9000/path?query=1";
        String specialAccessKey = "access+key/with=special";
        String specialSecretKey = "secret@key#with$special%chars";

        node.setName(specialName);
        node.setEndpoint(specialEndpoint);
        node.setAccessKey(specialAccessKey);
        node.setSecretKey(specialSecretKey);

        assertEquals(specialName, node.getName());
        assertEquals(specialEndpoint, node.getEndpoint());
        assertEquals(specialAccessKey, node.getAccessKey());
        assertEquals(specialSecretKey, node.getSecretKey());
    }

    @Test
    void testMultipleNodesWithSameName() {
        // 测试多个节点有相同名称的情况
        NodeConfig node1 = new NodeConfig();
        node1.setName("duplicate");
        node1.setEndpoint("http://localhost:9001");

        NodeConfig node2 = new NodeConfig();
        node2.setName("duplicate");
        node2.setEndpoint("http://localhost:9002");

        List<NodeConfig> nodes = new ArrayList<>();
        nodes.add(node1);
        nodes.add(node2);

        properties.setNodes(nodes);

        // 验证可以设置重复名称的节点（业务逻辑应该在其他地方处理）
        assertEquals(2, properties.getNodes().size());
        assertEquals("duplicate", properties.getNodes().get(0).getName());
        assertEquals("duplicate", properties.getNodes().get(1).getName());
    }
}
