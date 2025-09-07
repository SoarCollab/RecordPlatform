package cn.flying.identity.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

/**
 * 启动检查配置
 * 在应用启动时检查各项依赖服务的连接状态
 *
 * @author 王贝强
 */
@Slf4j
@Component
public class StartupCheckConfig implements ApplicationRunner {

    @Resource
    private DataSource dataSource;

    @Resource
    private StringRedisTemplate redisTemplate;

    @Resource
    private ApplicationProperties applicationProperties;

    @Override
    public void run(ApplicationArguments args) {
        log.info("=== Platform Identity 启动检查开始 ===");

        // 检查数据库连接
        checkDatabaseConnection();

        // 检查Redis连接
        checkRedisConnection();

        // 打印启动信息
        printStartupInfo();

        log.info("=== Platform Identity 启动检查完成 ===");
    }

    /**
     * 检查数据库连接
     */
    private void checkDatabaseConnection() {
        try (Connection connection = dataSource.getConnection()) {
            int timeout = applicationProperties.getStartupCheck().getDbConnectionTimeout();
            if (connection.isValid(timeout)) {
                log.info("✓ 数据库连接正常");
            } else {
                log.error("✗ 数据库连接异常");
            }
        } catch (Exception e) {
            log.error("✗ 数据库连接失败: {}", e.getMessage());
        }
    }

    /**
     * 检查Redis连接
     */
    private void checkRedisConnection() {
        try {
            String testKey = applicationProperties.getStartupCheck().getRedisTestKey();
            String testValue = applicationProperties.getStartupCheck().getRedisTestValue();

            redisTemplate.opsForValue().set(testKey, testValue);
            String value = redisTemplate.opsForValue().get(testKey);
            if (testValue.equals(value)) {
                log.info("✓ Redis连接正常");
                redisTemplate.delete(testKey);
            } else {
                log.error("✗ Redis连接异常");
            }
        } catch (Exception e) {
            log.error("✗ Redis连接失败: {}", e.getMessage());
        }
    }

    /**
     * 打印启动信息
     */
    private void printStartupInfo() {
        ApplicationProperties.AppInfo appInfo = applicationProperties.getAppInfo();

        log.info("=== Platform Identity 服务信息 ===");
        log.info("服务名称: {}", appInfo.getName());
        log.info("服务版本: {}", appInfo.getVersion());
        log.info("认证框架: {}", appInfo.getAuthFramework());
        log.info("支持功能:");
        log.info("  - 用户注册/登录");
        log.info("  - OAuth2.0 授权");
        log.info("  - SSO 单点登录");
        log.info("  - 第三方登录");
        log.info("  - 邮件验证码");
        log.info("  - 操作日志记录");
        log.info("  - 权限验证");
        log.info("  - 流量监控");
        log.info("API文档地址: {}", appInfo.getApiDocUrl());
        log.info("=== 服务启动完成 ===");
    }
}
