package cn.flying.test;

import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.config.MockDubboServicesConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

@SpringBootTest(
        classes = TestApplication.class,
        properties = {
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "dubbo.registry.address=N/A",
                "dubbo.consumer.check=false"
        }
)
@Import(MockDubboServicesConfig.class)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class BaseIntegrationTest {

    // Mock JavaMailSender to avoid SMTP connection attempts during tests
    @MockBean
    protected JavaMailSender javaMailSender;

    // Mock RabbitTemplate to prevent actual message sending to RabbitMQ
    // This avoids MailQueueListener being triggered and potentially blocking tests
    @MockBean
    protected RabbitTemplate rabbitTemplate;

    // Mock FileRemoteClient (replaces the excluded one)
    @MockBean
    protected FileRemoteClient fileRemoteClient;

    // Mock Dubbo services - injected from MockDubboServicesConfig
    @Autowired
    protected BlockChainService blockChainService;

    @Autowired
    protected DistributedStorageService distributedStorageService;

    private static final String MINIO_ACCESS_KEY = "minioadmin";
    private static final String MINIO_SECRET_KEY = "minioadmin";
    private static final int MINIO_PORT = 9000;

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("record_platform_test")
            .withUsername("test")
            .withPassword("test")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("init-test-db.sql"),
                    "/docker-entrypoint-initdb.d/init-test-db.sql"
            )
            .withReuse(true);

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .withReuse(true);

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.12-management-alpine"))
            .withReuse(true);

    @Container
    static GenericContainer<?> minio = new GenericContainer<>(DockerImageName.parse("minio/minio:latest"))
            .withExposedPorts(MINIO_PORT)
            .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
            .withCommand("server /data")
            .waitingFor(Wait.forHttp("/minio/health/ready")
                    .forPort(MINIO_PORT)
                    .withStartupTimeout(Duration.ofSeconds(60)))
            .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);

        registry.add("storage.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(MINIO_PORT));
        registry.add("storage.accessKey", () -> MINIO_ACCESS_KEY);
        registry.add("storage.secretKey", () -> MINIO_SECRET_KEY);
        registry.add("storage.region", () -> "us-east-1");

        // S3 configuration for S3ClientConfiguration and ImageServiceImpl
        registry.add("s3.endpoint", () -> "http://" + minio.getHost() + ":" + minio.getMappedPort(MINIO_PORT));
        registry.add("s3.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("s3.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("s3.bucket-name", () -> "record-platform-images");
    }

    protected static String getMinioEndpoint() {
        return "http://" + minio.getHost() + ":" + minio.getMappedPort(MINIO_PORT);
    }

    protected static String getMinioAccessKey() {
        return MINIO_ACCESS_KEY;
    }

    protected static String getMinioSecretKey() {
        return MINIO_SECRET_KEY;
    }
}
