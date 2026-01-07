package cn.flying.common.tenant;

import org.junit.jupiter.api.*;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@DisplayName("TenantContext Tests")
class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Nested
    @DisplayName("Basic Tenant ID Operations")
    class BasicTenantIdTests {

        @Test
        @DisplayName("should set and get tenant ID")
        void setAndGetTenantId() {
            TenantContext.setTenantId(100L);

            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("should return null when tenant ID not set")
        void getTenantId_returnsNullWhenNotSet() {
            assertThat(TenantContext.getTenantId()).isNull();
        }

        @Test
        @DisplayName("should not set null tenant ID")
        void setTenantId_ignoresNull() {
            TenantContext.setTenantId(100L);
            TenantContext.setTenantId(null);

            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("should return default when not set")
        void getTenantIdOrDefault_returnsDefault() {
            Long result = TenantContext.getTenantIdOrDefault();

            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("should return tenant ID when set")
        void getTenantIdOrDefault_returnsTenantId() {
            TenantContext.setTenantId(200L);

            Long result = TenantContext.getTenantIdOrDefault();

            assertThat(result).isEqualTo(200L);
        }

        @Test
        @DisplayName("should clear tenant context")
        void clear_removesTenantId() {
            TenantContext.setTenantId(100L);
            TenantContext.setIgnoreIsolation(true);

            TenantContext.clear();

            assertThat(TenantContext.getTenantId()).isNull();
            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        }

        @Test
        @DisplayName("isSet should return true when tenant ID is set")
        void isSet_returnsTrueWhenSet() {
            TenantContext.setTenantId(100L);

            assertThat(TenantContext.isSet()).isTrue();
        }

        @Test
        @DisplayName("isSet should return false when tenant ID not set")
        void isSet_returnsFalseWhenNotSet() {
            assertThat(TenantContext.isSet()).isFalse();
        }
    }

    @Nested
    @DisplayName("Require Tenant ID")
    class RequireTenantIdTests {

        @Test
        @DisplayName("should return tenant ID when set")
        void requireTenantId_returnsTenantIdWhenSet() {
            TenantContext.setTenantId(300L);

            Long result = TenantContext.requireTenantId();

            assertThat(result).isEqualTo(300L);
        }

        @Test
        @DisplayName("should throw when tenant ID not set")
        void requireTenantId_throwsWhenNotSet() {
            assertThatThrownBy(TenantContext::requireTenantId)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TenantContext not set");
        }
    }

    @Nested
    @DisplayName("Ignore Isolation")
    class IgnoreIsolationTests {

        @Test
        @DisplayName("should default to false")
        void isIgnoreIsolation_defaultsToFalse() {
            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        }

        @Test
        @DisplayName("should set and get ignore isolation")
        void setAndGetIgnoreIsolation() {
            TenantContext.setIgnoreIsolation(true);

            assertThat(TenantContext.isIgnoreIsolation()).isTrue();
        }

        @Test
        @DisplayName("should clear ignore isolation")
        void clearIgnoreIsolation_resetsToFalse() {
            TenantContext.setIgnoreIsolation(true);

            TenantContext.clearIgnoreIsolation();

            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        }
    }

    @Nested
    @DisplayName("Context Switching - runWithTenant")
    class RunWithTenantTests {

        @Test
        @DisplayName("should execute action within tenant context")
        void runWithTenant_executesWithinContext() {
            AtomicReference<Long> capturedTenant = new AtomicReference<>();

            TenantContext.runWithTenant(500L, () -> {
                capturedTenant.set(TenantContext.getTenantId());
            });

            assertThat(capturedTenant.get()).isEqualTo(500L);
        }

        @Test
        @DisplayName("should restore previous context after execution")
        void runWithTenant_restoresPreviousContext() {
            TenantContext.setTenantId(100L);

            TenantContext.runWithTenant(500L, () -> {
                // Action
            });

            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
        }

        @Test
        @DisplayName("should clear context when previous was null")
        void runWithTenant_clearsWhenPreviousNull() {
            TenantContext.runWithTenant(500L, () -> {
                // Action
            });

            assertThat(TenantContext.getTenantId()).isNull();
        }

        @Test
        @DisplayName("should restore context even on exception")
        void runWithTenant_restoresOnException() {
            TenantContext.setTenantId(100L);

            assertThatThrownBy(() ->
                    TenantContext.runWithTenant(500L, () -> {
                        throw new RuntimeException("Test exception");
                    })
            ).isInstanceOf(RuntimeException.class);

            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("Context Switching - callWithTenant")
    class CallWithTenantTests {

        @Test
        @DisplayName("should execute supplier within tenant context")
        void callWithTenant_executesAndReturns() {
            String result = TenantContext.callWithTenant(600L, () -> {
                return "Tenant: " + TenantContext.getTenantId();
            });

            assertThat(result).isEqualTo("Tenant: 600");
        }

        @Test
        @DisplayName("should restore previous context after execution")
        void callWithTenant_restoresPreviousContext() {
            TenantContext.setTenantId(100L);

            TenantContext.callWithTenant(600L, () -> "result");

            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
        }
    }

    @Nested
    @DisplayName("Cross-Tenant Operations - runWithoutIsolation")
    class RunWithoutIsolationTests {

        @Test
        @DisplayName("should set ignore isolation during execution")
        void runWithoutIsolation_setsIgnoreIsolation() {
            AtomicReference<Boolean> capturedFlag = new AtomicReference<>();

            TenantContext.runWithoutIsolation(() -> {
                capturedFlag.set(TenantContext.isIgnoreIsolation());
            });

            assertThat(capturedFlag.get()).isTrue();
        }

        @Test
        @DisplayName("should restore previous isolation state")
        void runWithoutIsolation_restoresPreviousState() {
            TenantContext.setIgnoreIsolation(false);

            TenantContext.runWithoutIsolation(() -> {
                // Action
            });

            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        }

        @Test
        @DisplayName("should return value from supplier")
        void runWithoutIsolation_returnsValue() {
            String result = TenantContext.runWithoutIsolation(() -> "cross-tenant-result");

            assertThat(result).isEqualTo("cross-tenant-result");
        }

        @Test
        @DisplayName("should restore state on exception")
        void runWithoutIsolation_restoresOnException() {
            TenantContext.setIgnoreIsolation(false);

            assertThatThrownBy(() ->
                    TenantContext.runWithoutIsolation(() -> {
                        throw new RuntimeException("Test exception");
                    })
            ).isInstanceOf(RuntimeException.class);

            assertThat(TenantContext.isIgnoreIsolation()).isFalse();
        }
    }

    @Nested
    @DisplayName("Thread Isolation")
    class ThreadIsolationTests {

        @Test
        @DisplayName("should isolate tenant context between threads")
        void threadIsolation_separateContextsPerThread() throws Exception {
            TenantContext.setTenantId(100L);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Long> future = executor.submit(() -> {
                TenantContext.setTenantId(200L);
                return TenantContext.getTenantId();
            });

            Long otherThreadTenant = future.get(5, TimeUnit.SECONDS);
            executor.shutdown();

            // Main thread should still have original value
            assertThat(TenantContext.getTenantId()).isEqualTo(100L);
            // Other thread should have its own value
            assertThat(otherThreadTenant).isEqualTo(200L);
        }

        @Test
        @DisplayName("should not leak context to other threads")
        void threadIsolation_noLeak() throws Exception {
            TenantContext.setTenantId(999L);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<Long> future = executor.submit(TenantContext::getTenantId);

            Long otherThreadTenant = future.get(5, TimeUnit.SECONDS);
            executor.shutdown();

            // Other thread should not inherit context
            assertThat(otherThreadTenant).isNull();
        }
    }
}
