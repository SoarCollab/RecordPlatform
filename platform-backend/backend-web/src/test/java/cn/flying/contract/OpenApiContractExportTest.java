package cn.flying.contract;

import cn.flying.common.util.ControllerUtils;
import cn.flying.common.util.JwtUtils;
import cn.flying.config.SwaggerConfiguration;
import cn.flying.controller.AccountController;
import cn.flying.controller.AdminAnnouncementController;
import cn.flying.controller.AdminTicketController;
import cn.flying.controller.AnnouncementController;
import cn.flying.controller.AuthorizeController;
import cn.flying.controller.ConversationController;
import cn.flying.controller.FileAdminController;
import cn.flying.controller.FileController;
import cn.flying.controller.FileRestController;
import cn.flying.controller.FriendController;
import cn.flying.controller.FriendFileShareController;
import cn.flying.controller.ImageController;
import cn.flying.controller.IntegrityAlertController;
import cn.flying.controller.MessageController;
import cn.flying.controller.PermissionController;
import cn.flying.controller.QuotaAdminController;
import cn.flying.controller.QuotaController;
import cn.flying.controller.RolePermissionController;
import cn.flying.controller.ShareController;
import cn.flying.controller.ShareRestController;
import cn.flying.controller.SseController;
import cn.flying.controller.SysAuditController;
import cn.flying.controller.SystemController;
import cn.flying.controller.TicketController;
import cn.flying.controller.TransactionController;
import cn.flying.controller.UploadSessionController;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.AccountService;
import cn.flying.service.AnnouncementService;
import cn.flying.service.ConversationService;
import cn.flying.service.DownloadBatchMetricsService;
import cn.flying.service.FileAdminService;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.FriendService;
import cn.flying.service.ImageService;
import cn.flying.service.integrity.IntegrityCheckService;
import cn.flying.service.MessageService;
import cn.flying.service.PermissionService;
import cn.flying.service.QuotaRolloutAuditService;
import cn.flying.service.QuotaService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.SysAuditService;
import cn.flying.service.SystemMonitorService;
import cn.flying.service.TicketService;
import cn.flying.service.sse.SseEmitterManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 导出后端 OpenAPI 文档供前端类型生成使用。
 */
