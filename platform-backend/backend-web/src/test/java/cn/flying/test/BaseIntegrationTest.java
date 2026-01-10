package cn.flying.test;

import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.constant.Result;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.config.MockDubboServicesConfig;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.annotation.DirtiesContext;
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
import org.mockito.Mockito;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;

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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Autowired
    private MinioClient s3Client;

    @Value("${s3.bucket-name}")
    private String s3BucketName;

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
        ensureMySqlTestUserCanReadPerformanceSchema();

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

    /**
     * 确保测试数据库用户具备读取 performance_schema 的权限，避免 Flyway 在检测 foreign_key_checks 时触发权限错误。
     * <p>
     * GitHub Actions / Testcontainers 环境中，Flyway 可能会通过 performance_schema.user_variables_by_thread 读取变量值；
     * 若测试用户缺少相应 SELECT 权限，将导致 Spring 容器启动失败并引发大量级联用例报错。
     * </p>
     */
    private static void ensureMySqlTestUserCanReadPerformanceSchema() {
        if (!mysql.isRunning()) {
            return;
        }

        try {
            mysql.execInContainer(
                    "bash",
                    "-lc",
                    """
                    mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
                    CREATE USER IF NOT EXISTS 'test'@'%' IDENTIFIED BY 'test';
                    GRANT SELECT ON performance_schema.* TO 'test'@'%';
                    GRANT SELECT ON performance_schema.user_variables_by_thread TO 'test'@'%';
                    FLUSH PRIVILEGES;
                    "
                    """
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to grant MySQL test user performance_schema privileges", e);
        }
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

    /**
     * 为集成测试提供 FileRemoteClient 的默认行为。
     * <p>
     * CI 环境不会连接真实的区块链/存储 Dubbo 服务，本项目通过 @MockBean 注入 FileRemoteClient。
     * 这里为常用的“分享/取消分享”链路提供成功返回，避免因未 stub 导致返回 null 触发 NPE。
     * 业务侧仍然以区块链调用为核心能力：生产环境不做降级，失败应直接返回错误。
     * </p>
     */
    @BeforeEach
    void stubFileRemoteClientDefaults() {
        Mockito.lenient()
                .when(fileRemoteClient.shareFiles(any()))
                .thenAnswer(invocation -> Result.success(generateTestShareCode()));

        Mockito.lenient()
                .when(fileRemoteClient.cancelShare(any()))
                .thenReturn(Result.success(true));

        ensureS3BucketExists();
    }

    /**
     * 生成测试用的分享码（长度 6，满足 file_share.share_code 唯一约束）。
     *
     * @return 分享码
     */
    private String generateTestShareCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    /**
     * 确保测试环境下 MinIO 的 S3 bucket 已创建，避免图片上传等集成测试因 NoSuchBucket 失败。
     */
    private void ensureS3BucketExists() {
        try {
            boolean exists = s3Client.bucketExists(
                    BucketExistsArgs.builder().bucket(s3BucketName).build()
            );
            if (!exists) {
                s3Client.makeBucket(MakeBucketArgs.builder().bucket(s3BucketName).build());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to ensure S3 bucket exists: " + s3BucketName, e);
        }
    }
}
