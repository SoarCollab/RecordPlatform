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
import cn.flying.controller.MessageController;
import cn.flying.controller.PermissionController;
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
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.AccountService;
import cn.flying.service.AnnouncementService;
import cn.flying.service.ConversationService;
import cn.flying.service.FileAdminService;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.FriendService;
import cn.flying.service.ImageService;
import cn.flying.service.MessageService;
import cn.flying.service.PermissionService;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    @MockBean
    private AccountService accountService;

    @MockBean
    private ControllerUtils controllerUtils;

    @MockBean
    private AnnouncementService announcementService;

    @MockBean
    private TicketService ticketService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private ConversationService conversationService;

    @MockBean
    private MessageService messageService;

    @MockBean
    private FileAdminService fileAdminService;

    @MockBean
    private ShareAuditService shareAuditService;

    @MockBean
    private FileQueryService fileQueryService;

    @MockBean
    private FileService fileService;

    @MockBean
    private FriendService friendService;

    @MockBean
    private FriendFileShareService friendFileShareService;

    @MockBean
    private ImageService imageService;

    @MockBean
    private SysPermissionMapper sysPermissionMapper;

    @MockBean
    private SysRolePermissionMapper sysRolePermissionMapper;

    @MockBean
    private PermissionService permissionService;

    @MockBean
    private FileMapper fileMapper;

    @MockBean
    private SseEmitterManager sseEmitterManager;

    @MockBean
    private SysAuditService sysAuditService;

    @MockBean
    private SystemMonitorService systemMonitorService;

    @MockBean
    private FileUploadService fileUploadService;

    @MockBean
    private RedissonClient redissonClient;

    /**
     * 调用 `/v3/api-docs` 并将结果写入 `target/openapi/openapi.json`。
     *
     * @throws Exception 请求或文件写入失败时抛出
     */
    @Test
    void shouldExportOpenApiDocument() throws Exception {
        String openApiContent = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        JsonNode rootNode = objectMapper.readTree(openApiContent);
        assertThat(rootNode.path("openapi").asText()).isNotBlank();
        assertThat(rootNode.path("paths").has("/api/v1/files")).isTrue();

        JsonNode normalizedNode = normalizeOpenApiDocument(rootNode);
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
        if (node.isObject()) {
            ObjectNode sortedNode = objectMapper.createObjectNode();
            List<String> fieldNames = new ArrayList<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            Collections.sort(fieldNames);
            for (String fieldName : fieldNames) {
                sortedNode.set(fieldName, normalizeOpenApiDocument(node.get(fieldName)));
            }
            return sortedNode;
        }
        if (node.isArray()) {
            ArrayNode sortedArrayNode = objectMapper.createArrayNode();
            for (JsonNode childNode : node) {
                sortedArrayNode.add(normalizeOpenApiDocument(childNode));
            }
            return sortedArrayNode;
        }
        return node;
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
            MessageController.class,
            PermissionController.class,
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
