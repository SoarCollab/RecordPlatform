package cn.flying.storage.core;

import cn.flying.storage.config.NodeConfig;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager.TopologyLease;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证 S3 客户端、节点配置和资源生命周期在 topology 刷新时保持同代且可线性化。
 */
@DisplayName("S3ClientManager topology snapshot tests")
class S3ClientManagerTest {

    @Test
    @DisplayName("reload should publish client and config from one revision without closing leased clients")
    void shouldPublishOneImmutableTopologyAndDeferRetiredResourceClose() throws Exception {
        StorageProperties properties = properties(node(
                "node-a", "http://old-storage:9000", "physical-old"));
        TestS3ClientManager manager = new TestS3ClientManager(properties);
        ExecutorService reloader = Executors.newSingleThreadExecutor();

        manager.reloadClients();
        TopologyLease oldLease = manager.acquireTopologyLease();
        S3Client oldClient = oldLease.getClient("node-a");
        S3Presigner oldPresigner = oldLease.getPresigner("node-a");
        assertThat(oldLease.getNodeConfig("node-a").getPhysicalStorageId())
                .isEqualTo("physical-old");
        NodeConfig callerCopy = oldLease.getNodeConfig("node-a");
        callerCopy.setEndpoint("http://caller-mutated:9000");
        assertThat(oldLease.getNodeConfig("node-a").getEndpoint())
                .isEqualTo("http://old-storage:9000");

        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch allowBuild = new CountDownLatch(1);
        manager.blockClientBuild("http://new-storage:9000", buildStarted, allowBuild);
        properties.setNodes(List.of(node(
                "node-a", "http://new-storage:9000", "physical-new")));
        CompletableFuture<Void> reload = CompletableFuture.runAsync(manager::reloadClients, reloader);
        assertThat(buildStarted.await(5, TimeUnit.SECONDS)).isTrue();

        try (TopologyLease duringBuild = manager.acquireTopologyLease()) {
            assertThat(duringBuild.revision()).isEqualTo(oldLease.revision());
            assertThat(duringBuild.getClient("node-a")).isSameAs(oldClient);
            assertThat(duringBuild.getPresigner("node-a")).isSameAs(oldPresigner);
            assertThat(duringBuild.getNodeConfig("node-a").getEndpoint())
                    .isEqualTo("http://old-storage:9000");
        }

        allowBuild.countDown();
        reload.get(5, TimeUnit.SECONDS);
        try (TopologyLease current = manager.acquireTopologyLease()) {
            assertThat(current.revision()).isGreaterThan(oldLease.revision());
            assertThat(current.getClient("node-a")).isNotSameAs(oldClient);
            assertThat(current.getPresigner("node-a")).isNotSameAs(oldPresigner);
            assertThat(current.getNodeConfig("node-a").getEndpoint())
                    .isEqualTo("http://new-storage:9000");
            assertThat(current.getNodeConfig("node-a").getPhysicalStorageId())
                    .isEqualTo("physical-new");
        }

        assertThatThrownBy(oldLease::verifyCurrent)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topology changed");
        verify(oldClient, never()).close();
        verify(oldPresigner, never()).close();
        oldLease.close();
        verify(oldClient).close();
        verify(oldPresigner).close();

        manager.cleanup();
        reloader.shutdownNow();
    }

    @Test
    @DisplayName("reused resources should survive later replacement until every owning lease exits")
    void shouldKeepCrossGenerationReusedResourcesUntilOldLeaseCloses() {
        StorageProperties properties = properties(node(
                "node-a", "http://storage-v1:9000", "physical-v1"));
        TestS3ClientManager manager = new TestS3ClientManager(properties);
        manager.reloadClients();
        TopologyLease firstLease = manager.acquireTopologyLease();
        S3Client reusedClient = firstLease.getClient("node-a");
        S3Presigner reusedPresigner = firstLease.getPresigner("node-a");
        long firstRevision = firstLease.revision();

        try {
            manager.reloadClients();
            try (TopologyLease reusedTopology = manager.acquireTopologyLease()) {
                assertThat(reusedTopology.revision()).isGreaterThan(firstRevision);
                assertThat(reusedTopology.getClient("node-a")).isSameAs(reusedClient);
                assertThat(reusedTopology.getPresigner("node-a")).isSameAs(reusedPresigner);
            }

            properties.setNodes(List.of(node(
                    "node-a", "http://storage-v2:9000", "physical-v2")));
            manager.reloadClients();

            assertThat(firstLease.getClient("node-a")).isSameAs(reusedClient);
            verify(reusedClient, never()).close();
            verify(reusedPresigner, never()).close();
        } finally {
            firstLease.close();
        }

        verify(reusedClient, times(1)).close();
        verify(reusedPresigner, times(1)).close();
        manager.cleanup();
        verify(reusedClient, times(1)).close();
        verify(reusedPresigner, times(1)).close();
    }

