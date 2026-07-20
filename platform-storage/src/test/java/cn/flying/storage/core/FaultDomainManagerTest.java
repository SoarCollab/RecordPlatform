package cn.flying.storage.core;

import cn.flying.storage.config.FaultDomainConfig;
import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FaultDomainManager Unit Tests")
class FaultDomainManagerTest {

    @Mock
    private StorageProperties storageProperties;

    @Mock
    private S3Monitor s3Monitor;

    @InjectMocks
    private FaultDomainManager manager;

    @BeforeEach
    void setUp() {
        when(storageProperties.getVirtualNodesPerNode()).thenReturn(150);
    }

    private NodeConfig createNode(String name, String domain, Boolean enabled, Integer weight) {
        NodeConfig node = new NodeConfig();
        node.setName(name);
        node.setFaultDomain(domain);
        node.setEnabled(enabled);
        node.setWeight(weight);
        node.setPhysicalStorageId("physical-" + name);
        return node;
    }

    private FaultDomainConfig createDomainConfig(String name, Boolean acceptsWrites, Integer minNodes) {
        FaultDomainConfig config = new FaultDomainConfig();
        config.setName(name);
        config.setAcceptsWrites(acceptsWrites);
        config.setMinNodes(minNodes);
        return config;
    }

    @Nested
    @DisplayName("Ring Rebuild Tests")
    class RebuildRingsTests {

        @Test
        @DisplayName("Should build rings for multiple domains")
        void shouldBuildRingsForMultipleDomains() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-a2", "domain-A", true, 100),
                    createNode("node-b1", "domain-B", true, 100)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());

            manager.rebuildRings();

