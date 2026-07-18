package cn.flying.service.impl;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.TenantKeyUtils;
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
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.AnnotationCacheOperationSource;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.CacheEvictOperation;
import org.springframework.cache.interceptor.CacheOperation;
import org.springframework.cache.interceptor.CacheOperationSource;
import org.springframework.cache.interceptor.CacheableOperation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通过真实 Spring Cache AOP 代理验证用户文件缓存的租户隔离合同。
 */
@SpringJUnitConfig(FileServiceCacheTenantIsolationTest.CacheTestConfiguration.class)
@DisplayName("FileService Spring Cache Tenant Isolation Tests")
class FileServiceCacheTenantIsolationTest {

    private static final String USER_FILES_CACHE = "userFiles";
    private static final String CANONICAL_TENANT_USER_KEY =
            "T(cn.flying.common.util.TenantKeyUtils).currentTenantUserKey(#userId)";
    private static final Long USER_ID = 100L;
    private static final Long TENANT_ID_1 = 1L;
    private static final Long TENANT_ID_2 = 2L;

    private static final Set<String> EXPECTED_USER_FILES_CACHE_METHODS = Set.of(
            "FileQueryServiceImpl#getUserFilesList(Long)",
            "FileServiceImpl#storeFile(Long,String,List,List,String)",
            "FileServiceImpl#storeFile(Long,Long,String,List,List,String)",
            "FileServiceImpl#storeDirectUploadedFile(Long,Long,String,long,List,String)",
            "FileServiceImpl#changeFileStatusByName(Long,String,Integer)",
            "FileServiceImpl#changeFileStatusByHash(Long,String,Integer)",
            "FileServiceImpl#changeFileStatusById(Long,Long,Integer)",
            "FileServiceImpl#markFileUploadFailed(Long,Long)",
            "FileServiceImpl#deleteFiles(Long,List)",
            "FileServiceImpl#getUserFilesList(Long)",
            "FileServiceImpl#createNewVersion(Long,Long,String,long,String)"
    );

    @Resource
    private FileQueryServiceImpl fileQueryService;

    @Resource
    private FileServiceImpl fileService;

    @Resource
    private CacheManager cacheManager;

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
     * 在每个用例前重置内存数据、缓存和真实租户上下文。
     */
    @BeforeEach
    void setUp() {
        assertThat(TenantContext.isSet()).as("前一用例不得泄漏租户上下文").isFalse();
        fileStore.reset();
        requireUserFilesCache().clear();
    }

