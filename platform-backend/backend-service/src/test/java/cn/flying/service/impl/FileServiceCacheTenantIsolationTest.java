package cn.flying.service.impl;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import jakarta.annotation.Resource;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证用户文件列表始终直接读取持久化事实源，迟到旧查询不能回填进程内缓存。
 */
@SpringJUnitConfig(FileServiceCacheTenantIsolationTest.CacheTestConfiguration.class)
@DisplayName("FileService user file list freshness tests")
class FileServiceCacheTenantIsolationTest {

    private static final String USER_FILES_CACHE = "userFiles";
    private static final Long USER_ID = 100L;
    private static final Long TENANT_ID_1 = 1L;
    private static final Long TENANT_ID_2 = 2L;

    @Resource
    private FileQueryServiceImpl fileQueryService;

    @Resource
    private TenantAwareFileStore fileStore;

    /**
     * 初始化 MyBatis Plus 的实体元数据，使 Lambda wrapper 在纯单元环境中可用。
     */
    @BeforeAll
    static void initializeMyBatisMetadata() {
        boolean initialized = TableInfoHelper.getTableInfos().stream()
                .anyMatch(info -> info.getEntityType().equals(File.class));
        if (!initialized) {
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
            TableInfoHelper.initTableInfo(assistant, File.class);
        }
    }

    /**
     * 在每个用例前重置内存事实源和真实租户上下文。
     */
    @BeforeEach
    void setUp() {
        assertThat(TenantContext.isSet()).as("前一用例不得泄漏租户上下文").isFalse();
        fileStore.reset();
    }

    /**
     * 在每个用例后清理 ThreadLocal，保证测试顺序无关。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        fileStore.releaseBlockedSelect();
        assertThat(TenantContext.isSet()).isFalse();
    }

    /**
     * 证明测试仍通过真实 Spring 缓存代理调用查询服务，避免绕过生产注解元数据。
     */
    @Test
    @DisplayName("should invoke query service through the real Spring cache proxy")
    void shouldInvokeQueryServiceThroughSpringCacheProxy() {
        assertThat(AopUtils.isAopProxy(fileQueryService)).isTrue();
        assertThat(AopUtils.isCglibProxy(fileQueryService)).isTrue();
    }

    /**
     * 验证匿名分享文件查询不受方法级缓存短路，确保每次请求都重新校验授权与文件状态。
     */
    @Test
    @DisplayName("should not cache authorization-sensitive public share file lookup")
    void shouldNotCacheAuthorizationSensitivePublicShareFileLookup() throws NoSuchMethodException {
        CacheOperationSource source = new AnnotationCacheOperationSource();
        Method method = FileQueryServiceImpl.class.getMethod("getShareFile", String.class);

        assertThat(source.getCacheOperations(method, FileQueryServiceImpl.class)).isNullOrEmpty();
    }

