package cn.flying.contract;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.util.ControllerUtils;
import cn.flying.common.util.JwtUtils;
import cn.flying.config.SwaggerConfiguration;
import cn.flying.controller.AccountController;
import cn.flying.controller.AdminAnnouncementController;
import cn.flying.controller.AdminTicketController;
import cn.flying.controller.AnnouncementController;
import cn.flying.controller.AuthorizeController;
import cn.flying.controller.AttestationBatchAdminController;
import cn.flying.controller.ConversationController;
import cn.flying.controller.CryptoAgilityAdminController;
import cn.flying.controller.FileAdminController;
import cn.flying.controller.FileController;
import cn.flying.controller.FileRestController;
import cn.flying.controller.FriendController;
import cn.flying.controller.FriendFileShareController;
import cn.flying.controller.ImageController;
import cn.flying.controller.IntegrityAlertController;
import cn.flying.controller.KeyRotationAdminController;
import cn.flying.controller.MessageController;
import cn.flying.controller.ManifestBackfillAdminController;
import cn.flying.controller.PermissionController;
import cn.flying.controller.QuotaAdminController;
import cn.flying.controller.QuotaController;
import cn.flying.controller.PublicProofController;
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
import cn.flying.service.attestation.AttestationBatchProductionService;
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
import cn.flying.service.key.rotation.KeyRotationPolicyService;
import cn.flying.service.key.rotation.KeyRotationRunCreationService;
import cn.flying.service.key.rotation.KeyRotationRunService;
import cn.flying.service.key.CryptoSuitePolicyService;
import cn.flying.service.key.CryptoSuiteRegistry;
import cn.flying.service.key.KeyWrappingProviderRegistry;
import cn.flying.service.key.FileKeyGrantService;
import cn.flying.service.key.TenantCryptoPolicyService;
import cn.flying.service.manifest.backfill.ManifestBackfillRunService;
import cn.flying.service.manifest.backfill.ManifestGovernanceStatusService;
import cn.flying.service.manifest.backfill.ManifestReferenceCensusService;
import cn.flying.service.manifest.backfill.ManifestReferenceSweepService;
import cn.flying.service.MessageService;
import cn.flying.service.PermissionService;
import cn.flying.service.QuotaRolloutAuditService;
import cn.flying.service.QuotaService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.SysAuditService;
import cn.flying.service.SystemMonitorService;
import cn.flying.service.TicketService;
import cn.flying.service.proof.ProofBundleService;
import cn.flying.service.proof.signed.SignedProofArchiveService;
import cn.flying.service.proof.signed.ProofSigningProviderRegistry;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.auth.AuthorizationStateService;
import cn.flying.security.TrustedClientIpResolver;
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
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

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
    private AuthorizationStateService authorizationStateService;

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
    private TrustedClientIpResolver trustedClientIpResolver;

    @MockitoBean
    private FileQueryService fileQueryService;

    @MockitoBean
    private FileService fileService;

    @MockitoBean
    private FileKeyGrantService fileKeyGrantService;

    @MockitoBean
    private ProofBundleService proofBundleService;

    @MockitoBean
    private SignedProofArchiveService signedProofArchiveService;

    @MockitoBean
    private AttestationBatchProductionService attestationBatchProductionService;

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
    private KeyRotationPolicyService keyRotationPolicyService;

    @MockitoBean
    private KeyRotationRunCreationService keyRotationRunCreationService;

    @MockitoBean
    private KeyRotationRunService keyRotationRunService;

    @MockitoBean
    private TenantCryptoPolicyService tenantCryptoPolicyService;

    @MockitoBean
    private CryptoSuitePolicyService cryptoSuitePolicyService;

    @MockitoBean
    private CryptoSuiteRegistry cryptoSuiteRegistry;

    @MockitoBean
    private KeyWrappingProviderRegistry keyWrappingProviderRegistry;

    @MockitoBean
    private ProofSigningProviderRegistry proofSigningProviderRegistry;

    @MockitoBean
    private ManifestGovernanceStatusService manifestGovernanceStatusService;

    @MockitoBean
    private ManifestBackfillRunService manifestBackfillRunService;

    @MockitoBean
    private ManifestReferenceCensusService manifestReferenceCensusService;

    @MockitoBean
    private ManifestReferenceSweepService manifestReferenceSweepService;

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
     * 验证 manifest 治理入口统一受管理员权限和操作审计保护。
     */
    @Test
    void shouldProtectAndAuditEveryManifestGovernanceOperation() {
        PreAuthorize authorization = ManifestBackfillAdminController.class.getAnnotation(PreAuthorize.class);
        assertThat(authorization).isNotNull();
        assertThat(authorization.value()).isEqualTo("isAdmin()");

        List<Method> publicOperations = java.util.Arrays.stream(
                        ManifestBackfillAdminController.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertThat(publicOperations).isNotEmpty();
        assertThat(publicOperations)
                .allSatisfy(method -> assertThat(method.getAnnotation(OperationLog.class))
                        .as(method.getName() + " must be audited")
                        .isNotNull());
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
        assertThat(rootNode.path("paths").has("/api/v1/admin/manifest-backfill-runs")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/manifest-backfill-runs/{runId}/items")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/manifest-backfill-runs/reference-census")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/attestation-batches/production/trigger")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/attestation-batches/production/status")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/key-rotation/policy")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/key-rotation/runs")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/key-rotation/runs/{runId}/items")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/crypto-agility/policy")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/admin/crypto-agility/diagnostics")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/files/{id}/proof-bundle.zip")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/files/attestation-leaves/{leafId}/proof-bundle.zip")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/public/proofs/{proofId}/status")).isTrue();
        assertThat(rootNode.path("paths").has(
                "/api/v1/public/proof-keys/{keyId}/versions/{keyVersion}")).isTrue();
        JsonNode accountSchema = rootNode.path("components").path("schemas").path("AccountVO");
        assertThat(accountSchema.path("properties").has("scope")).isTrue();
        assertThat(accountSchema.path("required")).anySatisfy(value ->
                assertThat(value.asText()).isEqualTo("scope"));
        assertThat(accountSchema.path("properties").path("scope").path("enum"))
                .extracting(JsonNode::asText)
                .containsExactly("tenant", "platform");
        assertThat(accountSchema.path("properties").has("status")).isTrue();
        assertPublicOperation(
                rootNode,
                "/api/v1/shares/{shareCode}/info",
                "#/components/schemas/ResultShareInfoVO",
                "shareCode");
        assertPublicOperation(
                rootNode,
                "/api/v1/shares/{shareCode}/files",
                "#/components/schemas/ResultListShareFileVO",
                "shareCode");
        assertPublicOperation(
                rootNode,
                "/api/v1/public/shares/{shareCode}/files/{fileHash}/chunks",
                "#/components/schemas/ResultListByte[]",
                "shareCode",
                "fileHash");
        assertPublicOperation(
                rootNode,
                "/api/v1/public/shares/{shareCode}/files/{fileHash}/decrypt-info",
                "#/components/schemas/ResultFileDecryptInfoVO",
                "shareCode",
                "fileHash");
        assertPublicOperation(
                rootNode,
                "/api/v1/public/shares/{shareCode}/files/{fileHash}/download-metadata",
                "#/components/schemas/ResultFileDownloadMetadataVO",
                "shareCode",
                "fileHash");
        assertAnonymousPostOperation(
                rootNode,
                "/api/v1/public/key-grants/consume",
                "#/components/schemas/DownloadKeyGrantConsumeRequestVO",
                "#/components/schemas/ResultDownloadKeyMaterialVO");
        assertProtectedOperation(rootNode, "/api/v1/shares", "post");
        assertProtectedOperation(rootNode, "/api/v1/shares/{shareCode}", "patch");
        assertProtectedOperation(rootNode, "/api/v1/shares/{shareCode}/files/save", "post");
        assertProtectedOperation(
                rootNode,
                "/api/v1/shares/{shareCode}/files/{fileHash}/chunks",
                "get");
        assertProtectedOperation(
                rootNode,
                "/api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info",
                "get");
        assertProtectedOperation(
                rootNode,
                "/api/v1/shares/{shareCode}/files/{fileHash}/download-metadata",
                "get");
        assertProtectedOperation(rootNode, "/api/v1/files/key-grants/consume", "post");
        assertSignedProofArchiveResponseContract(
                rootNode, "/api/v1/files/{id}/proof-bundle.zip");
        assertSignedProofArchiveResponseContract(
                rootNode, "/api/v1/files/attestation-leaves/{leafId}/proof-bundle.zip");
        JsonNode integrityAlertSchema = rootNode.path("components").path("schemas").path("IntegrityAlertVO");
        assertThat(integrityAlertSchema.path("properties").has("severity")).isTrue();
        assertThat(integrityAlertSchema.path("properties").has("evidence")).isTrue();
        JsonNode downloadMetadataSchema = rootNode.path("components").path("schemas")
                .path("FileDownloadMetadataVO");
        assertThat(downloadMetadataSchema.isMissingNode()).isFalse();
        assertThat(downloadMetadataSchema.path("properties").has("encryption")).isTrue();
        assertThat(downloadMetadataSchema.path("properties").has("canonicalManifestJson")).isTrue();
        assertThat(downloadMetadataSchema.path("properties").has("keyGrant")).isTrue();
        assertThat(downloadMetadataSchema.path("properties").has("accessIdentity")).isTrue();
        assertThat(downloadMetadataSchema.path("required").toString())
                .contains("\"accessIdentity\"");
        assertThat(downloadMetadataSchema.path("properties").path("canonicalManifestJson")
                .path("type").asText()).isEqualTo("string");
        JsonNode downloadEncryptionSchema = rootNode.path("components").path("schemas")
                .path("FileDownloadEncryptionVO");
        assertThat(downloadEncryptionSchema.path("properties").has("formatVersion")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("algorithmSuite")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("fileNonce")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("framePlainSize")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("keyDerivation")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("nonceDerivation")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("aadSchema")).isTrue();
        assertThat(downloadEncryptionSchema.path("properties").has("tagSize")).isTrue();
        JsonNode downloadPartSchema = rootNode.path("components").path("schemas")
                .path("FileDownloadPartVO");
        assertThat(downloadPartSchema.path("properties").has("plainSize")).isTrue();
        assertThat(downloadPartSchema.path("properties").has("frameCount")).isTrue();
        JsonNode downloadAccessIdentitySchema = rootNode.path("components").path("schemas")
                .path("DownloadAccessIdentityVO");
        assertThat(downloadAccessIdentitySchema.isMissingNode()).isFalse();
        assertThat(downloadAccessIdentitySchema.path("properties").has("accessKind")).isTrue();
        assertThat(downloadAccessIdentitySchema.path("properties").has("identityHash")).isTrue();
        assertThat(downloadAccessIdentitySchema.path("properties").has("fileVersion")).isTrue();
        assertThat(downloadAccessIdentitySchema.path("properties").has("manifestHash")).isTrue();
        assertThat(downloadAccessIdentitySchema.path("properties").has("algorithmSuite")).isTrue();
        assertThat(downloadAccessIdentitySchema.path("required").toString())
                .contains(
                        "\"accessKind\"",
                        "\"identityHash\"",
                        "\"fileVersion\"",
                        "\"manifestHash\"",
                        "\"algorithmSuite\"");
        JsonNode downloadMetadataOperation = rootNode.path("paths")
                .path("/api/v1/files/hash/{fileHash}/download-metadata").path("get");
        assertThat(downloadMetadataOperation.isMissingNode()).isFalse();
        assertThat(downloadMetadataOperation.path("responses").path("200")
                .path("content").path("*/*").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ResultFileDownloadMetadataVO");
        List<String> downloadMetadataHeaders = downloadMetadataOperation.path("parameters")
                .findValuesAsText("name");
        assertThat(downloadMetadataHeaders)
                .contains("X-Key-Delivery-Protocol", "X-Download-Session-ID");
        JsonNode grantRequestSchema = rootNode.path("components").path("schemas")
                .path("DownloadKeyGrantConsumeRequestVO");
        assertRequiredFields(grantRequestSchema, "grantReference", "sessionId");
        JsonNode grantSchema = rootNode.path("components").path("schemas").path("DownloadKeyGrantVO");
        assertThat(grantSchema.path("properties").has("reference")).isTrue();
        assertThat(grantSchema.path("properties").has("protocol")).isTrue();
        assertThat(grantSchema.path("properties").has("expiresAt")).isTrue();
        JsonNode directCompletePartSchema = rootNode.path("components").path("schemas")
                .path("DirectUploadCompletePartRequest");
        assertThat(directCompletePartSchema.path("properties").has("eTag")).isTrue();
        assertThat(directCompletePartSchema.path("properties").has("etag")).isFalse();
        assertThat(directCompletePartSchema.path("properties").path("eTag")
                .path("maxLength").asInt()).isEqualTo(255);
        assertThat(directCompletePartSchema.path("properties").path("eTag")
                .path("minLength").asInt()).isEqualTo(1);
        assertThat(directCompletePartSchema.path("properties").path("eTag")
                .path("pattern").asText()).isEqualTo("^[\\x21-\\x7E]{1,255}$");
        assertRequiredFields(directCompletePartSchema, "index", "eTag");
        JsonNode directCompletePartsSchema = rootNode.path("components").path("schemas")
                .path("DirectUploadCompleteRequest").path("properties").path("parts");
        assertThat(directCompletePartsSchema.path("minItems").asInt()).isEqualTo(1);
        assertThat(directCompletePartsSchema.path("maxItems").asInt()).isEqualTo(10_000);
        JsonNode directSessionPartsSchema = rootNode.path("components").path("schemas")
                .path("DirectUploadSessionRequest").path("properties").path("parts");
        assertThat(directSessionPartsSchema.path("minItems").asInt()).isEqualTo(1);
        assertThat(directSessionPartsSchema.path("maxItems").asInt()).isEqualTo(10_000);
        JsonNode directPartRequestSchema = rootNode.path("components").path("schemas")
                .path("DirectUploadPartRequest");
        JsonNode directPartProperties = directPartRequestSchema.path("properties");
        assertThat(directPartProperties.path("plainHash").path("maxLength").asInt()).isEqualTo(71);
        assertThat(directPartProperties.path("cipherHash").path("maxLength").asInt()).isEqualTo(71);
        assertThat(directPartProperties.path("checksumAlgorithm").path("maxLength").asInt()).isEqualTo(16);
        assertRequiredFields(
                directPartRequestSchema, "index", "size", "plainHash", "cipherHash");
        JsonNode directSessionRequestSchema = rootNode.path("components").path("schemas")
                .path("DirectUploadSessionRequest");
        assertRequiredFields(
                directSessionRequestSchema,
                "fileName",
                "fileSize",
                "chunkSize",
                "totalChunks",
                "parts");
        assertThat(directSessionRequestSchema.path("properties").has("contentType")).isTrue();
        assertThat(directSessionRequestSchema.path("properties").path("contentType")
                .path("maxLength").asInt()).isEqualTo(255);
        JsonNode uploadPolicyOperation = rootNode.path("paths")
                .path("/api/v1/upload-sessions/policy").path("get");
        assertThat(uploadPolicyOperation.isMissingNode()).isFalse();
        assertThat(uploadPolicyOperation.path("responses").path("200")
                .path("content").path("*/*").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ResultUploadPolicyVO");
        assertProtectedOperation(rootNode, "/api/v1/upload-sessions/policy", "get");
        JsonNode uploadPolicySchema = rootNode.path("components").path("schemas").path("UploadPolicyVO");
        assertRequiredFields(uploadPolicySchema, "maxFileSizeBytes", "fileTypes");
        JsonNode uploadFileTypePolicySchema = rootNode.path("components").path("schemas")
                .path("UploadFileTypePolicyVO");
        assertRequiredFields(
                uploadFileTypePolicySchema,
                "extension",
                "category",
                "categoryLabel",
                "previewMode",
                "mimeTypes");
        List<String> previewModes = new ArrayList<>();
        uploadFileTypePolicySchema.path("properties").path("previewMode").path("enum")
                .forEach(value -> previewModes.add(value.asText()));
        assertThat(previewModes)
                .containsExactlyInAnyOrder("image", "video", "audio", "pdf", "text", "unsupported");
        assertRequiredFields(
                rootNode.path("components").path("schemas").path("DirectUploadPartUrlVO"),
                "index",
                "size",
                "uploadUrl",
                "expiresAtEpochSeconds",
                "storagePath",
                "plainHash",
                "cipherHash");
        assertRequiredFields(
                rootNode.path("components").path("schemas").path("DirectUploadSessionVO"),
                "clientId",
                "chunkSize",
                "totalChunks",
                "resumed",
                "manifestSchemaId",
                "parts");
        assertRequiredFields(
                rootNode.path("components").path("schemas").path("DirectUploadCompleteVO"),
                "clientId",
                "fileId",
                "fileHash",
                "transactionHash",
                "manifestHash",
                "status");
        JsonNode proofBundleSchema = rootNode.path("components").path("schemas").path("ProofBundleVO");
        assertRequiredFields(
                proofBundleSchema,
                "contractVersion",
                "manifest",
                "file",
                "storage",
                "merkle",
                "chain",
                "issuer",
                "verificationPolicy",
                "verificationGuide");
        assertNullableFields(proofBundleSchema);
        JsonNode fileEvidenceSchema = rootNode.path("components").path("schemas")
                .path("FileEvidence");
        assertThat(fileEvidenceSchema.path("properties").path("fileHash")
                .path("description").asText()).contains("链记录 ID", "不是原文件内容 SHA-256");
        JsonNode storageObjectEvidenceSchema = rootNode.path("components").path("schemas")
                .path("StorageObjectEvidence");
        assertNullableFields(storageObjectEvidenceSchema, "plainSize");
        assertThat(storageObjectEvidenceSchema.path("properties").path("size")
                .path("description").asText()).contains("存储对象", "密文分片长度");
        assertThat(storageObjectEvidenceSchema.path("properties").path("plainSize")
                .path("description").asText()).contains("原始明文分片长度", "旧未加密对象");
        JsonNode chainEvidenceSchema = rootNode.path("components").path("schemas").path("ChainEvidence");
        assertRequiredFields(chainEvidenceSchema, "batchChainFileHash", "contractRegistry");
        assertNullableFields(
                chainEvidenceSchema,
                "batchTransactionHash",
                "fileTransactionHash",
                "batchConfirmationSource");
        JsonNode contractRegistrySchema = rootNode.path("components").path("schemas")
                .path("ContractRegistryEvidence");
        assertRequiredFields(
                contractRegistrySchema,
                "schemaVersion",
                "registryFingerprint",
                "contractName",
                "semanticVersion",
                "chainType",
                "chainId",
                "contractAddress",
                "abiFingerprintAlgorithm",
                "abiSha256",
                "artifactBytecodeSha256",
                "onChainCodeSha256",
                "status",
                "effectiveAt",
                "upgradeStrategy");
        assertNullableFields(
                contractRegistrySchema,
                "groupId",
                "deploymentTransactionHash",
                "deploymentBlockNumber");
        JsonNode productionStatusSchema = rootNode.path("components").path("schemas")
                .path("AttestationBatchProductionStatusVO");
        assertThat(productionStatusSchema.path("properties").has("readyCandidates")).isTrue();
        assertThat(productionStatusSchema.path("properties").has("deadLetterCandidates")).isTrue();
        JsonNode proofStatusSchema = rootNode.path("components").path("schemas").path("ProofStatusVO");
        assertThat(proofStatusSchema.path("properties").path("statusVersion").path("type").asText())
                .isEqualTo("string");
        List<String> currentStatuses = new ArrayList<>();
        proofStatusSchema.path("properties").path("status").path("enum")
                .forEach(value -> currentStatuses.add(value.asText()));
        assertThat(currentStatuses)
                .containsExactly("ACTIVE", "REVOKED", "SUPERSEDED", "INVALID");
        List<String> issuedStatuses = new ArrayList<>();
        proofStatusSchema.path("properties").path("issuedStatus").path("enum")
                .forEach(value -> issuedStatuses.add(value.asText()));
        assertThat(issuedStatuses).containsExactly("ACTIVE", "SUPERSEDED");

        return normalizeOpenApiDocument(rootNode);
    }

    /**
     * 验证匿名分享 GET 在 OpenAPI 中显式覆盖全局 Bearer 要求，且不声明租户请求头。
     *
     * @param rootNode OpenAPI 根节点
     * @param path 匿名分享路径模板
     * @param responseSchemaRef 成功响应 schema 引用
     * @param pathParameters 必需路径参数
     */
    private void assertPublicOperation(JsonNode rootNode,
                                       String path,
                                       String responseSchemaRef,
                                       String... pathParameters) {
        JsonNode operation = rootNode.path("paths").path(path).path("get");

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").isArray()).isTrue();
        assertThat(operation.path("security")).isEmpty();
        assertThat(operation.path("description").asText()).contains("X-Tenant-ID", "owner tenant");

        List<String> allParameterNames = operation.path("parameters").findValuesAsText("name");
        assertThat(allParameterNames).noneMatch(name -> "X-Tenant-ID".equalsIgnoreCase(name));

        List<String> actualPathParameters = new ArrayList<>();
        operation.path("parameters").forEach(parameter -> {
            if ("path".equals(parameter.path("in").asText())) {
                actualPathParameters.add(parameter.path("name").asText());
                assertThat(parameter.path("required").asBoolean()).isTrue();
                assertThat(parameter.path("schema").path("type").asText()).isEqualTo("string");
            }
        });
        assertThat(actualPathParameters).containsExactlyInAnyOrder(pathParameters);
        assertThat(operation.path("responses").path("200").path("content").path("*/*")
                .path("schema").path("$ref").asText()).isEqualTo(responseSchemaRef);
    }

    /**
     * 校验匿名 POST 显式覆盖 Bearer，且 grant 只通过 JSON 请求体传输。
     */
    private void assertAnonymousPostOperation(JsonNode rootNode,
                                              String path,
                                              String requestSchemaRef,
                                              String responseSchemaRef) {
        JsonNode operation = rootNode.path("paths").path(path).path("post");

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("security").isArray()).isTrue();
        assertThat(operation.path("security")).isEmpty();
        assertThat(operation.path("parameters").findValuesAsText("name"))
                .noneMatch(name -> "X-Tenant-ID".equalsIgnoreCase(name))
                .noneMatch(name -> "grantReference".equalsIgnoreCase(name));
        assertThat(operation.path("requestBody").path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo(requestSchemaRef);
        assertThat(operation.path("responses").path("200").path("content").path("*/*")
                .path("schema").path("$ref").asText()).isEqualTo(responseSchemaRef);
    }

    /**
     * 校验分享写入和登录态文件入口继续继承全局 Bearer 合同。
     *
     * @param rootNode OpenAPI 根节点
     * @param path 受保护路径
     * @param method 小写 HTTP 方法
     */
    private void assertProtectedOperation(JsonNode rootNode, String path, String method) {
        JsonNode operation = rootNode.path("paths").path(path).path(method);

        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.has("security")).isFalse();
    }

    /**
     * 校验签名 ZIP operation 的成功响应头和可重试失败响应合同。
     *
     * @param rootNode OpenAPI 根节点
     * @param path ZIP operation 路径
     */
    private void assertSignedProofArchiveResponseContract(JsonNode rootNode, String path) {
        JsonNode responses = rootNode.path("paths").path(path).path("get").path("responses");
        JsonNode successResponse = responses.path("200");
        JsonNode successHeaders = successResponse.path("headers");
        assertThat(successResponse.path("content").has("application/zip")).isTrue();
        assertThat(successHeaders.has("Content-Disposition")).isTrue();
        assertThat(successHeaders.has("Cache-Control")).isTrue();
        assertThat(successHeaders.has("X-Proof-Manifest-Hash")).isTrue();

        JsonNode retryableResponse = responses.path("503");
        assertThat(retryableResponse.path("headers").path("Retry-After")
                .path("schema").path("type").asText()).isEqualTo("integer");
        assertThat(retryableResponse.path("headers").path("Retry-After")
                .path("schema").path("format").asText()).isEqualTo("int32");
        assertThat(retryableResponse.path("content").path("application/json")
                .path("schema").path("$ref").asText()).isEqualTo("#/components/schemas/Result");
    }

    /**
     * 精确校验 schema 的必填字段集合，防止必填性意外扩大或缩小。
     *
     * @param schema 待校验的 OpenAPI schema
     * @param expectedFields 预期必填字段
     */
    private void assertRequiredFields(JsonNode schema, String... expectedFields) {
        List<String> actualFields = new ArrayList<>();
        schema.path("required").forEach(field -> actualFields.add(field.asText()));

        assertThat(actualFields).containsExactlyInAnyOrder(expectedFields);
    }

    /**
     * 精确校验 schema 中显式可空的字段集合，防止生成类型与响应合同漂移。
     *
     * @param schema 待校验的 OpenAPI schema
     * @param expectedFields 预期显式可空字段
     */
    private void assertNullableFields(JsonNode schema, String... expectedFields) {
        List<String> actualFields = new ArrayList<>();
        schema.path("properties").properties().forEach(entry -> {
            if (entry.getValue().path("nullable").asBoolean(false)) {
                actualFields.add(entry.getKey());
            }
        });

        assertThat(actualFields).containsExactlyInAnyOrder(expectedFields);
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
            AttestationBatchAdminController.class,
            AdminAnnouncementController.class,
            AdminTicketController.class,
            AnnouncementController.class,
            AuthorizeController.class,
            ConversationController.class,
            CryptoAgilityAdminController.class,
            FileAdminController.class,
            FileController.class,
            FileRestController.class,
            FriendController.class,
            FriendFileShareController.class,
            ImageController.class,
            IntegrityAlertController.class,
            KeyRotationAdminController.class,
            ManifestBackfillAdminController.class,
            MessageController.class,
            PermissionController.class,
            QuotaAdminController.class,
            QuotaController.class,
            PublicProofController.class,
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
