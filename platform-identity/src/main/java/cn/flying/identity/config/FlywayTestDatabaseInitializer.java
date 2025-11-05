package cn.flying.identity.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 在测试环境中启动时通过 Flyway 先清库再初始化，确保每次运行使用全新表结构。
 */
@Component
@ConditionalOnBean(Flyway.class)
@Profile("test")
public class FlywayTestDatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlywayTestDatabaseInitializer.class);

    private final Flyway flyway;

    public FlywayTestDatabaseInitializer(Flyway flyway) {
        this.flyway = flyway;
    }

    @Override
    public void run(ApplicationArguments args) {
        // 测试环境每次启动都重建数据库结构
        log.info("测试环境启用 Flyway 清空并重新执行所有迁移脚本");
        flyway.clean();
        flyway.migrate();
        log.info("测试环境数据库初始化完成");
    }
}