    /**
     * 证明同一用户跨租户查询每次都回源，并继续依赖真实租户上下文完成隔离。
     */
    @Test
    @DisplayName("should read the persistent source on every tenant-scoped list query")
    void shouldReadPersistentSourceOnEveryTenantScopedListQuery() {
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "tenant-one-v1.pdf")));
        fileStore.setFiles(TENANT_ID_2, List.of(file(2001L, TENANT_ID_2, "tenant-two-v1.pdf")));

        List<File> tenantOneFirst = callAsTenant(
                TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));
        fileStore.setFiles(TENANT_ID_1, List.of(file(1002L, TENANT_ID_1, "tenant-one-v2.pdf")));
        List<File> tenantOneSecond = callAsTenant(
                TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantTwo = callAsTenant(
                TENANT_ID_2, () -> fileQueryService.getUserFilesList(USER_ID));

        assertThat(tenantOneFirst).extracting(File::getFileName)
                .containsExactly("tenant-one-v1.pdf");
        assertThat(tenantOneSecond).extracting(File::getFileName)
                .containsExactly("tenant-one-v2.pdf");
        assertThat(tenantTwo).extracting(File::getFileName)
                .containsExactly("tenant-two-v1.pdf");
        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
        assertThat(fileStore.selectCount(TENANT_ID_2)).isEqualTo(1);
    }

    /**
     * 用闩锁复现旧查询先抓取快照、DB 后提交、旧查询最后返回的时序；后续查询必须看到新事实。
     */
    @Test
    @DisplayName("should not let a late stale query refill the removed user list cache")
    void shouldNotLetLateStaleQueryRefillRemovedUserListCache() throws Exception {
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "before-commit.pdf")));
        BlockedSelect blockedSelect = fileStore.blockNextSelectAfterSnapshot();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var staleQuery = executor.submit(() -> callAsTenant(
                    TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID)));
            assertThat(blockedSelect.snapshotCaptured().await(5, TimeUnit.SECONDS)).isTrue();

            fileStore.setFiles(TENANT_ID_1,
                    List.of(file(1002L, TENANT_ID_1, "after-commit.pdf")));
            blockedSelect.releaseQuery().countDown();

            assertThat(staleQuery.get(5, TimeUnit.SECONDS))
                    .extracting(File::getFileName)
                    .containsExactly("before-commit.pdf");
            assertThat(callAsTenant(
                    TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID)))
                    .extracting(File::getFileName)
                    .containsExactly("after-commit.pdf");
            assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
        } finally {
            blockedSelect.releaseQuery().countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 证明空结果也不形成负缓存，后续提交的数据可立即被查询到。
     */
    @Test
    @DisplayName("should not negatively cache an empty user file list")
    void shouldNotNegativelyCacheEmptyUserFileList() {
        fileStore.setFiles(TENANT_ID_1, List.of());

        assertThat(callAsTenant(
                TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID))).isEmpty();
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "created.pdf")));

        assertThat(callAsTenant(
                TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID)))
                .extracting(File::getFileName)
                .containsExactly("created.pdf");
        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
    }

    /**
     * 证明异常查询不会泄漏租户上下文，恢复后仍重新读取事实源。
     */
    @Test
    @DisplayName("should recover from a source failure without leaking tenant context")
    void shouldRecoverFromSourceFailureWithoutLeakingTenantContext() {
        fileStore.failSelectFor(TENANT_ID_1, true);

        assertThatThrownBy(() -> callAsTenant(
                TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated mapper failure");
        assertThat(TenantContext.isSet()).isFalse();

        fileStore.failSelectFor(TENANT_ID_1, false);
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "recovered.pdf")));
        assertThat(callAsTenant(
                TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID)))
                .extracting(File::getFileName)
                .containsExactly("recovered.pdf");
        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
    }

    /**
     * 枚举读写服务的缓存元数据，防止以后重新引入会发生旧读晚回填的 userFiles 缓存。
     */
    @Test
    @DisplayName("should keep every userFiles Cacheable and CacheEvict annotation removed")
    void shouldKeepEveryUserFilesCacheOperationRemoved() {
        assertThat(discoverUserFilesOperations()).isEmpty();
    }

    /**
     * 从生产类元数据中提取全部 userFiles 缓存操作。
     */
    private List<CacheOperation> discoverUserFilesOperations() {
        CacheOperationSource source = new AnnotationCacheOperationSource();
        List<CacheOperation> discovered = new ArrayList<>();
        for (Class<?> targetClass : List.of(FileQueryServiceImpl.class, FileServiceImpl.class)) {
            for (Method method : targetClass.getDeclaredMethods()) {
                Collection<CacheOperation> operations = source.getCacheOperations(method, targetClass);
                if (operations == null) {
                    continue;
                }
                operations.stream()
                        .filter(operation -> operation.getCacheNames().contains(USER_FILES_CACHE))
                        .forEach(discovered::add);
            }
        }
        return discovered;
    }

    /**
     * 在指定真实租户上下文中执行带返回值操作，并验证上下文已恢复。
     */
    private <T> T callAsTenant(Long tenantId, Supplier<T> action) {
        assertThat(TenantContext.isSet()).isFalse();
        T result = TenantContext.callWithTenant(tenantId, action);
        assertThat(TenantContext.isSet()).isFalse();
        return result;
    }

    /**
     * 构造租户文件记录。
     */
    private File file(Long id, Long tenantId, String fileName) {
        return new File()
                .setId(id)
                .setUid(USER_ID)
                .setTenantId(tenantId)
                .setFileName(fileName)
                .setFileHash("hash-" + id)
                .setIsLatest(1);
    }

    /**
     * 旧查询快照抓取与允许返回之间的确定性并发门闩。
     */
    private record BlockedSelect(
            CountDownLatch snapshotCaptured,
            CountDownLatch releaseQuery
    ) {
    }

    /**
     * 只提供本测试所需 Bean 的最小 Spring 缓存上下文。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class CacheTestConfiguration {

        /**
         * 只注册仍在使用的交易缓存，故意不创建 userFiles 缓存。
         */
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager("transaction");
        }

        /**
         * 提供按租户保存查询结果与调用次数的内存事实源。
         */
        @Bean
        TenantAwareFileStore fileStore() {
            return new TenantAwareFileStore();
        }

        /**
         * 提供只实现本测试所需方法的 mapper test double。
         */
        @Bean
        FileMapper fileMapper(TenantAwareFileStore fileStore) {
            return (FileMapper) Proxy.newProxyInstance(
                    FileMapper.class.getClassLoader(),
                    new Class<?>[]{FileMapper.class},
                    (proxy, method, args) -> invokeMapper(fileStore, proxy, method, args));
        }

        /**
         * 创建真实查询服务，由 Spring 缓存后处理器解析生产缓存注解。
         */
        @Bean
        FileQueryServiceImpl fileQueryService(FileMapper fileMapper) {
            return new FileQueryServiceImpl(
                    fileMapper,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    Runnable::run);
        }

        /**
         * 分派 mapper test double 调用，并拒绝任何超出测试范围的数据库访问。
         */
        private static Object invokeMapper(TenantAwareFileStore fileStore, Object proxy,
                                           Method method, Object[] args) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "TenantAwareFileMapper";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
            if ("selectList".equals(method.getName())) {
                return fileStore.selectFiles();
            }
            throw new UnsupportedOperationException(
                    "Unexpected FileMapper call: " + method.toGenericString());
        }
    }

    /**
     * 不依赖数据库的租户感知事实源，用于确定性复现迟到旧查询。
     */
    static final class TenantAwareFileStore {

        private final Map<Long, List<File>> filesByTenant = new ConcurrentHashMap<>();
        private final Map<Long, AtomicInteger> selectCounts = new ConcurrentHashMap<>();
        private final Set<Long> failingTenants = ConcurrentHashMap.newKeySet();
        private final AtomicReference<BlockedSelect> blockedSelect = new AtomicReference<>();

        /**
         * 清空所有数据、计数和并发门闩。
         */
        void reset() {
            releaseBlockedSelect();
            filesByTenant.clear();
            selectCounts.clear();
            failingTenants.clear();
        }

        /**
         * 设置指定租户下一次回源应返回的文件列表。
         */
        void setFiles(Long tenantId, List<File> files) {
            filesByTenant.put(tenantId, List.copyOf(files));
        }

        /**
         * 控制指定租户的回源查询是否抛出异常。
         */
        void failSelectFor(Long tenantId, boolean fail) {
            if (fail) {
                failingTenants.add(tenantId);
            } else {
                failingTenants.remove(tenantId);
            }
        }

        /**
         * 阻塞下一次查询在抓取快照之后、返回调用方之前的时刻。
         */
        BlockedSelect blockNextSelectAfterSnapshot() {
            BlockedSelect gate = new BlockedSelect(
                    new CountDownLatch(1), new CountDownLatch(1));
            assertThat(blockedSelect.compareAndSet(null, gate)).isTrue();
            return gate;
        }

        /**
         * 释放可能仍在等待的查询，避免失败用例遗留线程。
         */
        void releaseBlockedSelect() {
            BlockedSelect gate = blockedSelect.getAndSet(null);
            if (gate != null) {
                gate.releaseQuery().countDown();
            }
        }

        /**
         * 抓取当前租户的文件快照、记录回源次数，并按测试门闩决定何时返回。
         */
        List<File> selectFiles() {
            Long tenantId = TenantContext.requireTenantId();
            selectCounts.computeIfAbsent(tenantId, ignored -> new AtomicInteger())
                    .incrementAndGet();
            if (failingTenants.contains(tenantId)) {
                throw new IllegalStateException("simulated mapper failure for tenant " + tenantId);
            }
            List<File> snapshot = filesByTenant.getOrDefault(tenantId, List.of());
            BlockedSelect gate = blockedSelect.getAndSet(null);
            if (gate != null) {
                gate.snapshotCaptured().countDown();
                try {
                    if (!gate.releaseQuery().await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to release stale query");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("stale query wait interrupted", interrupted);
                }
            }
            return snapshot;
        }

        /**
         * 返回指定租户累计回源次数。
         */
        int selectCount(Long tenantId) {
            AtomicInteger count = selectCounts.get(tenantId);
            return count == null ? 0 : count.get();
        }
    }
}
