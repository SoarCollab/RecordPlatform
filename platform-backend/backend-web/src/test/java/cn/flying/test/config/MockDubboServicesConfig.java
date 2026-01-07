package cn.flying.test.config;

import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test configuration that provides mock implementations for Dubbo services.
 *
 * @DubboReference creates proxies that attempt to connect to a registry during
 * Spring context initialization, which fails in test environments.
 * This configuration provides @Primary beans that override the Dubbo references.
 */
@TestConfiguration
public class MockDubboServicesConfig {

    @Bean
    @Primary
    public BlockChainService mockBlockChainService() {
        return Mockito.mock(BlockChainService.class);
    }

    @Bean
    @Primary
    public DistributedStorageService mockDistributedStorageService() {
        return Mockito.mock(DistributedStorageService.class);
    }
}