            assertThat(manager.getNodesInDomain("domain-A")).containsExactlyInAnyOrder("node-a1", "node-a2");
            assertThat(manager.getNodesInDomain("domain-B")).containsExactly("node-b1");
        }

        @Test
        @DisplayName("Should skip disabled nodes")
        void shouldSkipDisabledNodes() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNode("node-active", "domain-A", true, 100),
                    createNode("node-disabled", "domain-A", false, 100)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());

            manager.rebuildRings();

            Set<String> domainNodes = manager.getNodesInDomain("domain-A");
            assertThat(domainNodes).containsExactly("node-active");
            assertThat(domainNodes).doesNotContain("node-disabled");
        }

        @Test
        @DisplayName("Should skip nodes without fault domain")
        void shouldSkipNodesWithoutFaultDomain() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNode("node-with-domain", "domain-A", true, 100),
                    createNode("node-no-domain", null, true, 100),
                    createNode("node-blank-domain", "", true, 100)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());

            manager.rebuildRings();

            assertThat(manager.getNodesInDomain("domain-A")).containsExactly("node-with-domain");
        }

        @Test
        @DisplayName("Should handle empty node list")
        void shouldHandleEmptyNodeList() {
            when(storageProperties.getNodes()).thenReturn(Collections.emptyList());
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());

            manager.rebuildRings();

            assertThat(manager.getNodesInDomain("any-domain")).isEmpty();
        }

        @Test
        @DisplayName("Should cache domain configurations")
        void shouldCacheDomainConfigurations() {
            List<FaultDomainConfig> domains = Arrays.asList(
                    createDomainConfig("domain-A", true, 2),
                    createDomainConfig("domain-B", true, 1)
            );
            when(storageProperties.getNodes()).thenReturn(Collections.emptyList());
            when(storageProperties.getDomains()).thenReturn(domains);

            manager.rebuildRings();

            assertThat(manager.getDomainConfig("domain-A")).isNotNull();
            assertThat(manager.getDomainConfig("domain-A").getMinNodes()).isEqualTo(2);
            assertThat(manager.getDomainConfig("domain-B")).isNotNull();
        }
    }

    @Nested
    @DisplayName("Target Node Selection Tests")
    class TargetNodeSelectionTests {

        @BeforeEach
        void setupNodes() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-b1", "domain-B", true, 100)
            );
            List<FaultDomainConfig> domains = Arrays.asList(
                    createDomainConfig("domain-A", true, 1),
                    createDomainConfig("domain-B", true, 1)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(domains);
            when(storageProperties.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            
            manager.rebuildRings();
        }

        @Test
        @DisplayName("Should return target nodes from multiple domains")
        void shouldReturnTargetNodesFromMultipleDomains() {
            List<String> targets = manager.getTargetNodes("test-chunk-hash");

            assertThat(targets).hasSize(2);
            assertThat(targets).containsAnyOf("node-a1", "node-b1");
        }

        /**
         * 验证 Nacos 刷新把两个计划目标漂移到同一物理集群后，整份 placement 快照失败关闭。
         */
        @Test
        @DisplayName("Duplicate physical storage after topology refresh should invalidate every planned target")
        void shouldFailClosedAfterPhysicalStorageIdentityDrift() {
            NodeConfig nodeA = createNode("node-a1", "domain-A", true, 100);
            NodeConfig nodeB = createNode("node-b1", "domain-B", true, 100);
            when(storageProperties.getNodes()).thenReturn(List.of(nodeA, nodeB));
            manager.rebuildRings();

            Map<String, String> healthySnapshot = manager.getPlannedTargetsSnapshot("physical-drift");
            assertThat(healthySnapshot)
                    .containsEntry("domain-A", "node-a1")
                    .containsEntry("domain-B", "node-b1");
            assertThat(manager.areNodesOnIndependentPhysicalStorage("node-a1", "node-b1"))
                    .isTrue();

            nodeB.setPhysicalStorageId(nodeA.getPhysicalStorageId());
            manager.rebuildRings();

            Map<String, String> driftedSnapshot = manager.getPlannedTargetsSnapshot("physical-drift");
            assertThat(driftedSnapshot).containsOnlyKeys("domain-A", "domain-B");
            assertThat(driftedSnapshot.values()).containsOnlyNulls();
            assertThat(manager.getTargetNodeInDomain("physical-drift", "domain-A")).isNull();
            assertThat(manager.getTargetNodeInDomain("physical-drift", "domain-B")).isNull();
            assertThat(manager.areNodesOnIndependentPhysicalStorage("node-a1", "node-b1"))
                    .isFalse();
        }

        @Test
        @DisplayName("Should skip offline nodes and use fallback")
        void shouldSkipOfflineNodesAndUseFallback() {
            when(s3Monitor.isNodeOnline("node-a1")).thenReturn(false);

            List<String> targets = manager.getTargetNodes("test-chunk-hash");

            assertThat(targets).hasSize(1);
            assertThat(targets).contains("node-b1");
        }

        @Test
        @DisplayName("Should respect replication factor limit")
        void shouldRespectReplicationFactorLimit() {
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);

            List<String> targets = manager.getTargetNodes("test-chunk-hash");

            assertThat(targets).hasSize(1);
        }

        @Test
        @DisplayName("Should plan factor two from three active domains while excluding read-only domains")
        void shouldPlanWritableDomainsIndependentOfHealth() {
            when(storageProperties.getNodes()).thenReturn(Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-b1", "domain-B", true, 100),
                    createNode("node-c1", "domain-C", true, 100)
            ));
            when(storageProperties.getDomains()).thenReturn(Arrays.asList(
                    createDomainConfig("domain-A", true, 1),
                    createDomainConfig("domain-B", false, 1),
                    createDomainConfig("domain-C", true, 1)
            ));
            when(storageProperties.getActiveDomains())
                    .thenReturn(Arrays.asList("domain-A", "domain-B", "domain-C"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(false);
            manager.rebuildRings();

            assertThat(manager.getPlannedTargetDomains("test-chunk-hash"))
                    .containsExactly("domain-A", "domain-C");
        }

        @Test
        @DisplayName("Should not replace an offline hash target with another node in the same domain")
        void shouldNotFallbackForExactDomainPlacement() {
            when(storageProperties.getNodes()).thenReturn(Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-a2", "domain-A", true, 100)
            ));
            when(storageProperties.getDomains()).thenReturn(List.of(
                    createDomainConfig("domain-A", true, 1)
            ));
            when(storageProperties.getActiveDomains()).thenReturn(List.of("domain-A"));
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            manager.rebuildRings();

            String exactTarget = manager.getPlannedTargetNodeInDomain("test-chunk-hash", "domain-A");
            assertThat(exactTarget).isNotNull();
            assertThat(manager.getTargetNodeInDomain("test-chunk-hash", "domain-A"))
                    .isEqualTo(exactTarget);
            when(s3Monitor.isNodeOnline(exactTarget)).thenReturn(false);

            assertThat(manager.getPlannedTargetNodeInDomain("test-chunk-hash", "domain-A"))
                    .isEqualTo(exactTarget);
            assertThat(manager.getTargetNodeInDomain("test-chunk-hash", "domain-A")).isNull();
        }

        @Test
        @DisplayName("Fallback write target should remain distinct from the exact planned target")
        void shouldKeepFallbackDistinctFromExactPlannedTarget() {
            when(storageProperties.getNodes()).thenReturn(Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-a2", "domain-A", true, 100)
            ));
            when(storageProperties.getDomains()).thenReturn(List.of(
                    createDomainConfig("domain-A", true, 1)
            ));
            when(storageProperties.getActiveDomains()).thenReturn(List.of("domain-A"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            manager.rebuildRings();

            String objectHash = "fallback-placement-hash";
            String plannedTarget = manager.getPlannedTargetNodeInDomain(objectHash, "domain-A");
            String fallbackNode = "node-a1".equals(plannedTarget) ? "node-a2" : "node-a1";
            when(s3Monitor.isNodeOnline(plannedTarget)).thenReturn(false);
            when(s3Monitor.isNodeOnline(fallbackNode)).thenReturn(true);

            assertThat(manager.getTargetNodes(objectHash)).containsExactly(fallbackNode);
            assertThat(manager.getPlannedTargetNodeInDomain(objectHash, "domain-A"))
                    .isEqualTo(plannedTarget)
                    .isNotEqualTo(fallbackNode);
        }

        @Test
        @DisplayName("Should expose the new exact target after a same-domain ring change")
        void shouldExposeSameDomainRingTargetChangeIndependentOfHealth() {
            when(storageProperties.getNodes()).thenReturn(List.of(
                    createNode("node-a1", "domain-A", true, 100)
            ));
            when(storageProperties.getDomains()).thenReturn(List.of(
                    createDomainConfig("domain-A", true, 1)
            ));
            when(storageProperties.getActiveDomains()).thenReturn(List.of("domain-A"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(1);
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(false);
            manager.rebuildRings();

            when(storageProperties.getNodes()).thenReturn(List.of(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-a2", "domain-A", true, 100)
            ));
            manager.rebuildRings();

            String movedHash = null;
            for (int index = 0; index < 10_000; index++) {
                String candidate = "same-domain-move-" + index;
                if ("node-a2".equals(manager.getPlannedTargetNodeInDomain(candidate, "domain-A"))) {
                    movedHash = candidate;
                    break;
                }
            }

            assertThat(movedHash).isNotNull();
            assertThat(manager.getPlannedTargetNodeInDomain(movedHash, "domain-A"))
                    .isEqualTo("node-a2");
            assertThat(manager.getTargetNodeInDomain(movedHash, "domain-A")).isNull();
        }
    }

    @Nested
    @DisplayName("Read Node Selection Tests")
    class ReadNodeSelectionTests {

        @Test
        @DisplayName("Should select node with lowest load score")
        void shouldSelectNodeWithLowestLoadScore() {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(true);
            when(s3Monitor.getNodeLoadScore("node1")).thenReturn(0.8);
            when(s3Monitor.getNodeLoadScore("node2")).thenReturn(0.3);
            when(s3Monitor.getNodeLoadScore("node3")).thenReturn(0.5);

            List<String> candidates = Arrays.asList("node1", "node2", "node3");
            String selected = manager.selectBestNodeForRead(candidates);

            assertThat(selected).isEqualTo("node2");
        }

        @Test
        @DisplayName("Should skip offline nodes when selecting best node")
        void shouldSkipOfflineNodesWhenSelectingBestNode() {
            when(s3Monitor.isNodeOnline("node1")).thenReturn(false);
            when(s3Monitor.isNodeOnline("node2")).thenReturn(true);
            when(s3Monitor.getNodeLoadScore("node2")).thenReturn(0.5);

            List<String> candidates = Arrays.asList("node1", "node2");
            String selected = manager.selectBestNodeForRead(candidates);

            assertThat(selected).isEqualTo("node2");
        }

        @Test
        @DisplayName("Should return null when no nodes are available")
        void shouldReturnNullWhenNoNodesAvailable() {
            when(s3Monitor.isNodeOnline(anyString())).thenReturn(false);

            List<String> candidates = Arrays.asList("node1", "node2");
            String selected = manager.selectBestNodeForRead(candidates);

            assertThat(selected).isNull();
        }

        @Test
        @DisplayName("Should handle empty candidate list")
        void shouldHandleEmptyCandidateList() {
            String selected = manager.selectBestNodeForRead(Collections.emptyList());

            assertThat(selected).isNull();
        }

        @Test
        @DisplayName("Should handle null candidate list")
        void shouldHandleNullCandidateList() {
            String selected = manager.selectBestNodeForRead(null);

            assertThat(selected).isNull();
        }
    }

    @Nested
    @DisplayName("Standby Node Tests")
    class StandbyNodeTests {

        @BeforeEach
        void setupStandbyNodes() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("standby-1", "STANDBY", true, 100),
                    createNode("standby-2", "STANDBY", true, 100)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());
            when(storageProperties.getStandbyDomain()).thenReturn("STANDBY");
            
            manager.rebuildRings();
        }

        @Test
        @DisplayName("Should return all standby nodes")
        void shouldReturnAllStandbyNodes() {
            List<String> standbyNodes = manager.getStandbyNodes();

            assertThat(standbyNodes).containsExactlyInAnyOrder("standby-1", "standby-2");
        }

        @Test
        @DisplayName("Should return only healthy standby nodes")
        void shouldReturnOnlyHealthyStandbyNodes() {
            when(s3Monitor.isNodeOnline("standby-1")).thenReturn(true);
            when(s3Monitor.isNodeOnline("standby-2")).thenReturn(false);

            List<String> healthyStandby = manager.getHealthyStandbyNodes();

            assertThat(healthyStandby).containsExactly("standby-1");
        }

        @Test
        @DisplayName("Should return empty list when no standby domain configured")
        void shouldReturnEmptyListWhenNoStandbyDomainConfigured() {
            when(storageProperties.getStandbyDomain()).thenReturn(null);

            List<String> standbyNodes = manager.getStandbyNodes();

            assertThat(standbyNodes).isEmpty();
        }
    }

    @Nested
    @DisplayName("Domain Query Tests")
    class DomainQueryTests {

        @BeforeEach
        void setupDomains() {
            List<NodeConfig> nodes = Arrays.asList(
                    createNode("node-a1", "domain-A", true, 100),
                    createNode("node-a2", "domain-A", true, 100),
                    createNode("node-b1", "domain-B", true, 100)
            );
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());
            when(storageProperties.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B"));
            
            manager.rebuildRings();
        }

        @Test
        @DisplayName("Should find healthy node in domain")
        void shouldFindHealthyNodeInDomain() {
            when(s3Monitor.isNodeOnline("node-a1")).thenReturn(true);

            String healthyNode = manager.findHealthyNodeInDomain("domain-A");

            assertThat(healthyNode).isEqualTo("node-a1");
        }

        @Test
        @DisplayName("Should count healthy nodes in domain")
        void shouldCountHealthyNodesInDomain() {
            when(s3Monitor.isNodeOnline("node-a1")).thenReturn(true);
            when(s3Monitor.isNodeOnline("node-a2")).thenReturn(false);

            int count = manager.countHealthyNodesInDomain("domain-A");

            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("Should return zero for empty domain")
        void shouldReturnZeroForEmptyDomain() {
            int count = manager.countHealthyNodesInDomain("non-existent-domain");

            assertThat(count).isZero();
        }

        @Test
        @DisplayName("Should get node domain")
        void shouldGetNodeDomain() {
            String domain = manager.getNodeDomain("node-a1");

            assertThat(domain).isEqualTo("domain-A");
        }

        @Test
        @DisplayName("Should return null for unknown node")
        void shouldReturnNullForUnknownNode() {
            String domain = manager.getNodeDomain("unknown-node");

            assertThat(domain).isNull();
        }

        @Test
        @DisplayName("Should check if using fault domains")
        void shouldCheckIfUsingFaultDomains() {
            boolean usingFaultDomains = manager.isUsingFaultDomains();

            assertThat(usingFaultDomains).isTrue();
        }
    }

    @Nested
    @DisplayName("Node Domain Change Tests")
    class NodeDomainChangeTests {

        @BeforeEach
        void setupDomains() {
            List<NodeConfig> nodes = new ArrayList<>(Arrays.asList(
                    createNode("node-to-move", "STANDBY", true, 100),
                    createNode("node-a1", "domain-A", true, 100)
            ));
            when(storageProperties.getNodes()).thenReturn(nodes);
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());
            
            manager.rebuildRings();
        }

        @Test
        @DisplayName("Should change node domain successfully")
        void shouldChangeNodeDomainSuccessfully() {
            boolean result = manager.changeNodeDomain("node-to-move", "domain-A");

            assertThat(result).isTrue();
            assertThat(manager.getNodesInDomain("domain-A")).contains("node-to-move");
            assertThat(manager.getNodesInDomain("STANDBY")).doesNotContain("node-to-move");
        }

        @Test
        @DisplayName("Should return false for unknown node")
        void shouldReturnFalseForUnknownNode() {
            boolean result = manager.changeNodeDomain("unknown-node", "domain-A");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return true if node already in target domain")
        void shouldReturnTrueIfNodeAlreadyInTargetDomain() {
            boolean result = manager.changeNodeDomain("node-a1", "domain-A");

            assertThat(result).isTrue();
        }

        /**
         * 验证节点迁移不会修改已发布 ring，且 planned-target 读取只能观察迁移前或迁移后的完整拓扑。
         */
        @Test
        @DisplayName("Should publish one immutable planned-target topology during concurrent domain move")
        void shouldPublishConsistentSnapshotDuringConcurrentDomainMove() throws Exception {
            NodeConfig movingNode = createNode("node-to-move", "domain-A", true, 100);
            when(storageProperties.getNodes()).thenReturn(List.of(movingNode));
            when(storageProperties.getDomains()).thenReturn(List.of(
                    createDomainConfig("domain-A", true, 1),
                    createDomainConfig("domain-B", true, 1)
            ));
            when(storageProperties.getActiveDomains()).thenReturn(List.of("domain-A", "domain-B"));
            when(storageProperties.getEffectiveReplicationFactor()).thenReturn(2);
            manager.rebuildRings();

            Map<String, String> beforeMove = manager.getPlannedTargetsSnapshot("moving-object");
            assertThat(beforeMove.get("domain-A")).isEqualTo("node-to-move");
            assertThat(beforeMove).containsKey("domain-B");
            assertThat(beforeMove.get("domain-B")).isNull();
            assertThat(beforeMove.values().stream().filter("node-to-move"::equals)).hasSize(1);

            CountDownLatch ringRebuildEntered = new CountDownLatch(1);
            CountDownLatch releaseRingRebuild = new CountDownLatch(1);
            AtomicBoolean blockNextRingBuild = new AtomicBoolean(true);
            when(storageProperties.getVirtualNodesPerNode()).thenAnswer(invocation -> {
                if (blockNextRingBuild.compareAndSet(true, false)) {
                    ringRebuildEntered.countDown();
                    if (!releaseRingRebuild.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to release ring rebuild");
                    }
                }
                return 150;
            });

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<Boolean> moveFuture = executor.submit(
                        () -> manager.changeNodeDomain("node-to-move", "domain-B")
                );
                assertThat(ringRebuildEntered.await(5, TimeUnit.SECONDS)).isTrue();

                // 迁移尚未发布时，无锁 reader 仍必须看到完整旧 ring。
                assertThat(manager.getPlannedTargetNodeInDomain("moving-object", "domain-A"))
                        .isEqualTo("node-to-move");

                CountDownLatch snapshotStarted = new CountDownLatch(1);
                Future<Map<String, String>> snapshotFuture = executor.submit(() -> {
                    snapshotStarted.countDown();
                    return manager.getPlannedTargetsSnapshot("moving-object");
                });
                assertThat(snapshotStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(snapshotFuture).isNotDone();

                releaseRingRebuild.countDown();
                assertThat(moveFuture.get(5, TimeUnit.SECONDS)).isTrue();
                Map<String, String> afterMove = snapshotFuture.get(5, TimeUnit.SECONDS);

                assertThat(afterMove).containsKey("domain-A");
                assertThat(afterMove.get("domain-A")).isNull();
                assertThat(afterMove.get("domain-B")).isEqualTo("node-to-move");
                assertThat(afterMove.values().stream().filter("node-to-move"::equals)).hasSize(1);
            } finally {
                releaseRingRebuild.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Writable Domains Tests")
    class WritableDomainsTests {

        @Test
        @DisplayName("Should return only writable domains")
        void shouldReturnOnlyWritableDomains() {
            List<FaultDomainConfig> domains = Arrays.asList(
                    createDomainConfig("domain-A", true, 1),
                    createDomainConfig("domain-B", false, 1),
                    createDomainConfig("domain-C", true, 1)
            );
            when(storageProperties.getNodes()).thenReturn(Collections.emptyList());
            when(storageProperties.getDomains()).thenReturn(domains);
            when(storageProperties.getActiveDomains()).thenReturn(Arrays.asList("domain-A", "domain-B", "domain-C"));

            manager.rebuildRings();
            List<FaultDomainConfig> writable = manager.getWritableDomains();

            assertThat(writable).hasSize(2);
            assertThat(writable.stream().map(FaultDomainConfig::getName))
                    .containsExactly("domain-A", "domain-C");
        }

        @Test
        @DisplayName("Should create default config for unconfigured active domains")
        void shouldCreateDefaultConfigForUnconfiguredActiveDomains() {
            when(storageProperties.getNodes()).thenReturn(Collections.emptyList());
            when(storageProperties.getDomains()).thenReturn(Collections.emptyList());
            when(storageProperties.getActiveDomains()).thenReturn(Arrays.asList("unconfigured-domain"));

            manager.rebuildRings();
            List<FaultDomainConfig> writable = manager.getWritableDomains();

            assertThat(writable).hasSize(1);
            assertThat(writable.get(0).getName()).isEqualTo("unconfigured-domain");
            assertThat(writable.get(0).getAcceptsWrites()).isTrue();
        }
    }

    @Nested
    @DisplayName("Single Domain Mode Tests")
    class SingleDomainModeTests {

        @Test
        @DisplayName("Should detect single domain mode")
        void shouldDetectSingleDomainMode() {
            when(storageProperties.isSingleDomainMode()).thenReturn(true);

            assertThat(manager.isSingleDomainMode()).isTrue();
        }

        @Test
        @DisplayName("Should detect multi domain mode")
        void shouldDetectMultiDomainMode() {
            when(storageProperties.isSingleDomainMode()).thenReturn(false);

            assertThat(manager.isSingleDomainMode()).isFalse();
        }
    }
}
