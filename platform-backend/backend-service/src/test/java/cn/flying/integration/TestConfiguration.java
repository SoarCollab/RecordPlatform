package cn.flying.integration;

import cn.flying.common.util.*;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ImageStoreMapper;
import cn.flying.dao.mapper.SysOperationLogMapper;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import io.minio.MinioClient;
import org.mockito.Mockito;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 集成测试配置类
 * 提供Spring Boot测试所需的配置和Mock Bean
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = {
        MybatisPlusAutoConfiguration.class,
        RabbitAutoConfiguration.class,
        org.apache.dubbo.spring.boot.autoconfigure.DubboAutoConfiguration.class
})
@ComponentScan(
        basePackages = {"cn.flying.service"},
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.REGEX,
                pattern = "cn.flying.service.impl.*Test"
        )
)
public class TestConfiguration {

    // ===== 数据访问层Mock =====
    // 注意：某些Mapper在这里统一Mock，特定测试可以再次Mock覆盖

    @MockBean
    private FileMapper fileMapper;

    @MockBean
    private AccountMapper accountMapper;

    @MockBean
    private ImageStoreMapper imageStoreMapper;

    @MockBean
    private SysOperationLogMapper sysOperationLogMapper;

    // ===== 外部服务Mock =====
    // 注意：外部服务的MockBean需要统一定义，因为Service使用@DubboReference注入

    @MockBean
    private BlockChainService blockChainService;

    @MockBean
    private DistributedStorageService distributedStorageService;

    @MockBean
    private FileUploadRedisStateManager fileUploadRedisStateManager;

    @MockBean
    private AmqpTemplate rabbitTemplate;

    @MockBean
    private StringRedisTemplate stringRedisTemplate;

    @MockBean
    private ApplicationEventPublisher eventPublisher;

    @MockBean
    private MinioClient minioClient;

    @Bean
    public CacheUtils cacheUtils() {
        return Mockito.mock(CacheUtils.class);
    }

    // ===== 数据访问层Mock =====

    @Bean
    public FlowUtils flowUtils() {
        return Mockito.mock(FlowUtils.class);
    }

    @Bean
    public IdUtils idUtils() {
        return Mockito.mock(IdUtils.class);
    }

    @Bean
    public SecurityUtils securityUtils() {
        return Mockito.mock(SecurityUtils.class);
    }

    @Bean
    public UidEncoder uidEncoder() {
        return Mockito.mock(UidEncoder.class);
    }

    // ===== 安全相关Bean =====

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}