    @Test
    @DisplayName("reload should not cross the final stable snapshot critical section")
    void shouldLinearizeReloadBeforeOrAfterStableSnapshotAction() throws Exception {
        StorageProperties properties = properties(node(
                "node-a", "http://storage-v1:9000", "physical-v1"));
        TestS3ClientManager manager = new TestS3ClientManager(properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        manager.reloadClients();

        try (TopologyLease lease = manager.acquireTopologyLease()) {
            CountDownLatch criticalEntered = new CountDownLatch(1);
            CountDownLatch allowCommit = new CountDownLatch(1);
            CompletableFuture<Void> receiptCommit = CompletableFuture.runAsync(
                    () -> lease.runIfCurrent(() -> {
                        criticalEntered.countDown();
                        await(allowCommit);
                    }),
                    executor
            );
            assertThat(criticalEntered.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch publishAttempted = new CountDownLatch(1);
            manager.observeNextPublish(publishAttempted);
            properties.setNodes(List.of(node(
                    "node-a", "http://storage-v2:9000", "physical-v2")));
            CompletableFuture<Void> reload = CompletableFuture.runAsync(manager::reloadClients, executor);
            assertThat(publishAttempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(reload).isNotDone();

            allowCommit.countDown();
            receiptCommit.get(5, TimeUnit.SECONDS);
            reload.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(lease::verifyCurrent)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("topology changed");
        } finally {
            manager.cleanup();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("cleanup should wait for a stable snapshot action and close each resource once")
    void shouldFenceCleanupWithTheSameStableSnapshotGuard() throws Exception {
        StorageProperties properties = properties(node(
                "node-a", "http://storage:9000", "physical-a"));
        TestS3ClientManager manager = new TestS3ClientManager(properties);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        manager.reloadClients();
        TopologyLease lease = manager.acquireTopologyLease();
        S3Client client = lease.getClient("node-a");
        S3Presigner presigner = lease.getPresigner("node-a");
        CountDownLatch criticalEntered = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CompletableFuture<Void> receiptCommit = CompletableFuture.runAsync(
                () -> lease.runIfCurrent(() -> {
                    criticalEntered.countDown();
                    await(allowCommit);
                }),
                executor
        );
        assertThat(criticalEntered.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> cleanup = CompletableFuture.runAsync(manager::cleanup, executor);
        assertThat(cleanup).isNotDone();
        verify(client, never()).close();
        allowCommit.countDown();
        receiptCommit.get(5, TimeUnit.SECONDS);
        cleanup.get(5, TimeUnit.SECONDS);
        verify(client, never()).close();

        lease.close();
        verify(client, times(1)).close();
        verify(presigner, times(1)).close();
        executor.shutdownNow();
    }

    /** 构建只包含一个节点的存储配置。 */
    private static StorageProperties properties(NodeConfig node) {
        StorageProperties properties = new StorageProperties();
        properties.setNodes(List.of(node));
        return properties;
    }

    /** 构建可用于 topology 身份判断的完整测试节点。 */
    private static NodeConfig node(String name, String endpoint, String physicalStorageId) {
        NodeConfig node = new NodeConfig();
        node.setName(name);
        node.setEndpoint(endpoint);
        node.setPhysicalStorageId(physicalStorageId);
        node.setAccessKey("access-key");
        node.setSecretKey("secret-key");
        return node;
    }

    /** 在测试线程中等待闸门，并保留中断语义。 */
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test latch interrupted", e);
        }
    }

    /**
     * 使用 Mockito 资源替代真实网络客户端，并暴露确定性的构建/发布闸门。
     */
    private static final class TestS3ClientManager extends S3ClientManager {
        private final Map<String, S3Client> clients = new ConcurrentHashMap<>();
        private final Map<String, S3Presigner> presigners = new ConcurrentHashMap<>();
        private volatile String blockedEndpoint;
        private volatile CountDownLatch buildStarted;
        private volatile CountDownLatch allowBuild;
        private volatile CountDownLatch publishAttempted;

        private TestS3ClientManager(StorageProperties storageProperties) {
            super(storageProperties);
        }

        /** 配置下一次指定 endpoint 客户端构建的确定性闸门。 */
        private void blockClientBuild(
                String endpoint,
                CountDownLatch started,
                CountDownLatch allowed
        ) {
            blockedEndpoint = endpoint;
            buildStarted = started;
            allowBuild = allowed;
        }

        /** 观察下一次 topology 进入原子发布前的边界。 */
        private void observeNextPublish(CountDownLatch attempted) {
            publishAttempted = attempted;
        }

        @Override
        protected S3Client createS3Client(NodeConfig nodeConfig) {
            if (nodeConfig.getEndpoint().equals(blockedEndpoint)) {
                buildStarted.countDown();
                await(allowBuild);
            }
            return clients.computeIfAbsent(nodeConfig.getEndpoint(), ignored -> mock(S3Client.class));
        }

        @Override
        protected S3Presigner createPresigner(NodeConfig nodeConfig, String endpoint) {
            return presigners.computeIfAbsent(endpoint, ignored -> mock(S3Presigner.class));
        }

        @Override
        protected void beforeTopologyPublish(long revision) {
            CountDownLatch attempted = publishAttempted;
            if (attempted != null) {
                publishAttempted = null;
                attempted.countDown();
            }
        }
    }
}
