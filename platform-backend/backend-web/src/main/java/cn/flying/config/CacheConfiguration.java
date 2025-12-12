package cn.flying.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 缓存配置 - CQRS 读模型优化
 * <p>
 * 使用 Caffeine 作为本地缓存（L1 Cache），为 CQRS Query 操作提供高性能读取。
 * </p>
 *
 * <h3>缓存分类</h3>
 * <ul>
 *   <li><b>Query 缓存</b>：userFiles, fileDecryptInfo, fileAddress - 高命中率读操作</li>
 *   <li><b>元数据缓存</b>：fileMeta, fileList - 文件元信息</li>
 *   <li><b>分享缓存</b>：sharedFiles - 分享文件查询</li>
 * </ul>
 *
 * <h3>缓存策略</h3>
 * <ul>
 *   <li>默认 TTL：30 分钟（平衡一致性与性能）</li>
 *   <li>最大容量：10,000 条目</li>
 *   <li>写后过期：防止缓存数据过期</li>
 * </ul>
 *
 * @see cn.flying.service.FileQueryService CQRS Query 服务
 */
@Configuration
@EnableCaching
public class CacheConfiguration {

    /**
     * CQRS 读模型缓存名称
     */
    public static final String CACHE_USER_FILES = "userFiles";
    public static final String CACHE_FILE_DECRYPT_INFO = "fileDecryptInfo";
    public static final String CACHE_FILE_ADDRESS = "fileAddress";
    public static final String CACHE_SHARED_FILES = "sharedFiles";
    public static final String CACHE_FILE_META = "fileMeta";
    public static final String CACHE_FILE_LIST = "fileList";
    public static final String CACHE_TRANSACTION = "transaction";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .maximumSize(10000)
                .recordStats());
        manager.setCacheNames(List.of(
                // CQRS Query 缓存
                CACHE_USER_FILES,
                CACHE_FILE_DECRYPT_INFO,
                CACHE_FILE_ADDRESS,
                CACHE_SHARED_FILES,
                CACHE_TRANSACTION,
                // 元数据缓存
                CACHE_FILE_META,
                CACHE_FILE_LIST
        ));
        return manager;
    }
}
