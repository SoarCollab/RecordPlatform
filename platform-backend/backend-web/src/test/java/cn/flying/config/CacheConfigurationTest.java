package cn.flying.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证生产缓存名称合同不会重新创建用户文件列表缓存。
 */
class CacheConfigurationTest {

    /**
     * 用户文件列表必须直接读取事实源，同时保留其他独立缓存。
     */
    @Test
    void shouldNotConfigureUserFilesCache() {
        CacheManager cacheManager = new CacheConfiguration().cacheManager();

        assertThat(cacheManager.getCache("userFiles")).isNull();
        assertThat(cacheManager.getCache("fileDecryptInfo")).isNotNull();
        assertThat(cacheManager.getCache("transaction")).isNotNull();
    }
}
