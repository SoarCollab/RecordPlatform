package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 匿名公开分享的 owner tenant、统一客户端 IP 与审计归属真实 MySQL/Redis 集成测试。
 */
@TestPropertySource(properties = "spring.web.rate-limit.client-ip.trusted-proxies=10.0.0.0/8")
@DisplayName("Public share tenant and audit isolation integration tests")
class PublicShareAuditTenantIsolationIT extends BaseControllerIntegrationTest {

    private static final long OWNER_TENANT_ID = 42L;
    private static final long FORGED_TENANT_ID = 43L;
    private static final String UNTRUSTED_PEER = "198.51.100.44";
    private static final String RATE_LIMIT_PEER = "198.51.100.45";
    private static final String TRUSTED_PEER = "10.0.0.20";
    private static final String TRUSTED_CLIENT = "203.0.113.7";
    private static final String RATE_KEY_PREFIX = "rate:limit:public:share-access:v2:ip:";
    private static final String INFO_AUDIT_URL = "/api/v1/shares/***/info";
    private static final String FILES_AUDIT_URL = "/api/v1/shares/***/files";
    private static final String CHUNKS_AUDIT_URL = "/api/v1/public/shares/***/files/***/chunks";
    private static final String DECRYPT_AUDIT_URL = "/api/v1/public/shares/***/files/***/decrypt-info";
    private static final List<String> PUBLIC_AUDIT_URLS = List.of(
            INFO_AUDIT_URL,
            FILES_AUDIT_URL,
            CHUNKS_AUDIT_URL,
            DECRYPT_AUDIT_URL);

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileShareMapper fileShareMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private long ownerUserId;
    private long fileId;
    private String shareCode;
    private String fileHash;

    /**
     * 清理共享测试状态、创建非零租户公开分享并伪造远端分片响应。
     */
    @BeforeEach
    void setUpPublicShareFixture() {
        clearPublicAuditRows();
        clearPublicRateKeys();

        ownerUserId = IdUtils.nextEntityId();
        fileId = IdUtils.nextEntityId();
        shareCode = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        fileHash = "sha256-public-share-" + UUID.randomUUID().toString().replace("-", "");
        setTestUser(ownerUserId, OWNER_TENANT_ID);
        insertOwnerFixture();
        stubPublicDownload();
    }

