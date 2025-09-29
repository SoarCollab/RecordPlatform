package cn.flying.minio.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LogicNodeMapping 测试类
 * 测试逻辑节点映射配置的各种场景
 */
class LogicNodeMappingTest {

    private LogicNodeMapping mapping;

    @BeforeEach
    void setUp() {
        mapping = new LogicNodeMapping();
    }

    @Test
    void testDefaultValues() {
        // 验证默认值
        assertNull(mapping.getLogicNodeName());
        assertNull(mapping.getPhysicalNodePair());
    }

    @Test
    void testSetAndGetLogicNodeName() {
        String nodeName = "logic-node-1";
        mapping.setLogicNodeName(nodeName);

        assertEquals(nodeName, mapping.getLogicNodeName());
    }

    @Test
    void testSetAndGetPhysicalNodePair() {
        List<String> nodes = Arrays.asList("physical-1", "physical-2", "physical-3");
        mapping.setPhysicalNodePair(nodes);

        assertNotNull(mapping.getPhysicalNodePair());
        assertEquals(3, mapping.getPhysicalNodePair().size());
        assertEquals("physical-1", mapping.getPhysicalNodePair().get(0));
        assertEquals("physical-2", mapping.getPhysicalNodePair().get(1));
        assertEquals("physical-3", mapping.getPhysicalNodePair().get(2));
    }

    @Test
    void testEmptyPhysicalNodePair() {
        mapping.setPhysicalNodePair(new ArrayList<>());

        assertNotNull(mapping.getPhysicalNodePair());
        assertTrue(mapping.getPhysicalNodePair().isEmpty());
    }

    @Test
    void testNullPhysicalNodePair() {
        mapping.setPhysicalNodePair(null);

        assertNull(mapping.getPhysicalNodePair());
    }

    @Test
    void testSinglePhysicalNode() {
        List<String> singleNode = Arrays.asList("single-physical-node");
        mapping.setPhysicalNodePair(singleNode);

        assertNotNull(mapping.getPhysicalNodePair());
        assertEquals(1, mapping.getPhysicalNodePair().size());
        assertEquals("single-physical-node", mapping.getPhysicalNodePair().getFirst());
    }

    @Test
    void testModifyPhysicalNodePair() {
        // 创建初始列表
        List<String> nodes = new ArrayList<>();
        nodes.add("node-1");
        mapping.setPhysicalNodePair(nodes);

        assertEquals(1, mapping.getPhysicalNodePair().size());

        // 修改列表
        mapping.getPhysicalNodePair().add("node-2");

        // 验证修改生效
        assertEquals(2, mapping.getPhysicalNodePair().size());
        assertEquals("node-2", mapping.getPhysicalNodePair().get(1));
    }

    @Test
    void testLogicNodeNameWithSpecialCharacters() {
        String specialName = "logic-节点_123!@#$%^&*()";
        mapping.setLogicNodeName(specialName);

        assertEquals(specialName, mapping.getLogicNodeName());
    }

    @Test
    void testLogicNodeNameWithEmptyString() {
        mapping.setLogicNodeName("");

        assertEquals("", mapping.getLogicNodeName());
    }

    @Test
    void testPhysicalNodePairWithDuplicates() {
        // 测试包含重复节点的情况
        List<String> nodesWithDuplicates = Arrays.asList(
                "node-1", "node-2", "node-1", "node-3", "node-2"
        );
        mapping.setPhysicalNodePair(nodesWithDuplicates);

        assertNotNull(mapping.getPhysicalNodePair());
        assertEquals(5, mapping.getPhysicalNodePair().size());

        // 验证保留了所有重复项
        assertEquals("node-1", mapping.getPhysicalNodePair().get(0));
        assertEquals("node-2", mapping.getPhysicalNodePair().get(1));
        assertEquals("node-1", mapping.getPhysicalNodePair().get(2));
        assertEquals("node-3", mapping.getPhysicalNodePair().get(3));
        assertEquals("node-2", mapping.getPhysicalNodePair().get(4));
    }

    @Test
    void testPhysicalNodePairWithNullElements() {
        // 测试包含null元素的列表
        List<String> nodesWithNull = Arrays.asList("node-1", null, "node-2", null);
        mapping.setPhysicalNodePair(nodesWithNull);

        assertNotNull(mapping.getPhysicalNodePair());
        assertEquals(4, mapping.getPhysicalNodePair().size());
        assertEquals("node-1", mapping.getPhysicalNodePair().get(0));
        assertNull(mapping.getPhysicalNodePair().get(1));
        assertEquals("node-2", mapping.getPhysicalNodePair().get(2));
        assertNull(mapping.getPhysicalNodePair().get(3));
    }

    @Test
    void testCompleteMapping() {
        // 测试完整的映射配置
        mapping.setLogicNodeName("production-logic-cluster");
        mapping.setPhysicalNodePair(Arrays.asList(
                "us-east-1-node",
                "us-west-2-node",
                "eu-central-1-node",
                "ap-southeast-1-node"
        ));

        assertEquals("production-logic-cluster", mapping.getLogicNodeName());
        assertEquals(4, mapping.getPhysicalNodePair().size());

        // 验证所有物理节点都正确设置
        assertTrue(mapping.getPhysicalNodePair().contains("us-east-1-node"));
        assertTrue(mapping.getPhysicalNodePair().contains("us-west-2-node"));
        assertTrue(mapping.getPhysicalNodePair().contains("eu-central-1-node"));
        assertTrue(mapping.getPhysicalNodePair().contains("ap-southeast-1-node"));
    }

    @Test
    void testUpdateLogicNodeName() {
        // 测试更新逻辑节点名称
        mapping.setLogicNodeName("old-name");
        assertEquals("old-name", mapping.getLogicNodeName());

        mapping.setLogicNodeName("new-name");
        assertEquals("new-name", mapping.getLogicNodeName());
    }

    @Test
    void testReplacePhysicalNodePair() {
        // 测试替换整个物理节点列表
        List<String> initialNodes = Arrays.asList("node-a", "node-b");
        mapping.setPhysicalNodePair(initialNodes);
        assertEquals(2, mapping.getPhysicalNodePair().size());

        List<String> newNodes = Arrays.asList("node-x", "node-y", "node-z");
        mapping.setPhysicalNodePair(newNodes);

        assertEquals(3, mapping.getPhysicalNodePair().size());
        assertEquals("node-x", mapping.getPhysicalNodePair().get(0));
        assertEquals("node-y", mapping.getPhysicalNodePair().get(1));
        assertEquals("node-z", mapping.getPhysicalNodePair().get(2));
    }

    @Test
    void testLargePhysicalNodePair() {
        // 测试大量物理节点
        List<String> largeNodeList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            largeNodeList.add("node-" + i);
        }

        mapping.setPhysicalNodePair(largeNodeList);

        assertEquals(100, mapping.getPhysicalNodePair().size());
        assertEquals("node-0", mapping.getPhysicalNodePair().getFirst());
        assertEquals("node-99", mapping.getPhysicalNodePair().get(99));
    }
}
