package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.Result;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.config.IdSecurityConfiguration;
import cn.flying.dao.vo.file.ProofSigningKeyVO;
import cn.flying.dao.vo.file.ProofStatusVO;
import cn.flying.dao.vo.file.RevokeProofRequest;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.proof.ProofBundleService;
import cn.flying.service.proof.signed.ProofArchive;
import cn.flying.service.proof.signed.SignedProofArchiveService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签名 proof ZIP、revoke 与公开查询控制器合同测试。
 */
@ExtendWith(MockitoExtension.class)
class SignedProofControllerTest {

    private static final Long USER_ID = 42L;
    private static final Long FILE_ID = 11L;
    private static final Long LEAF_ID = 901L;
    private static final String PROOF_ID = "rp-proof-" + "a".repeat(64);

    @Mock
    private FileQueryService fileQueryService;
    @Mock
    private FileService fileService;
    @Mock
    private ShareAuditService shareAuditService;
    @Mock
    private ProofBundleService proofBundleService;
    @Mock
    private SignedProofArchiveService signedProofArchiveService;

    private FileController fileController;
    private PublicProofController publicProofController;
    private SecureIdCodec originalSecureIdCodec;

    /**
     * 初始化外部 ID 编码器和两个被测控制器。
     */
    @BeforeEach
    void setUp() {
        originalSecureIdCodec = (SecureIdCodec) ReflectionTestUtils.getField(
                IdUtils.class, "secureIdCodec");
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234"));
        fileController = new FileController(
                fileQueryService,
                fileService,
                shareAuditService,
                proofBundleService,
                signedProofArchiveService);
        publicProofController = new PublicProofController(signedProofArchiveService);
    }

