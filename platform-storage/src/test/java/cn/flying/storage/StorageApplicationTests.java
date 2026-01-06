package cn.flying.storage;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

@SpringBootTest(properties = {
        "dubbo.protocol.name=injvm",
        "dubbo.registry.address=N/A",
        "dubbo.config-center.address=N/A",
        "dubbo.application.qos-enable=false",
        "spring.cloud.nacos.config.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false"
})
class StorageApplicationTests {

    @MockBean
    private RedissonClient redissonClient;

    /**
     * 验证 Spring 容器在测试环境下可正常启动（禁用 Dubbo/Nacos 等外部依赖）。
     */
    @Test
    void contextLoads() {
    }

}
