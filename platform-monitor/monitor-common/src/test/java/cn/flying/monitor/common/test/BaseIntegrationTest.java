package cn.flying.monitor.common.test;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 集成测试基类
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class BaseIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("monitor_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3-management-alpine")
            .withReuse(true);

    @Container
    static GenericContainer<?> influxdb = new GenericContainer<>(DockerImageName.parse("influxdb:2.7-alpine"))
            .withExposedPorts(8086)
            .withEnv("INFLUXDB_DB", "monitor_test")
            .withEnv("INFLUXDB_ADMIN_USER", "admin")
            .withEnv("INFLUXDB_ADMIN_PASSWORD", "password")
            .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // MySQL配置
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");

        // Redis配置
        registry.add("spring.redis.host", redis::getHost);
        registry.add("spring.redis.port", () -> redis.getMappedPort(6379));

        // RabbitMQ配置
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);

        // InfluxDB配置
        registry.add("influxdb.url", () -> "http://" + influxdb.getHost() + ":" + influxdb.getMappedPort(8086));
        registry.add("influxdb.token", () -> "test-token");
        registry.add("influxdb.org", () -> "test-org");
        registry.add("influxdb.bucket", () -> "test-bucket");
    }

    @BeforeEach
    void setUp() {
        // 每个测试前的通用设置
        setupTestData();
    }

    /**
     * 子类可以重写此方法来设置测试数据
     */
    protected void setupTestData() {
        // 默认实现为空
    }

    /**
     * 清理测试数据
     */
    protected void cleanupTestData() {
        // 默认实现为空
    }
}