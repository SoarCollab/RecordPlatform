package cn.flying.storage.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ConsistentHashRing Tests")
class ConsistentHashRingTest {

    private ConsistentHashRing hashRing;
    
    @BeforeEach
    void setUp() {
        hashRing = new ConsistentHashRing(100);
    }
    
    @AfterEach
    void tearDown() {
        hashRing.clear();
    }

    @Nested
    @DisplayName("Basic Operations")
    class BasicOperations {
        
        @Test
        @DisplayName("Should add node successfully")
        void addNode_shouldAddNodeSuccessfully() {
            hashRing.addNode("node1", 100);
            
            assertThat(hashRing.size()).isEqualTo(1);
            assertThat(hashRing.getAllNodes()).contains("node1");
        }
        
        @Test
        @DisplayName("Should remove node successfully")
        void removeNode_shouldRemoveNodeSuccessfully() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            
            hashRing.removeNode("node1");
            
            assertThat(hashRing.size()).isEqualTo(1);
            assertThat(hashRing.getAllNodes()).containsExactly("node2");
        }
        
        @Test
        @DisplayName("Should handle removing non-existent node")
        void removeNode_shouldHandleNonExistentNode() {
            hashRing.addNode("node1", 100);
            
            assertThatCode(() -> hashRing.removeNode("non-existent"))
                    .doesNotThrowAnyException();
            
            assertThat(hashRing.size()).isEqualTo(1);
        }
        
        @Test
        @DisplayName("Should handle null node removal")
        void removeNode_shouldHandleNullNode() {
            hashRing.addNode("node1", 100);
            
            assertThatCode(() -> hashRing.removeNode(null))
                    .doesNotThrowAnyException();
        }
        
        @Test
        @DisplayName("Should ignore blank node name on add")
        void addNode_shouldIgnoreBlankNodeName() {
            hashRing.addNode("", 100);
            hashRing.addNode("   ", 100);
            
            assertThat(hashRing.isEmpty()).isTrue();
        }
        
        @Test
        @DisplayName("Should clear all nodes")
        void clear_shouldRemoveAllNodes() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            hashRing.clear();
            
