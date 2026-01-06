package cn.flying.service.sse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SseEmitterManager.
 * Verifies connection management, multi-device support, tenant isolation, and broadcasting.
 */
@DisplayName("SseEmitterManager Tests")
class SseEmitterManagerTest {

    private SseEmitterManager manager;

    private static final Long TENANT_1 = 1L;
    private static final Long TENANT_2 = 2L;
    private static final Long USER_1 = 100L;
    private static final Long USER_2 = 200L;

    /**
     * 初始化待测对象，保证每个用例之间状态隔离。
     */
    @BeforeEach
    void setUp() {
        manager = new SseEmitterManager();
    }

    @Nested
    @DisplayName("Connection Management")
    class ConnectionManagement {

        @Test
        @DisplayName("should create new connection successfully")
        void shouldCreateConnection() {
            String connId = UUID.randomUUID().toString();

            SseEmitter emitter = manager.createConnection(TENANT_1, USER_1, connId);

            assertNotNull(emitter);
            assertTrue(manager.isOnline(TENANT_1, USER_1));
            assertEquals(1, manager.getUserConnectionCount(TENANT_1, USER_1));
        }

        @Test
        @DisplayName("should support multiple connections per user")
        void shouldSupportMultipleConnections() {
            String conn1 = "conn-1";
            String conn2 = "conn-2";
            String conn3 = "conn-3";

            manager.createConnection(TENANT_1, USER_1, conn1);
            manager.createConnection(TENANT_1, USER_1, conn2);
            manager.createConnection(TENANT_1, USER_1, conn3);

            assertEquals(3, manager.getUserConnectionCount(TENANT_1, USER_1));
            assertTrue(manager.isOnline(TENANT_1, USER_1));
        }

        @Test
        @DisplayName("should remove connection correctly")
        void shouldRemoveConnection() {
            String connId = "test-conn";
            manager.createConnection(TENANT_1, USER_1, connId);

            manager.removeConnection(TENANT_1, USER_1, connId);

            assertFalse(manager.isOnline(TENANT_1, USER_1));
            assertEquals(0, manager.getUserConnectionCount(TENANT_1, USER_1));
        }

        @Test
        @DisplayName("should handle removal of non-existent connection gracefully")
        void shouldHandleRemovalOfNonExistent() {
            // Should not throw
            assertDoesNotThrow(() ->
                    manager.removeConnection(TENANT_1, USER_1, "non-existent"));
        }

        @Test
        @DisplayName("should limit max connections per user")
        void shouldLimitMaxConnections() {
            // Create 6 connections (max is 5)
            for (int i = 0; i < 6; i++) {
                manager.createConnection(TENANT_1, USER_1, "conn-" + i);
            }

            // Should only have 5 connections (oldest removed)
            assertEquals(5, manager.getUserConnectionCount(TENANT_1, USER_1));
        }
    }

    @Nested
    @DisplayName("Tenant Isolation")
    class TenantIsolation {

        @Test
        @DisplayName("should isolate connections between tenants")
        void shouldIsolateByTenant() {
            manager.createConnection(TENANT_1, USER_1, "conn-t1");
            manager.createConnection(TENANT_2, USER_1, "conn-t2");

            assertEquals(1, manager.getUserConnectionCount(TENANT_1, USER_1));
            assertEquals(1, manager.getUserConnectionCount(TENANT_2, USER_1));
            assertEquals(1, manager.getOnlineCount(TENANT_1));
            assertEquals(1, manager.getOnlineCount(TENANT_2));
        }

        @Test
        @DisplayName("should track online users per tenant")
        void shouldTrackOnlineUsersPerTenant() {
            manager.createConnection(TENANT_1, USER_1, "conn-1");
            manager.createConnection(TENANT_1, USER_2, "conn-2");
            manager.createConnection(TENANT_2, USER_1, "conn-3");

            assertEquals(2, manager.getOnlineCount(TENANT_1));
            assertEquals(1, manager.getOnlineCount(TENANT_2));

            Set<Long> tenant1Users = manager.getOnlineUsers(TENANT_1);
            assertTrue(tenant1Users.contains(USER_1));
            assertTrue(tenant1Users.contains(USER_2));
        }

        @Test
        @DisplayName("should return empty set for non-existent tenant")
        void shouldReturnEmptyForNonExistentTenant() {
            Set<Long> users = manager.getOnlineUsers(999L);

            assertNotNull(users);
            assertTrue(users.isEmpty());
        }
    }

    @Nested
    @DisplayName("Online Status")
    class OnlineStatus {

        @Test
        @DisplayName("should report user as online when connected")
        void shouldReportOnlineWhenConnected() {
            manager.createConnection(TENANT_1, USER_1, "conn-1");

            assertTrue(manager.isOnline(TENANT_1, USER_1));
        }

        @Test
        @DisplayName("should report user as offline when all connections removed")
        void shouldReportOfflineWhenDisconnected() {
            String conn1 = "conn-1";
            String conn2 = "conn-2";
            manager.createConnection(TENANT_1, USER_1, conn1);
            manager.createConnection(TENANT_1, USER_1, conn2);

            manager.removeConnection(TENANT_1, USER_1, conn1);
            assertTrue(manager.isOnline(TENANT_1, USER_1)); // Still has one connection

            manager.removeConnection(TENANT_1, USER_1, conn2);
            assertFalse(manager.isOnline(TENANT_1, USER_1)); // All connections gone
        }

        @Test
        @DisplayName("should report offline for never-connected user")
        void shouldReportOfflineForNeverConnected() {
            assertFalse(manager.isOnline(TENANT_1, 999L));
        }

        @Test
        @DisplayName("should return zero count for non-existent tenant")
        void shouldReturnZeroCountForNonExistentTenant() {
            assertEquals(0, manager.getOnlineCount(999L));
        }
    }

