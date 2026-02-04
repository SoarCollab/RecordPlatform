package cn.flying.test;

import cn.flying.dao.vo.system.ChainStatusVO;
import cn.flying.dao.vo.system.MonitorMetricsVO;
import cn.flying.dao.vo.system.SystemHealthVO;
import cn.flying.dao.vo.system.SystemStatsVO;
import cn.flying.service.SystemMonitorService;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Bean;

import cn.flying.BackendWebApplication;
import cn.flying.health.FiscoHealthIndicator;
import cn.flying.health.S3StorageHealthIndicator;
import cn.flying.service.impl.SystemMonitorServiceImpl;
import cn.flying.service.remote.FileRemoteClient;

import java.util.Collections;

/**
 * Test application class that excludes Dubbo auto-configuration and components using @DubboReference.
 * This prevents Dubbo from attempting to connect to a registry during tests.
 *
 * Additionally, BackendWebApplication is excluded because it enables Dubbo/Scheduling via annotations
 * and would re-activate Dubbo reference initialization during Spring context startup.
 *
 * Note: Dubbo auto-configuration is excluded via application-test.yml using
 * spring.autoconfigure.exclude property, as @SpringBootApplication(exclude=...)
 * may not work reliably with all Dubbo configurations.
 */
@SpringBootApplication
@MapperScan(basePackages = "cn.flying.dao.mapper")
@ComponentScan(
        basePackages = "cn.flying",
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        BackendWebApplication.class,
                        FileRemoteClient.class,
                        SystemMonitorServiceImpl.class,
                        FiscoHealthIndicator.class,
                        S3StorageHealthIndicator.class
                }
        )
)
public class TestApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    /**
     * 为测试环境提供一个轻量级的 {@link SystemMonitorService} 实现，避免引入 DubboReference 等外部依赖，
     * 同时保证 {@link cn.flying.controller.SystemController} 能正常完成依赖注入并启动容器。
     *
     * @return 测试用系统监控服务
     */
    @Bean
    public SystemMonitorService systemMonitorService() {
        return new TestSystemMonitorService();
    }

    private static final class TestSystemMonitorService implements SystemMonitorService {

        /**
         * 返回固定的系统统计信息，避免测试环境依赖真实数据库与存储统计。
         *
         * @return 系统统计 VO（默认值）
         */
        @Override
        public SystemStatsVO getSystemStats() {
            return new SystemStatsVO(0L, 0L, 0L, 0L, 0L, 0L);
        }

        /**
         * 返回固定的区块链状态，避免测试环境访问真实区块链服务。
         *
         * @return 区块链状态 VO（默认值）
         */
        @Override
        public ChainStatusVO getChainStatus() {
            return new ChainStatusVO(0L, 0L, 0L, 0, "TEST", false, System.currentTimeMillis());
        }

        /**
         * 返回固定的系统健康状态，避免测试环境连接 Redis/区块链/S3 等外部组件。
         *
         * @return 系统健康 VO（默认值）
         */
        @Override
        public SystemHealthVO getSystemHealth() {
            return new SystemHealthVO(
                    "UNKNOWN",
                    Collections.emptyMap(),
                    0L,
                    String.valueOf(System.currentTimeMillis())
            );
        }

        /**
         * 聚合返回系统统计、区块链状态与健康状态，供监控聚合接口使用。
         *
         * @return 监控指标聚合 VO（默认值）
         */
        @Override
        public MonitorMetricsVO getMonitorMetrics() {
            return new MonitorMetricsVO(getSystemStats(), getChainStatus(), getSystemHealth());
        }
    }
}