            assertThat(hashRing.isEmpty()).isTrue();
            assertThat(hashRing.size()).isZero();
        }
    }

    @Nested
    @DisplayName("Node Lookup")
    class NodeLookup {
        
        @Test
        @DisplayName("Should return null for empty ring")
        void getNode_shouldReturnNullForEmptyRing() {
            assertThat(hashRing.getNode("any-key")).isNull();
        }
        
        @Test
        @DisplayName("Should return single node for single-node ring")
        void getNode_shouldReturnSingleNodeForSingleNodeRing() {
            hashRing.addNode("node1", 100);
            
            assertThat(hashRing.getNode("key1")).isEqualTo("node1");
            assertThat(hashRing.getNode("key2")).isEqualTo("node1");
            assertThat(hashRing.getNode("key3")).isEqualTo("node1");
        }
        
        @Test
        @DisplayName("Should return consistent results for same key")
        void getNode_shouldReturnConsistentResultsForSameKey() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            String key = "test-file-hash";
            String firstResult = hashRing.getNode(key);
            
            for (int i = 0; i < 100; i++) {
                assertThat(hashRing.getNode(key)).isEqualTo(firstResult);
            }
        }
        
        @Test
        @DisplayName("Should return multiple unique nodes with getNodes")
        void getNodes_shouldReturnMultipleUniqueNodes() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            List<String> nodes = hashRing.getNodes("test-key", 3);
            
            assertThat(nodes).hasSize(3);
            assertThat(new HashSet<>(nodes)).hasSize(3);
        }
        
        @Test
        @DisplayName("Should return available nodes when requesting more than exist")
        void getNodes_shouldReturnAvailableNodesWhenRequestingMore() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            
            List<String> nodes = hashRing.getNodes("test-key", 5);
            
            assertThat(nodes).hasSize(2);
        }
        
        @Test
        @DisplayName("Should return empty list for empty ring with getNodes")
        void getNodes_shouldReturnEmptyListForEmptyRing() {
            List<String> nodes = hashRing.getNodes("test-key", 3);
            
            assertThat(nodes).isEmpty();
        }
        
        @Test
        @DisplayName("Should return empty list for zero count with getNodes")
        void getNodes_shouldReturnEmptyListForZeroCount() {
            hashRing.addNode("node1", 100);
            
            List<String> nodes = hashRing.getNodes("test-key", 0);
            
            assertThat(nodes).isEmpty();
        }
    }

    @Nested
    @DisplayName("Distribution Balance")
    class DistributionBalance {
        
        @Test
        @DisplayName("Should distribute keys evenly across nodes")
        void shouldDistributeKeysEvenly() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            Map<String, Integer> distribution = new HashMap<>();
            int totalKeys = 10000;
            
            for (int i = 0; i < totalKeys; i++) {
                String key = "file_" + i;
                String node = hashRing.getNode(key);
                distribution.merge(node, 1, Integer::sum);
            }
            
            int expectedPerNode = totalKeys / 3;
            // Consistent hashing has inherent variance; 25% tolerance is realistic
            int tolerance = (int) (expectedPerNode * 0.25);
            
            distribution.values().forEach(count -> {
                assertThat(count).isBetween(expectedPerNode - tolerance, expectedPerNode + tolerance);
            });
        }
        
        @ParameterizedTest
        @ValueSource(ints = {2, 5, 10, 20})
        @DisplayName("Should distribute evenly with different node counts")
        void shouldDistributeEvenlyWithDifferentNodeCounts(int nodeCount) {
            for (int i = 1; i <= nodeCount; i++) {
                hashRing.addNode("node" + i, 100);
            }
            
            Map<String, Integer> distribution = new HashMap<>();
            int totalKeys = 10000;
            
            for (int i = 0; i < totalKeys; i++) {
                String key = "file_" + i;
                String node = hashRing.getNode(key);
                distribution.merge(node, 1, Integer::sum);
            }
            
            int expectedPerNode = totalKeys / nodeCount;
            // Consistent hashing variance increases with fewer nodes; 30% tolerance is realistic
            int tolerance = (int) (expectedPerNode * 0.30);
            
            distribution.values().forEach(count -> {
                assertThat(count).isBetween(expectedPerNode - tolerance, expectedPerNode + tolerance);
            });
        }
    }

    @Nested
    @DisplayName("Minimal Migration")
    class MinimalMigration {
        
        @Test
        @DisplayName("Should minimize data migration when node is added")
        void shouldMinimizeDataMigrationOnNodeAdd() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            
            Map<String, String> initialMapping = new HashMap<>();
            int totalKeys = 1000;
            for (int i = 0; i < totalKeys; i++) {
                String key = "file_" + i;
                initialMapping.put(key, hashRing.getNode(key));
            }
            
            hashRing.addNode("node3", 100);
            
            int migratedKeys = 0;
            for (String key : initialMapping.keySet()) {
                String newNode = hashRing.getNode(key);
                if (!newNode.equals(initialMapping.get(key))) {
                    migratedKeys++;
                }
            }
            
            int maxExpectedMigration = totalKeys / 3 + (int)(totalKeys * 0.10);
            assertThat(migratedKeys).isLessThan(maxExpectedMigration);
        }
        
        @Test
        @DisplayName("Should minimize data migration when node is removed")
        void shouldMinimizeDataMigrationOnNodeRemove() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            Map<String, String> initialMapping = new HashMap<>();
            int totalKeys = 1000;
            for (int i = 0; i < totalKeys; i++) {
                String key = "file_" + i;
                initialMapping.put(key, hashRing.getNode(key));
            }
            
            hashRing.removeNode("node2");
            
            int migratedKeys = 0;
            for (String key : initialMapping.keySet()) {
                String newNode = hashRing.getNode(key);
                if (!newNode.equals(initialMapping.get(key))) {
                    migratedKeys++;
                }
            }
            
            int maxExpectedMigration = totalKeys / 3 + (int)(totalKeys * 0.10);
            assertThat(migratedKeys).isLessThan(maxExpectedMigration);
        }
    }

    @Nested
    @DisplayName("Node Weights")
    class NodeWeights {
        
        @Test
        @DisplayName("Should allocate more keys to higher weight nodes")
        void shouldAllocateMoreKeysToHigherWeightNodes() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 200);
            
            Map<String, Integer> distribution = new HashMap<>();
            int totalKeys = 10000;
            
            for (int i = 0; i < totalKeys; i++) {
                String key = "file_" + i;
                String node = hashRing.getNode(key);
                distribution.merge(node, 1, Integer::sum);
            }
            
            int node1Count = distribution.getOrDefault("node1", 0);
            int node2Count = distribution.getOrDefault("node2", 0);
            
            assertThat((double) node2Count / node1Count).isBetween(1.5, 2.5);
        }
        
        @Test
        @DisplayName("Should handle node weight update by re-adding")
        void shouldHandleNodeWeightUpdate() {
            hashRing.addNode("node1", 100);
            
            String key = "test-key";
            String initialNode = hashRing.getNode(key);
            
            hashRing.addNode("node1", 200);
            
            assertThat(hashRing.size()).isEqualTo(1);
            assertThat(hashRing.getNode(key)).isEqualTo(initialNode);
        }
    }

    @Nested
    @DisplayName("Thread Safety")
    class ThreadSafety {
        
        @Test
        @DisplayName("Should handle concurrent reads safely")
        void shouldHandleConcurrentReadsSafely() throws Exception {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            int threadCount = 10;
            int operationsPerThread = 1000;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Future<?>> futures = new ArrayList<>();
            
            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    try {
                        for (int i = 0; i < operationsPerThread; i++) {
                            String key = "key_" + Thread.currentThread().getId() + "_" + i;
                            String node = hashRing.getNode(key);
                            assertThat(node).isNotNull();
                        }
                    } finally {
                        latch.countDown();
                    }
                }));
            }
            
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            
            assertThat(completed).isTrue();
            futures.forEach(f -> assertThatCode(f::get).doesNotThrowAnyException());
        }
        
        @Test
        @DisplayName("Should handle concurrent reads and writes safely")
        void shouldHandleConcurrentReadsAndWritesSafely() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            List<Future<?>> futures = new ArrayList<>();
            
            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                futures.add(executor.submit(() -> {
                    try {
                        for (int i = 0; i < 100; i++) {
                            if (i % 3 == 0) {
                                hashRing.addNode("node_" + threadId + "_" + i, 100);
                            } else if (i % 3 == 1) {
                                hashRing.getNode("key_" + i);
                            } else {
                                hashRing.removeNode("node_" + threadId + "_" + (i - 2));
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                }));
            }
            
            boolean completed = latch.await(30, TimeUnit.SECONDS);
            executor.shutdown();
            
            assertThat(completed).isTrue();
            futures.forEach(f -> assertThatCode(f::get).doesNotThrowAnyException());
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {
        
        @Test
        @DisplayName("Should handle wrap-around in hash ring")
        void shouldHandleWrapAroundInHashRing() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            
            Set<String> foundNodes = new HashSet<>();
            for (int i = 0; i < 1000; i++) {
                String key = UUID.randomUUID().toString();
                foundNodes.add(hashRing.getNode(key));
            }
            
            assertThat(foundNodes).hasSize(2);
        }
        
        @Test
        @DisplayName("Should handle very low weight")
        void shouldHandleVeryLowWeight() {
            hashRing.addNode("node1", 1);
            
            assertThat(hashRing.size()).isEqualTo(1);
            assertThat(hashRing.getNode("any-key")).isEqualTo("node1");
        }
        
        @Test
        @DisplayName("Should be deterministic across restarts")
        void shouldBeDeterministicAcrossRestarts() {
            hashRing.addNode("node1", 100);
            hashRing.addNode("node2", 100);
            hashRing.addNode("node3", 100);
            
            Map<String, String> mapping1 = new HashMap<>();
            for (int i = 0; i < 100; i++) {
                String key = "file_" + i;
                mapping1.put(key, hashRing.getNode(key));
            }
            
            ConsistentHashRing newRing = new ConsistentHashRing(100);
            newRing.addNode("node1", 100);
            newRing.addNode("node2", 100);
            newRing.addNode("node3", 100);
            
            for (int i = 0; i < 100; i++) {
                String key = "file_" + i;
                assertThat(newRing.getNode(key)).isEqualTo(mapping1.get(key));
            }
        }
        
        @Test
        @DisplayName("Should handle special characters in key")
        void shouldHandleSpecialCharactersInKey() {
            hashRing.addNode("node1", 100);
            
            assertThatCode(() -> {
                hashRing.getNode("key with spaces");
                hashRing.getNode("key/with/slashes");
                hashRing.getNode("key?with=query&params");
                hashRing.getNode("key中文字符");
                hashRing.getNode("");
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Performance")
    class Performance {
        
        @Test
        @DisplayName("Should perform lookups efficiently")
        void shouldPerformLookupsEfficiently() {
            for (int i = 0; i < 100; i++) {
                hashRing.addNode("node" + i, 100);
            }
            
            long startTime = System.nanoTime();
            int iterations = 100000;
            
            for (int i = 0; i < iterations; i++) {
                hashRing.getNode("key_" + i);
            }
            
            long endTime = System.nanoTime();
            long durationMs = TimeUnit.NANOSECONDS.toMillis(endTime - startTime);
            double opsPerMs = iterations / (double) durationMs;
            
            assertThat(opsPerMs).isGreaterThan(100);
        }
    }
}