    @Nested
    @DisplayName("Concurrency")
    class Concurrency {

        @Test
        @DisplayName("should handle concurrent connections safely")
        void shouldHandleConcurrentConnections() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        SseEmitter emitter = manager.createConnection(TENANT_1, USER_1, "conn-" + idx);
                        if (emitter != null) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // Should have max 5 connections (oldest removed)
            assertEquals(5, manager.getUserConnectionCount(TENANT_1, USER_1));
            assertTrue(manager.isOnline(TENANT_1, USER_1));
        }

        @Test
        @DisplayName("should handle concurrent removals safely")
        void shouldHandleConcurrentRemovals() throws InterruptedException {
            // First create some connections
            for (int i = 0; i < 5; i++) {
                manager.createConnection(TENANT_1, USER_1, "conn-" + i);
            }

            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        manager.removeConnection(TENANT_1, USER_1, "conn-" + idx);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            assertEquals(0, manager.getUserConnectionCount(TENANT_1, USER_1));
            assertFalse(manager.isOnline(TENANT_1, USER_1));
        }
    }

    @Nested
    @DisplayName("Broadcasting")
    class Broadcasting {

        @Test
        @DisplayName("should broadcast to all users in tenant")
        void shouldBroadcastToTenant() {
            manager.createConnection(TENANT_1, USER_1, "conn-1");
            manager.createConnection(TENANT_1, USER_2, "conn-2");
            manager.createConnection(TENANT_2, USER_1, "conn-3"); // Different tenant

            SseEvent event = SseEvent.of(SseEventType.NEW_ANNOUNCEMENT, "Test announcement");

            // This won't throw even though connections can't actually receive
            // (they're not connected to real clients in tests)
            assertDoesNotThrow(() -> manager.broadcastToTenant(TENANT_1, event));
        }

        @Test
        @DisplayName("should send to specific user")
        void shouldSendToUser() {
            manager.createConnection(TENANT_1, USER_1, "conn-1");
            manager.createConnection(TENANT_1, USER_1, "conn-2");

            SseEvent event = SseEvent.of(SseEventType.NEW_MESSAGE, "Test message");

            assertDoesNotThrow(() -> manager.sendToUser(TENANT_1, USER_1, event));
        }

        @Test
        @DisplayName("should send to multiple users")
        void shouldSendToUsers() {
            manager.createConnection(TENANT_1, USER_1, "conn-1");
            manager.createConnection(TENANT_1, USER_2, "conn-2");

            SseEvent event = SseEvent.of(SseEventType.NEW_MESSAGE, "Test notification");

            assertDoesNotThrow(() ->
                    manager.sendToUsers(TENANT_1, Set.of(USER_1, USER_2), event));
        }

        @Test
        @DisplayName("should handle send to non-existent user gracefully")
        void shouldHandleSendToNonExistent() {
            SseEvent event = SseEvent.of(SseEventType.NEW_MESSAGE, "Test");

            assertDoesNotThrow(() -> manager.sendToUser(TENANT_1, 999L, event));
        }

        @Test
        @DisplayName("should handle broadcast to empty tenant gracefully")
        void shouldHandleBroadcastToEmpty() {
            SseEvent event = SseEvent.of(SseEventType.NEW_ANNOUNCEMENT, "Test");

            assertDoesNotThrow(() -> manager.broadcastToTenant(999L, event));
        }
    }

    @Nested
    @DisplayName("Heartbeat")
    class Heartbeat {

        @Test
        @DisplayName("should not throw when sending heartbeat with no connections")
        void shouldNotThrowWithNoConnections() {
            assertDoesNotThrow(() -> manager.sendHeartbeat());
        }

        @Test
        @DisplayName("should not throw when sending heartbeat with connections")
        void shouldNotThrowWithConnections() {
            manager.createConnection(TENANT_1, USER_1, "conn-1");
            manager.createConnection(TENANT_1, USER_2, "conn-2");

            assertDoesNotThrow(() -> manager.sendHeartbeat());
        }
    }

    @Nested
    @DisplayName("SseEvent Types")
    class SseEventTypes {

        @Test
        @DisplayName("should create connected event")
        void shouldCreateConnectedEvent() {
            SseEvent event = SseEvent.connected();

            assertEquals(SseEventType.CONNECTED.getType(), event.getType());
            assertNotNull(event.getPayload());
        }

        @Test
        @DisplayName("should create heartbeat event")
        void shouldCreateHeartbeatEvent() {
            SseEvent event = SseEvent.heartbeat();

            assertEquals(SseEventType.HEARTBEAT.getType(), event.getType());
        }

        @Test
        @DisplayName("should create message event using of()")
        void shouldCreateMessageEvent() {
            SseEvent event = SseEvent.of(SseEventType.NEW_MESSAGE, "Test message");

            assertEquals(SseEventType.NEW_MESSAGE.getType(), event.getType());
            assertEquals("Test message", event.getPayload());
        }

        @Test
        @DisplayName("should create announcement event using of()")
        void shouldCreateAnnouncementEvent() {
            SseEvent event = SseEvent.of(SseEventType.NEW_ANNOUNCEMENT, "Test announcement");

            assertEquals(SseEventType.NEW_ANNOUNCEMENT.getType(), event.getType());
        }

        @Test
        @DisplayName("should include timestamp in event")
        void shouldIncludeTimestamp() {
            long before = System.currentTimeMillis();
            SseEvent event = SseEvent.of(SseEventType.TICKET_UPDATE, "update");
            long after = System.currentTimeMillis();

            assertTrue(event.getTimestamp() >= before && event.getTimestamp() <= after);
        }
    }
}