    /**
     * 在每个用例后清理 ThreadLocal 和缓存，保证测试顺序无关。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
        requireUserFilesCache().clear();
        assertThat(TenantContext.isSet()).isFalse();
    }

    /**
     * 证明读取和写入服务均由 Spring 缓存切面代理，而不是直接实例调用。
     */
    @Test
    @DisplayName("should invoke production services through Spring AOP proxies")
    void shouldInvokeProductionServicesThroughSpringAopProxies() {
        assertThat(AopUtils.isAopProxy(fileQueryService)).isTrue();
        assertThat(AopUtils.isCglibProxy(fileQueryService)).isTrue();
        assertThat(AopUtils.isAopProxy(fileService)).isTrue();
        assertThat(AopUtils.isCglibProxy(fileService)).isTrue();
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
     * 证明相同用户在两个租户下各自首次回源，后续及交错切换均命中自己的缓存。
     */
    @Test
    @DisplayName("should isolate cache hits for the same user across tenants")
    void shouldIsolateCacheHitsForSameUserAcrossTenants() {
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "tenant-one.pdf")));
        fileStore.setFiles(TENANT_ID_2, List.of(file(2001L, TENANT_ID_2, "tenant-two.pdf")));

        List<File> tenantOneFirst = callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantOneCached = callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantTwoFirst = callAsTenant(TENANT_ID_2, () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantOneAfterSwitch = callAsTenant(TENANT_ID_1,
                () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantTwoCached = callAsTenant(TENANT_ID_2, () -> fileQueryService.getUserFilesList(USER_ID));

        assertThat(tenantOneFirst).extracting(File::getFileName).containsExactly("tenant-one.pdf");
        assertThat(tenantOneCached).isSameAs(tenantOneFirst);
        assertThat(tenantOneAfterSwitch).isSameAs(tenantOneFirst);
        assertThat(tenantTwoFirst).extracting(File::getFileName).containsExactly("tenant-two.pdf");
        assertThat(tenantTwoCached).isSameAs(tenantTwoFirst);
        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(1);
        assertThat(fileStore.selectCount(TENANT_ID_2)).isEqualTo(1);
        assertThat(requireUserFilesCache().get("1:100")).isNotNull();
        assertThat(requireUserFilesCache().get("2:100")).isNotNull();
    }

    /**
     * 证明缓存条目只写入 canonical tenantId:userId key，不回退到裸用户 ID。
     */
    @Test
    @DisplayName("should store entries only under the canonical tenant user key")
    void shouldStoreEntriesOnlyUnderCanonicalTenantUserKey() {
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "tenant-one.pdf")));

        callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));

        String canonicalKey = TenantKeyUtils.tenantUserKey(TENANT_ID_1, USER_ID);
        assertThat(canonicalKey).isEqualTo("1:100");
        assertThat(requireUserFilesCache().get(canonicalKey)).isNotNull();
        assertThat(requireUserFilesCache().get(USER_ID)).isNull();
        assertThat(requireUserFilesCache().get(USER_ID.toString())).isNull();
    }

    /**
     * 证明真实 CacheEvict 代理只清除当前租户条目，另一租户继续命中缓存。
     */
    @Test
    @DisplayName("should evict only the current tenant through a production CacheEvict method")
    void shouldEvictOnlyCurrentTenantThroughProductionCacheEvictMethod() {
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "tenant-one-v1.pdf")));
        fileStore.setFiles(TENANT_ID_2, List.of(file(2001L, TENANT_ID_2, "tenant-two-v1.pdf")));
        callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantTwoCached = callAsTenant(TENANT_ID_2,
                () -> fileQueryService.getUserFilesList(USER_ID));

        fileStore.setFiles(TENANT_ID_1, List.of(file(1002L, TENANT_ID_1, "tenant-one-v2.pdf")));
        runAsTenant(TENANT_ID_1, () -> fileService.changeFileStatusById(USER_ID, 1001L, 2));

        assertThat(requireUserFilesCache().get("1:100")).isNull();
        assertThat(requireUserFilesCache().get("2:100")).isNotNull();

        List<File> tenantTwoAfterEviction = callAsTenant(TENANT_ID_2,
                () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> tenantOneAfterEviction = callAsTenant(TENANT_ID_1,
                () -> fileQueryService.getUserFilesList(USER_ID));

        assertThat(tenantTwoAfterEviction).isSameAs(tenantTwoCached);
        assertThat(tenantOneAfterEviction).extracting(File::getFileName)
                .containsExactly("tenant-one-v2.pdf");
        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
        assertThat(fileStore.selectCount(TENANT_ID_2)).isEqualTo(1);
    }

    /**
     * 证明 unless 条件不会缓存空结果，后续查询仍会回源。
     */
    @Test
    @DisplayName("should not cache empty query results")
    void shouldNotCacheEmptyQueryResults() {
        fileStore.setFiles(TENANT_ID_1, List.of());

        assertThat(callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID))).isEmpty();
        assertThat(callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID))).isEmpty();

        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
        assertThat(requireUserFilesCache().get("1:100")).isNull();
    }

    /**
     * 证明异常查询不会写入缓存，恢复后可正常回源并缓存成功结果。
     */
    @Test
    @DisplayName("should not cache exceptions or leak tenant context")
    void shouldNotCacheExceptionsOrLeakTenantContext() {
        fileStore.failSelectFor(TENANT_ID_1, true);

        assertThatThrownBy(() -> callAsTenant(TENANT_ID_1,
                () -> fileQueryService.getUserFilesList(USER_ID)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated mapper failure");
        assertThat(TenantContext.isSet()).isFalse();
        assertThat(requireUserFilesCache().get("1:100")).isNull();

        fileStore.failSelectFor(TENANT_ID_1, false);
        fileStore.setFiles(TENANT_ID_1, List.of(file(1001L, TENANT_ID_1, "recovered.pdf")));
        List<File> recovered = callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));
        List<File> cached = callAsTenant(TENANT_ID_1, () -> fileQueryService.getUserFilesList(USER_ID));

        assertThat(recovered).extracting(File::getFileName).containsExactly("recovered.pdf");
        assertThat(cached).isSameAs(recovered);
        assertThat(fileStore.selectCount(TENANT_ID_1)).isEqualTo(2);
    }

    /**
     * 枚举全部 userFiles 缓存操作，防止新增写路径遗漏租户 key 或误用用户 ID。
     */
    @Test
    @DisplayName("should keep every userFiles annotation on the canonical tenant key")
    void shouldKeepEveryUserFilesAnnotationOnCanonicalTenantKey() {
        List<DiscoveredCacheOperation> operations = discoverUserFilesOperations();

        assertThat(operations).extracting(DiscoveredCacheOperation::methodId)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_USER_FILES_CACHE_METHODS);
        assertThat(operations).allSatisfy(discovered -> {
            assertThat(discovered.operation().getCacheNames()).containsExactly(USER_FILES_CACHE);
            assertThat(discovered.operation().getKey()).isEqualTo(CANONICAL_TENANT_USER_KEY);
        });

        Set<String> cacheableMethods = operations.stream()
                .filter(discovered -> discovered.operation() instanceof CacheableOperation)
                .map(DiscoveredCacheOperation::methodId)
                .collect(Collectors.toSet());
        assertThat(cacheableMethods).containsExactlyInAnyOrder(
                "FileQueryServiceImpl#getUserFilesList(Long)",
                "FileServiceImpl#getUserFilesList(Long)"
        );
        assertThat(operations.stream()
                .filter(discovered -> !cacheableMethods.contains(discovered.methodId()))
                .map(DiscoveredCacheOperation::operation))
                .allMatch(CacheEvictOperation.class::isInstance);
    }

    /**
     * 从生产类元数据中提取全部 userFiles 缓存操作。
     */
    private List<DiscoveredCacheOperation> discoverUserFilesOperations() {
        CacheOperationSource source = new AnnotationCacheOperationSource();
        List<DiscoveredCacheOperation> discovered = new ArrayList<>();
        for (Class<?> targetClass : List.of(FileQueryServiceImpl.class, FileServiceImpl.class)) {
            for (Method method : targetClass.getDeclaredMethods()) {
                Collection<CacheOperation> methodOperations = source.getCacheOperations(method, targetClass);
                if (methodOperations == null) {
                    continue;
                }
                methodOperations.stream()
                        .filter(operation -> operation.getCacheNames().contains(USER_FILES_CACHE))
                        .map(operation -> new DiscoveredCacheOperation(operationId(targetClass, method), operation))
                        .forEach(discovered::add);
            }
        }
        return discovered;
    }

    /**
     * 构造稳定的方法签名，供缓存注解合同断言使用。
     */
    private String operationId(Class<?> targetClass, Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(","));
        return targetClass.getSimpleName() + "#" + method.getName() + "(" + parameters + ")";
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
     * 在指定真实租户上下文中执行无返回值操作，并验证上下文已恢复。
     */
    private void runAsTenant(Long tenantId, Runnable action) {
        assertThat(TenantContext.isSet()).isFalse();
        TenantContext.runWithTenant(tenantId, action);
        assertThat(TenantContext.isSet()).isFalse();
    }

    /**
     * 获取测试使用的用户文件缓存并在缺失时立即失败。
     */
    private Cache requireUserFilesCache() {
        Cache cache = cacheManager.getCache(USER_FILES_CACHE);
        assertThat(cache).isNotNull();
        return cache;
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
     * 缓存操作及其声明方法的组合。
     */
    private record DiscoveredCacheOperation(String methodId, CacheOperation operation) {
    }

    /**
     * 首个子任务使用的最小 Spring 缓存测试上下文。
     */
    @Configuration(proxyBeanMethods = false)
    @EnableCaching(proxyTargetClass = true)
    static class CacheTestConfiguration {

        /**
         * 提供与生产同名、但不依赖 Redis 的确定性内存缓存。
         */
        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(USER_FILES_CACHE);
        }

        /**
         * 提供按租户保存查询结果与调用次数的内存数据源。
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
                    (proxy, method, args) -> invokeMapper(fileStore, proxy, method, args)
            );
        }

        /**
         * 创建真实查询服务，由 Spring 缓存后处理器负责代理。
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
                    Runnable::run
            );
        }

        /**
         * 创建真实写服务并注入 mapper，使实际 CacheEvict 方法可执行。
         */
        @Bean
        FileServiceImpl fileService(FileMapper fileMapper, CacheManager cacheManager) {
            FileServiceImpl service = new FileServiceImpl(
                    null,
                    null,
                    null,
                    null,
                    null,
                    cacheManager,
                    null,
                    null,
                    null,
                    null,
                    null
            );
            ReflectionTestUtils.setField(service, "baseMapper", fileMapper);
            return service;
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
            return switch (method.getName()) {
                case "selectList" -> fileStore.selectFiles();
                case "update" -> 1;
                default -> throw new UnsupportedOperationException(
                        "Unexpected FileMapper call: " + method.toGenericString());
            };
        }
    }

    /**
     * 不依赖数据库的租户感知数据源，用于观测真实缓存命中和回源次数。
     */
    static final class TenantAwareFileStore {

        private final Map<Long, List<File>> filesByTenant = new HashMap<>();
        private final Map<Long, Integer> selectCounts = new HashMap<>();
        private final Set<Long> failingTenants = new HashSet<>();

        /**
         * 清空所有数据和计数。
         */
        void reset() {
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
         * 返回当前租户的文件并记录一次真实回源。
         */
        List<File> selectFiles() {
            Long tenantId = TenantContext.requireTenantId();
            selectCounts.merge(tenantId, 1, Integer::sum);
            if (failingTenants.contains(tenantId)) {
                throw new IllegalStateException("simulated mapper failure for tenant " + tenantId);
            }
            return filesByTenant.getOrDefault(tenantId, List.of());
        }

        /**
         * 返回指定租户累计回源次数。
         */
        int selectCount(Long tenantId) {
            return selectCounts.getOrDefault(tenantId, 0);
        }
    }
}