    /**
     * 恢复全局外部 ID 编码器，避免静态测试状态污染其他控制器用例。
     */
    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", originalSecureIdCodec);
    }

    /**
     * 验证文件 ZIP 端点转换外部 ID，并返回安全附件名、manifest header 和可读取 ZIP 流。
     */
    @Test
    void shouldStreamSignedProofArchiveWithSafeHeaders() throws Exception {
        String externalFileId = IdUtils.toExternalId(FILE_ID);
        ProofArchive archive = archive();
        when(signedProofArchiveService.exportByFileId(USER_ID, FILE_ID)).thenReturn(archive);

        ResponseEntity<StreamingResponseBody> response =
                fileController.exportSignedProofArchiveByFile(USER_ID, externalFileId);

        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/zip");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", archive.fileName());
        assertThat(response.getHeaders().getFirst("X-Proof-Manifest-Hash"))
                .isEqualTo(archive.manifestHash());
        assertThat(response.getHeaders().getCacheControl())
                .contains("no-store", "must-revalidate");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
            while (zip.getNextEntry() != null) {
                entries++;
            }
        }
        assertThat(entries).isEqualTo(ProofArchive.ENTRY_ORDER.size());
        verify(signedProofArchiveService).exportByFileId(USER_ID, FILE_ID);
    }

    /**
     * 验证叶子 ZIP 与 revoke 端点都只向服务传递解码后的内部叶子 ID。
     */
    @Test
    void shouldDelegateLeafArchiveAndIdempotentRevoke() {
        String externalLeafId = IdUtils.toExternalId(LEAF_ID);
        ProofArchive archive = archive();
        ProofStatusVO status = status("REVOKED", 2L);
        when(signedProofArchiveService.exportByLeafId(USER_ID, LEAF_ID)).thenReturn(archive);
        when(signedProofArchiveService.revokeByLeafId(USER_ID, LEAF_ID, "owner request"))
                .thenReturn(status);

        ResponseEntity<StreamingResponseBody> archiveResponse =
                fileController.exportSignedProofArchiveByLeaf(USER_ID, externalLeafId);
        Result<ProofStatusVO> revokeResponse = fileController.revokeSignedProof(
                USER_ID,
                externalLeafId,
                new RevokeProofRequest("owner request"));

        assertThat(archiveResponse.getBody()).isNotNull();
        assertThat(revokeResponse.getData()).isEqualTo(status);
        verify(signedProofArchiveService).exportByLeafId(USER_ID, LEAF_ID);
        verify(signedProofArchiveService).revokeByLeafId(USER_ID, LEAF_ID, "owner request");
    }

    /**
     * 验证两个签名 ZIP 入口共享同一用户维度限流桶，不能通过切换路径绕过。
     */
    @Test
    void shouldShareRateLimitPolicyAcrossSignedProofArchiveEndpoints() throws Exception {
        RateLimit byFile = FileController.class
                .getMethod("exportSignedProofArchiveByFile", Long.class, String.class)
                .getAnnotation(RateLimit.class);
        RateLimit byLeaf = FileController.class
                .getMethod("exportSignedProofArchiveByLeaf", Long.class, String.class)
                .getAnnotation(RateLimit.class);

        assertThat(byFile).isNotNull();
        assertThat(byLeaf).isNotNull();
        assertThat(byFile.key()).isEqualTo("proof:archive");
        assertThat(byLeaf.key()).isEqualTo(byFile.key());
        assertThat(byFile.type()).isEqualTo(RateLimit.LimitType.USER);
        assertThat(byLeaf.type()).isEqualTo(byFile.type());
        assertThat(byFile.limit()).isEqualTo(10);
        assertThat(byLeaf.limit()).isEqualTo(byFile.limit());
        assertThat(byFile.adminLimit()).isEqualTo(30);
        assertThat(byLeaf.adminLimit()).isEqualTo(byFile.adminLimit());
        assertThat(byFile.monitorLimit()).isEqualTo(30);
        assertThat(byLeaf.monitorLimit()).isEqualTo(byFile.monitorLimit());
        assertThat(byFile.period()).isEqualTo(60);
        assertThat(byLeaf.period()).isEqualTo(byFile.period());
    }

    /**
     * 验证撤销证明使用独立用户限流桶，避免枚举或重复写请求绕过导出限流。
     */
    @Test
    void shouldRateLimitSignedProofRevocation() throws Exception {
        RateLimit revoke = FileController.class
                .getMethod("revokeSignedProof", Long.class, String.class, RevokeProofRequest.class)
                .getAnnotation(RateLimit.class);

        assertThat(revoke).isNotNull();
        assertThat(revoke.key()).isEqualTo("proof:revoke");
        assertThat(revoke.type()).isEqualTo(RateLimit.LimitType.USER);
        assertThat(revoke.limit()).isEqualTo(10);
        assertThat(revoke.adminLimit()).isEqualTo(30);
        assertThat(revoke.monitorLimit()).isEqualTo(30);
        assertThat(revoke.period()).isEqualTo(60);
    }

    /**
     * 验证公开状态不缓存、版本化公钥可短期缓存且响应仅含公开 DTO。
     */
    @Test
    void shouldReturnPublicStatusAndVersionedSigningKey() {
        ProofStatusVO status = status("ACTIVE", 1L);
        ProofSigningKeyVO key = new ProofSigningKeyVO(
                "proof-key-main", 1, "EdDSA", "spki", "sha256:" + "f".repeat(64));
        when(signedProofArchiveService.getPublicStatus(PROOF_ID)).thenReturn(status);
        when(signedProofArchiveService.getPublicSigningKey("proof-key-main", 1)).thenReturn(key);

        ResponseEntity<Result<ProofStatusVO>> statusResponse =
                publicProofController.getProofStatus(PROOF_ID);
        ResponseEntity<Result<ProofSigningKeyVO>> keyResponse =
                publicProofController.getProofSigningKey("proof-key-main", 1);

        assertThat(statusResponse.getBody().getData()).isEqualTo(status);
        assertThat(statusResponse.getHeaders().getCacheControl()).contains("no-store");
        assertThat(keyResponse.getBody().getData()).isEqualTo(key);
        assertThat(keyResponse.getHeaders().getCacheControl()).contains("max-age=300", "immutable");
    }

    /**
     * 验证公开状态与公钥端点共享 IP 限流桶，并保留匿名查询审计记录。
     */
    @Test
    void shouldRateLimitAndAuditPublicVerificationEndpoints() throws Exception {
        var statusMethod = PublicProofController.class
                .getMethod("getProofStatus", String.class);
        var keyMethod = PublicProofController.class
                .getMethod("getProofSigningKey", String.class, Integer.class);
        RateLimit statusRateLimit = statusMethod.getAnnotation(RateLimit.class);
        RateLimit keyRateLimit = keyMethod.getAnnotation(RateLimit.class);

        assertThat(statusRateLimit).isNotNull();
        assertThat(keyRateLimit).isNotNull();
        assertThat(statusRateLimit.key()).isEqualTo("public:proof-verification");
        assertThat(keyRateLimit.key()).isEqualTo(statusRateLimit.key());
        assertThat(statusRateLimit.type()).isEqualTo(RateLimit.LimitType.IP);
        assertThat(keyRateLimit.type()).isEqualTo(statusRateLimit.type());
        assertThat(statusRateLimit.limit()).isEqualTo(120);
        assertThat(keyRateLimit.limit()).isEqualTo(statusRateLimit.limit());
        assertThat(statusRateLimit.period()).isEqualTo(60);
        assertThat(keyRateLimit.period()).isEqualTo(statusRateLimit.period());
        assertThat(statusMethod.getAnnotation(OperationLog.class)).isNotNull();
        assertThat(keyMethod.getAnnotation(OperationLog.class)).isNotNull();
    }

    /**
     * 验证公开状态响应按运行时 Jackson 配置把状态版本序列化为字符串，避免前端精度丢失。
     */
    @Test
    void shouldSerializeProofStatusVersionAsString() throws Exception {
        long unsafeJavaScriptInteger = 9_007_199_254_740_993L;
        ObjectMapper objectMapper = new IdSecurityConfiguration()
                .objectMapper(new Jackson2ObjectMapperBuilder());

        JsonNode json = objectMapper.readTree(
                objectMapper.writeValueAsBytes(Result.success(status("ACTIVE", unsafeJavaScriptInteger))));

        assertThat(json.path("data").path("statusVersion").isTextual()).isTrue();
        assertThat(json.path("data").path("statusVersion").asText())
                .isEqualTo(Long.toString(unsafeJavaScriptInteger));
    }

    /**
     * 构造固定八条目测试 archive。
     */
    private ProofArchive archive() {
        List<ProofArchive.ArchiveEntry> entries = ProofArchive.ENTRY_ORDER.stream()
                .map(name -> new ProofArchive.ArchiveEntry(
                        name,
                        name.endsWith(".json") ? "application/json" : "text/plain",
                        (name + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .toList();
        return new ProofArchive(
                "record-proof-fileA-1.zip",
                "sha256:" + "b".repeat(64),
                "header.payload.signature",
                entries);
    }

    /**
     * 构造不含任何内部标识的公开状态。
     */
    private ProofStatusVO status(String value, long version) {
        Date time = new Date(1_752_451_200_000L);
        return new ProofStatusVO(
                PROOF_ID,
                value,
                version,
                "ACTIVE",
                "proof-key-main",
                1,
                null,
                time,
                time);
    }
}