    /**
     * 清理本用例创建的数据、审计行、限流键和本地分享缓存。
     */
    @AfterEach
    void tearDownPublicShareFixture() {
        clearPublicRateKeys();
        jdbcTemplate.update("DELETE FROM share_access_log WHERE share_code = ?", shareCode);
        jdbcTemplate.update("DELETE FROM file_share WHERE share_code = ?", shareCode);
        jdbcTemplate.update("DELETE FROM file WHERE id = ?", fileId);
        jdbcTemplate.update("DELETE FROM account WHERE id = ?", ownerUserId);
        clearPublicAuditRows();
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE request_url = ?", "/api/v1/shares/***");
    }

    /**
     * 验证四个匿名入口忽略全部租户头形态，并将业务、审计和限流身份恢复到可信边界。
     */
    @Test
    @DisplayName("should ignore every tenant header form and keep owner/system audit boundaries")
    void shouldIgnoreEveryTenantHeaderFormAndKeepAuditBoundaries() throws Exception {
        Map<String, String> canonicalResponseByPath = new LinkedHashMap<>();

        for (TenantHeaderCase headerCase : tenantHeaderCases()) {
            for (String path : publicPaths()) {
                MockHttpServletRequestBuilder request = get(path)
                        .with(remoteAddress(UNTRUSTED_PEER))
                        .header("X-Forwarded-For", "203.0.113.91")
                        .header("X-Real-IP", "203.0.113.92")
                        .header("User-Agent", "PublicShareIT/1.0");
                headerCase.configure().accept(request);

                MvcResult result = mockMvc.perform(request)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(200))
                        .andReturn();
                String body = result.getResponse().getContentAsString();
                String canonicalBody = canonicalResponseByPath.putIfAbsent(path, body);
                if (canonicalBody != null) {
                    assertThat(body)
                            .as("tenant header case %s must not change %s", headerCase.name(), path)
                            .isEqualTo(canonicalBody);
                }
            }
        }

        awaitShareAccessLogCount(12);
        assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + UNTRUSTED_PEER)).isEqualTo("12");
        assertThat(redisTemplate.keys("rate:limit:public:share-access:*")).containsExactly(
                RATE_KEY_PREFIX + UNTRUSTED_PEER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_count FROM file_share WHERE share_code = ?",
                Integer.class,
                shareCode)).isEqualTo(12);

        List<AuditRow> systemRows = selectPublicOperationRows();
        assertThat(systemRows).hasSize(24).allSatisfy(row -> {
            assertThat(row.tenantId()).isZero();
            assertThat(row.ip()).isEqualTo(UNTRUSTED_PEER);
            assertThat(row.ip()).hasSizeLessThanOrEqualTo(50);
        });
        assertThat(systemRows).extracting(AuditRow::url)
                .containsOnlyElementsOf(PUBLIC_AUDIT_URLS);

        List<ShareAuditRow> shareRows = selectShareAccessRows();
        assertThat(shareRows).hasSize(12).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(OWNER_TENANT_ID);
            assertThat(row.ip()).isEqualTo(UNTRUSTED_PEER);
            assertThat(row.ip()).hasSizeLessThanOrEqualTo(50);
        });
        assertThat(shareRows).filteredOn(row -> row.actionType() == 1).hasSize(6);
        assertThat(shareRows).filteredOn(row -> row.actionType() == 2).hasSize(6);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM share_access_log WHERE share_code = ? AND tenant_id = ?",
                Long.class,
                shareCode,
                FORGED_TENANT_ID)).isZero();
    }

    /**
     * 验证可信代理合法链在系统审计、分享审计和 Redis 桶中使用同一规范化客户端 IP。
     */
    @Test
    @DisplayName("should use one canonical client IP behind a trusted proxy")
    void shouldUseOneCanonicalClientIpBehindTrustedProxy() throws Exception {
        String filesPath = "/api/v1/shares/" + shareCode + "/files";
        String chunksPath = "/api/v1/public/shares/" + shareCode + "/files/" + fileHash + "/chunks";

        for (String path : List.of(filesPath, chunksPath)) {
            mockMvc.perform(get(path)
                            .with(remoteAddress(TRUSTED_PEER))
                            .header("X-Forwarded-For", TRUSTED_CLIENT + ", 10.1.0.8, 10.2.0.9")
                            .header(HEADER_TENANT_ID, FORGED_TENANT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        awaitShareAccessLogCount(2);
        assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + TRUSTED_CLIENT)).isEqualTo("1");
        assertThat(selectPublicOperationRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.tenantId()).isZero();
            assertThat(row.ip()).isEqualTo(TRUSTED_CLIENT);
        });
        assertThat(selectShareAccessRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.tenantId()).isEqualTo(OWNER_TENANT_ID);
            assertThat(row.ip()).isEqualTo(TRUSTED_CLIENT);
        });
    }

    /**
     * 验证分享从公开改为私密后匿名列表立即失败关闭。
     */
    @Test
    @DisplayName("should reject public file list immediately when share becomes private")
    void shouldRejectPublicFileListImmediatelyWhenShareBecomesPrivate() throws Exception {
        String filesPath = "/api/v1/shares/" + shareCode + "/files";
        mockMvc.perform(get(filesPath).with(remoteAddress(UNTRUSTED_PEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(withAuth(patch("/api/v1/shares/{shareCode}", shareCode)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shareCode\":\"" + shareCode + "\",\"shareType\":1}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get(filesPath).with(remoteAddress(UNTRUSTED_PEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_UNAUTHORIZED.getCode()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT share_type FROM file_share WHERE share_code = ?",
                Integer.class,
                shareCode)).isEqualTo(1);
        awaitShareAccessLogCount(1);
    }

    /**
     * 验证一次成功读取后分享自然到期时不会返回此前的公开文件列表。
     */
    @Test
    @DisplayName("should reject public file list after a previously active share expires")
    void shouldRejectPublicFileListAfterPreviouslyActiveShareExpires() throws Exception {
        String filesPath = "/api/v1/shares/" + shareCode + "/files";
        mockMvc.perform(get(filesPath).with(remoteAddress(UNTRUSTED_PEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        jdbcTemplate.update(
                "UPDATE file_share SET status = ?, expire_time = DATE_SUB(NOW(), INTERVAL 1 SECOND) WHERE share_code = ?",
                FileShare.STATUS_ACTIVE,
                shareCode);

        mockMvc.perform(get(filesPath).with(remoteAddress(UNTRUSTED_PEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.SHARE_EXPIRED.getCode()));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM file_share WHERE share_code = ?",
                Integer.class,
                shareCode)).isEqualTo(FileShare.STATUS_EXPIRED);
        awaitShareAccessLogCount(1);
    }

    /**
     * 验证伪造有效租户头也不能把匿名调用升级为受保护的写入或登录态下载权限。
     */
    @Test
    @DisplayName("should keep protected share mutations and private download paths authenticated")
    void shouldKeepProtectedShareMutationsAndPrivateDownloadPathsAuthenticated() throws Exception {
        String tenantHeader = String.valueOf(FORGED_TENANT_ID);
        int originalShareType = jdbcTemplate.queryForObject(
                "SELECT share_type FROM file_share WHERE share_code = ?",
                Integer.class,
                shareCode);

        mockMvc.perform(patch("/api/v1/shares/{shareCode}", shareCode)
                        .header(HEADER_TENANT_ID, tenantHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shareType\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/shares/{shareCode}/files/save", shareCode)
                        .header(HEADER_TENANT_ID, tenantHeader)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sharingFileIdList\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/shares/{shareCode}/files/{fileHash}/chunks", shareCode, fileHash)
                        .header(HEADER_TENANT_ID, tenantHeader))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/shares/{shareCode}/files/{fileHash}/decrypt-info", shareCode, fileHash)
                        .header(HEADER_TENANT_ID, tenantHeader))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT share_type FROM file_share WHERE share_code = ?",
                Integer.class,
                shareCode)).isEqualTo(originalShareType);
        assertThat(selectShareAccessRows()).isEmpty();
    }

    /**
     * 验证两个公开文件入口共享 30 次桶，且第 31 次保持当前 HTTP 200 加业务码 70005 合同。
     */
    @Test
    @DisplayName("should return business rate-limit code on the 31st shared public request")
    void shouldReturnBusinessRateLimitCodeOnThirtyFirstSharedPublicRequest() throws Exception {
        String chunksPath = "/api/v1/public/shares/" + shareCode + "/files/" + fileHash + "/chunks";
        String decryptPath = "/api/v1/public/shares/" + shareCode + "/files/" + fileHash + "/decrypt-info";

        for (int index = 0; index < 30; index++) {
            String path = index % 2 == 0 ? chunksPath : decryptPath;
            mockMvc.perform(get(path).with(remoteAddress(RATE_LIMIT_PEER)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        mockMvc.perform(get(chunksPath).with(remoteAddress(RATE_LIMIT_PEER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultEnum.PERMISSION_LIMIT.getCode()));

        assertThat(redisTemplate.opsForValue().get(RATE_KEY_PREFIX + RATE_LIMIT_PEER)).isEqualTo("30");
        awaitShareAccessLogCount(15);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT access_count FROM file_share WHERE share_code = ?",
                Integer.class,
                shareCode)).isEqualTo(15);
    }

    /**
     * 在 owner tenant 内插入账户、文件和分享，确保跨租户读取只能从分享码开始。
     */
    private void insertOwnerFixture() {
        Account account = new Account();
        account.setId(ownerUserId);
        account.setTenantId(OWNER_TENANT_ID);
        account.setUsername("public_share_owner_" + ownerUserId);
        account.setPassword("$2a$10$PublicShareTenantBoundaryTestPasswordHashValue000000000000");
        account.setEmail("public_share_owner_" + ownerUserId + "@example.test");
        account.setRole("user");
        account.setNickname("Public Share Owner");
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);

        File file = new File()
                .setId(fileId)
                .setTenantId(OWNER_TENANT_ID)
                .setUid(ownerUserId)
                .setFileName("public-share.txt")
                .setFileHash(fileHash)
                .setFileParam("""
                        {"encryptionAlgorithm":"NONE","fileName":"public-share.txt","fileSize":3,"contentType":"text/plain","chunkCount":1}
                        """)
                .setClassification("document")
                .setStatus(1)
                .setDeleted(0)
                .setVersion(1)
                .setIsLatest(1)
                .setVersionGroupId(fileId)
                .setCreateTime(new Date());

        FileShare share = new FileShare()
                .setId(IdUtils.nextEntityId())
                .setTenantId(OWNER_TENANT_ID)
                .setUserId(ownerUserId)
                .setShareCode(shareCode)
                .setShareType(0)
                .setFileHashes("[\"" + fileHash + "\"]")
                .setExpireTime(new Date(System.currentTimeMillis() + 3_600_000L))
                .setAccessCount(0)
                .setStatus(FileShare.STATUS_ACTIVE)
                .setCreateTime(new Date())
                .setUpdateTime(new Date())
                .setDeleted(0);

        TenantContext.runWithTenant(OWNER_TENANT_ID, () -> {
            accountMapper.insert(account);
            fileMapper.insert(file);
            fileShareMapper.insert(share);
        });
    }

    /**
     * 为公开分片下载伪造链上对象引用和真实字节返回，其余数据库授权仍使用生产实现。
     */
    private void stubPublicDownload() {
        when(fileRemoteClient.getFile(String.valueOf(ownerUserId), fileHash)).thenReturn(Result.success(
                new FileDetailVO(
                        String.valueOf(ownerUserId),
                        "public-share.txt",
                        null,
                        "{\"cipher-hash\":\"chunks/public-share-0\"}",
                        fileHash,
                        null,
                        null,
                        3L,
                        "text/plain")));
        when(fileRemoteClient.getFileListByHash(
                List.of("chunks/public-share-0"),
                List.of("cipher-hash")))
                .thenReturn(Result.success(List.of("abc".getBytes(StandardCharsets.UTF_8))));
    }

    /**
     * 返回四个必须忽略调用者租户头的匿名 GET 路径。
     */
    private List<String> publicPaths() {
        return List.of(
                "/api/v1/shares/" + shareCode + "/info",
                "/api/v1/shares/" + shareCode + "/files",
                "/api/v1/public/shares/" + shareCode + "/files/" + fileHash + "/chunks",
                "/api/v1/public/shares/" + shareCode + "/files/" + fileHash + "/decrypt-info");
    }

    /**
     * 构造缺失、system、其他租户、畸形、空值和重复租户头矩阵。
     */
    private List<TenantHeaderCase> tenantHeaderCases() {
        return List.of(
                new TenantHeaderCase("absent", request -> { }),
                new TenantHeaderCase("system", request -> request.header(HEADER_TENANT_ID, "0")),
                new TenantHeaderCase("other-tenant", request -> request.header(
                        HEADER_TENANT_ID, String.valueOf(FORGED_TENANT_ID))),
                new TenantHeaderCase("malformed", request -> request.header(HEADER_TENANT_ID, "not-a-tenant")),
                new TenantHeaderCase("empty", request -> request.header(HEADER_TENANT_ID, "")),
                new TenantHeaderCase("duplicate", request -> request.header(
                        HEADER_TENANT_ID,
                        String.valueOf(OWNER_TENANT_ID),
                        String.valueOf(FORGED_TENANT_ID))));
    }

    /**
     * 等待异步分享审计达到预期数量，超时则保留实际计数作为断言证据。
     *
     * @param expectedCount 预期审计行数
     */
    private void awaitShareAccessLogCount(int expectedCount) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + 5_000_000_000L;
        long currentCount;
        do {
            currentCount = shareAccessLogCount();
            if (currentCount >= expectedCount) {
                break;
            }
            Thread.sleep(25L);
        } while (System.nanoTime() < deadlineNanos);

        assertThat(currentCount).isEqualTo(expectedCount);
    }

    /**
     * 查询当前分享的访问审计数量。
     */
    private long shareAccessLogCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM share_access_log WHERE share_code = ?",
                Long.class,
                shareCode);
        return count != null ? count : 0L;
    }

    /**
     * 查询四个匿名公开入口的系统操作日志。
     */
    private List<AuditRow> selectPublicOperationRows() {
        return jdbcTemplate.query(
                """
                SELECT tenant_id, request_ip, request_url
                FROM sys_operation_log
                WHERE request_url IN (?, ?, ?, ?)
                """,
                (resultSet, rowNumber) -> new AuditRow(
                        resultSet.getLong("tenant_id"),
                        resultSet.getString("request_ip"),
                        resultSet.getString("request_url")),
                INFO_AUDIT_URL,
                FILES_AUDIT_URL,
                CHUNKS_AUDIT_URL,
                DECRYPT_AUDIT_URL);
    }

    /**
     * 查询当前分享的 owner-scoped 分享访问日志。
     */
    private List<ShareAuditRow> selectShareAccessRows() {
        return jdbcTemplate.query(
                "SELECT tenant_id, actor_ip, action_type FROM share_access_log WHERE share_code = ?",
                (resultSet, rowNumber) -> new ShareAuditRow(
                        resultSet.getLong("tenant_id"),
                        resultSet.getString("actor_ip"),
                        resultSet.getInt("action_type")),
                shareCode);
    }

    /**
     * 设置 MockMvc 请求的直接 socket 对端地址。
     */
    private RequestPostProcessor remoteAddress(String peer) {
        return request -> {
            request.setRemoteAddr(peer);
            return request;
        };
    }

    /**
     * 清理四个公开分享审计 URL 的历史系统日志，隔离复用容器中的测试状态。
     */
    private void clearPublicAuditRows() {
        jdbcTemplate.update(
                "DELETE FROM sys_operation_log WHERE request_url IN (?, ?, ?, ?)",
                INFO_AUDIT_URL,
                FILES_AUDIT_URL,
                CHUNKS_AUDIT_URL,
                DECRYPT_AUDIT_URL);
    }

    /**
     * 清理公开分享限流键，隔离复用 Redis 容器中的测试状态。
     */
    private void clearPublicRateKeys() {
        var keys = redisTemplate.keys("rate:limit:public:share-access:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private record TenantHeaderCase(
            String name,
            Consumer<MockHttpServletRequestBuilder> configure) {
    }

    private record AuditRow(long tenantId, String ip, String url) {
    }

    private record ShareAuditRow(long tenantId, String ip, int actionType) {
    }
}