@SpringBootTest(
        classes = OpenApiContractExportTest.OpenApiContractTestApplication.class,
        properties = {
                "spring.main.banner-mode=off",
                "logging.config=classpath:logback-test.xml",
                "spring.config.import=",
                "spring.cloud.nacos.config.enabled=false",
                "spring.cloud.nacos.discovery.enabled=false",
                "spring.task.scheduling.enabled=false",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,"
                        + "com.alibaba.druid.spring.boot3.autoconfigure.DruidDataSourceAutoConfigure,"
                        + "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                        + "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
        }
)
@AutoConfigureMockMvc(addFilters = false)
class OpenApiContractExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private ControllerUtils controllerUtils;

    @MockitoBean
    private AnnouncementService announcementService;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private JwtUtils jwtUtils;

    @MockitoBean
    private ConversationService conversationService;

    @MockitoBean
    private MessageService messageService;

    @MockitoBean
    private FileAdminService fileAdminService;

    @MockitoBean
    private ShareAuditService shareAuditService;

    @MockitoBean
    private FileQueryService fileQueryService;

    @MockitoBean
    private FileService fileService;

    @MockitoBean
    private DownloadBatchMetricsService downloadBatchMetricsService;

    @MockitoBean
    private FriendService friendService;

    @MockitoBean
    private FriendFileShareService friendFileShareService;

    @MockitoBean
    private ImageService imageService;

    @MockitoBean
    private IntegrityCheckService integrityCheckService;

    @MockitoBean
    private IntegrityAlertMapper integrityAlertMapper;

    @MockitoBean
    private SysPermissionMapper sysPermissionMapper;

    @MockitoBean
    private SysRolePermissionMapper sysRolePermissionMapper;

    @MockitoBean
    private PermissionService permissionService;

    @MockitoBean
    private QuotaService quotaService;

    @MockitoBean
    private QuotaRolloutAuditService quotaRolloutAuditService;

    @MockitoBean
    private FileMapper fileMapper;

    @MockitoBean
    private SseEmitterManager sseEmitterManager;

    @MockitoBean
    private SysAuditService sysAuditService;

    @MockitoBean
    private SystemMonitorService systemMonitorService;

    @MockitoBean
    private FileUploadService fileUploadService;

    @MockitoBean
    private RedissonClient redissonClient;

    /**
     * 调用 `/v3/api-docs` 并将结果写入 `target/openapi/openapi.json`。
     *
     * @throws Exception 请求或文件写入失败时抛出
     */
    @Test
    void shouldExportOpenApiDocument() throws Exception {
        JsonNode normalizedNode = fetchAndNormalizeOpenApiDocument();
        writeOpenApiArtifact(normalizedNode);
    }

    /**
     * 验证在同一测试上下文中，规范化 OpenAPI 文档的哈希值保持稳定。
     *
     * @throws Exception 请求或哈希计算失败时抛出
     */
    @Test
    void shouldGenerateDeterministicCanonicalOpenApiHash() throws Exception {
        JsonNode firstNormalizedNode = fetchAndNormalizeOpenApiDocument();
        JsonNode secondNormalizedNode = fetchAndNormalizeOpenApiDocument();

        String firstHash = sha256Hex(canonicalJson(firstNormalizedNode));
        String secondHash = sha256Hex(canonicalJson(secondNormalizedNode));

        assertThat(firstHash).isEqualTo(secondHash);
    }

    /**
     * 拉取并规范化 OpenAPI 文档，用于导出与一致性校验。
     *
     * @return 规范化后的 OpenAPI 节点
     * @throws Exception 请求或 JSON 解析失败时抛出
     */
    private JsonNode fetchAndNormalizeOpenApiDocument() throws Exception {
        String openApiContent = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode rootNode = objectMapper.readTree(openApiContent);
        assertThat(rootNode.path("openapi").asText()).isNotBlank();
        assertThat(rootNode.path("paths").has("/api/v1/files")).isTrue();
        assertThat(rootNode.path("paths").has("/api/v1/admin/quota/rollout/audits")).isTrue();
        assertThat(rootNode.path("paths").has("/api/v1/admin/integrity-alerts")).isTrue();

        return normalizeOpenApiDocument(rootNode);
    }

    /**
     * 将规范化后的 OpenAPI 文档写入构建产物目录。
     *
     * @param normalizedNode 规范化后的 OpenAPI 节点
     * @throws Exception 文件写入失败时抛出
     */
    private void writeOpenApiArtifact(JsonNode normalizedNode) throws Exception {
        Path outputPath = Path.of("target", "openapi", "openapi.json");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(
                outputPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(normalizedNode),
                StandardCharsets.UTF_8
        );

        assertThat(Files.exists(outputPath)).isTrue();
    }

    /**
     * 递归稳定化 OpenAPI 文档键顺序，避免 CI 生成结果受 Map 遍历顺序影响。
     *
     * @param node 原始 OpenAPI 节点
     * @return 键顺序稳定的 OpenAPI 节点
     */
    private JsonNode normalizeOpenApiDocument(JsonNode node) {
        return normalizeOpenApiDocument(node, "");
    }

    /**
     * 递归稳定化 OpenAPI 文档键顺序，并对已知无序数组执行可重复排序。
     *
     * @param node 当前 JSON 节点
     * @param fieldName 当前节点在父级对象中的字段名
     * @return 规范化后的 JSON 节点
     */
    private JsonNode normalizeOpenApiDocument(JsonNode node, String fieldName) {
        if (node.isObject()) {
            ObjectNode sortedNode = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            for (String childFieldName : fieldNames) {
                sortedNode.set(childFieldName, normalizeOpenApiDocument(node.get(childFieldName), childFieldName));
            }
            return sortedNode;
        }
        if (node.isArray()) {
            List<JsonNode> normalizedChildren = new ArrayList<>();
            for (JsonNode childNode : node) {
                normalizedChildren.add(normalizeOpenApiDocument(childNode, fieldName));
            }
            sortKnownUnorderedArrays(fieldName, normalizedChildren);

            ArrayNode sortedArrayNode = objectMapper.createArrayNode();
            normalizedChildren.forEach(sortedArrayNode::add);
            return sortedArrayNode;
        }
        return node;
    }

    /**
     * 对已知无序数组进行稳定排序，避免文档导出产生伪差异。
     *
     * @param fieldName 数组字段名
     * @param normalizedChildren 已规范化的数组子节点
     */
    private void sortKnownUnorderedArrays(String fieldName, List<JsonNode> normalizedChildren) {
        if ("required".equals(fieldName) && normalizedChildren.stream().allMatch(JsonNode::isTextual)) {
            normalizedChildren.sort(Comparator.comparing(JsonNode::asText));
            return;
        }

        if (("tags".equals(fieldName) || "parameters".equals(fieldName))
                && normalizedChildren.stream().allMatch(JsonNode::isObject)) {
            normalizedChildren.sort(
                    Comparator.comparing((JsonNode node) -> node.path("name").asText(""))
                            .thenComparing(node -> node.path("in").asText(""))
                            .thenComparing(this::canonicalJson)
            );
        }
    }

    /**
     * 将 JSON 节点序列化为无格式化 canonical 字符串，用于哈希比较。
     *
     * @param node JSON 节点
     * @return canonical JSON 字符串
     */
    private String canonicalJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalStateException("序列化 canonical OpenAPI JSON 失败", exception);
        }
    }

    /**
     * 计算输入内容的 SHA-256 十六进制摘要。
     *
     * @param content 待计算内容
     * @return SHA-256 十六进制字符串
     */
    private String sha256Hex(String content) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digest = messageDigest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 未提供 SHA-256 算法实现", exception);
        }
    }

    /**
     * OpenAPI 契约导出测试专用应用配置，仅加载 Controller 与 Swagger 配置。
     */
    @SpringBootApplication
    @Import({
            SwaggerConfiguration.class,
            AccountController.class,
            AdminAnnouncementController.class,
            AdminTicketController.class,
            AnnouncementController.class,
            AuthorizeController.class,
            ConversationController.class,
            FileAdminController.class,
            FileController.class,
            FileRestController.class,
            FriendController.class,
            FriendFileShareController.class,
            ImageController.class,
            IntegrityAlertController.class,
            MessageController.class,
            PermissionController.class,
            QuotaAdminController.class,
            QuotaController.class,
            RolePermissionController.class,
            ShareController.class,
            ShareRestController.class,
            SseController.class,
            SysAuditController.class,
            SystemController.class,
            TicketController.class,
            TransactionController.class,
            UploadSessionController.class
    })
    static class OpenApiContractTestApplication {
    }
}
