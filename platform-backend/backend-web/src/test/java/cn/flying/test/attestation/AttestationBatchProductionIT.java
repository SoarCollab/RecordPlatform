package cn.flying.test.attestation;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.GetAttestationBatchRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.GetAttestationBatchResponse;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.service.attestation.AttestationBatchCandidatePersistenceService;
import cn.flying.service.attestation.AttestationBatchProductionProperties;
import cn.flying.service.attestation.AttestationBatchProductionRunResult;
import cn.flying.service.attestation.AttestationBatchProductionService;
import cn.flying.service.attestation.AttestationCandidateAdmissionResult;
import cn.flying.service.attestation.AttestationCandidateClaim;
import cn.flying.service.auth.AuthorizationStateService;
import cn.flying.test.BaseIntegrationTest;
import cn.flying.test.support.JwtTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用真实 MySQL 验证生产 candidate、租约和 batch/leaf 原子绑定。
 */
@AutoConfigureMockMvc
class AttestationBatchProductionIT extends BaseIntegrationTest {

    private static final Long TENANT_ID = 93_001L;
    private static final Long USER_ID = 94_001L;
    private static final String PRODUCTION_BASE_URL = "/api/v1/admin/attestation-batches/production";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AttestationBatchProductionService productionService;

    @Autowired
    private AttestationBatchCandidatePersistenceService candidatePersistence;

    @Autowired
    private AttestationBatchProductionProperties properties;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthorizationStateService authorizationStateService;

    /**
     * 启用测试内生产路径，并把阈值限制为单文件/单 batch。
     */
    @BeforeEach
    void setUpProduction() {
        lenient().when(authorizationStateService.isTokenAuthorized(
                        anyLong(), anyLong(), anyString(), anyString(), anyLong()))
                .thenReturn(true);
        TenantContext.setTenantId(TENANT_ID);
        properties.setEnabled(true);
        properties.setMinBatchSize(1);
        properties.setMaxBatchSize(10);
        properties.setSeedLimit(20);
        properties.setMaxBatchesPerRun(1);
        properties.setCandidateMaxAttempts(3);
        properties.setClaimLeaseSeconds(30);
    }

    /**
     * 按外键逆序清理测试数据并恢复默认关闭的 feature flag。
     */
    @AfterEach
    void cleanUpProduction() {
        TenantContext.clear();
        properties.setEnabled(false);
        jdbcTemplate.update("DELETE FROM attestation_batch_candidate WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_batch_attempt WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_leaf WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_batch WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_chunk_manifest_item WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_chunk_manifest WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM sys_operation_log WHERE tenant_id = ?", TENANT_ID);
    }

    /**
     * 验证未认证调用不能读取或触发当前租户的生产批次。
     */
    @Test
    void productionEndpointsShouldRejectUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get(PRODUCTION_BASE_URL + "/status")
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(PRODUCTION_BASE_URL + "/trigger")
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 验证普通用户即使租户一致，也不能读取或触发管理员生产入口。
     */
    @Test
    void productionEndpointsShouldRejectNonAdminUsers() throws Exception {
        String token = JwtTestSupport.generateToken(USER_ID, "production-user", "user", TENANT_ID);

        mockMvc.perform(get(PRODUCTION_BASE_URL + "/status")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(PRODUCTION_BASE_URL + "/trigger")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isForbidden());
    }

    /**
     * 验证管理员 token 不能通过伪造请求头切换到其他租户。
     */
    @Test
    void productionEndpointsShouldRejectTenantMismatch() throws Exception {
        String token = JwtTestSupport.generateAdminToken(USER_ID, TENANT_ID);

        mockMvc.perform(get(PRODUCTION_BASE_URL + "/status")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID + 1))
                .andExpect(status().isForbidden());
    }

    /**
     * 验证管理员可在认证租户内查询状态并执行有界人工触发。
     */
    @Test
    void productionEndpointsShouldAllowCurrentTenantAdmin() throws Exception {
        String token = JwtTestSupport.generateAdminToken(USER_ID, TENANT_ID);

        mockMvc.perform(get(PRODUCTION_BASE_URL + "/status")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        mockMvc.perform(post(PRODUCTION_BASE_URL + "/trigger")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-ID", TENANT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.force").value(true));
    }

    /**
     * 验证成功文件只 admission 一次，manifest hash 成为叶子证据且链调用位于提交事务之后。
     */
    @Test
    void forceRunShouldAtomicallyBindManifestCandidateAndCompleteBatch() {
        TestEvidence evidence = insertSuccessfulFileWithManifest();
        when(fileRemoteClient.getAttestationBatch(any(GetAttestationBatchRequest.class)))
                .thenAnswer(invocation -> {
                    GetAttestationBatchRequest request = invocation.getArgument(0);
                    return Result.success(GetAttestationBatchResponse.notFound(
                            request.tenantId(), request.batchId()));
                });
        when(fileRemoteClient.storeAttestationBatch(any(StoreAttestationBatchRequest.class)))
                .thenAnswer(invocation -> {
                    StoreAttestationBatchRequest request = invocation.getArgument(0);
                    return Result.success(new StoreAttestationBatchResponse(
                            "d".repeat(64), request.merkleRoot()));
                });

        AttestationBatchProductionRunResult first = productionService.runTenant(TENANT_ID, true);
        AttestationBatchProductionRunResult duplicate = productionService.runTenant(TENANT_ID, true);

        assertThat(first.candidatesAdmitted()).isEqualTo(1);
        assertThat(first.batchesCreated()).isEqualTo(1);
        assertThat(first.batchesCompleted()).isEqualTo(1);
        assertThat(duplicate.candidatesAdmitted()).isZero();
        assertThat(duplicate.batchesCreated()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM attestation_batch_candidate WHERE tenant_id = ? AND file_id = ?",
                String.class, TENANT_ID, evidence.fileId())).isEqualTo("BATCHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT evidence_type FROM attestation_leaf WHERE tenant_id = ? AND file_id = ?",
                String.class, TENANT_ID, evidence.fileId())).isEqualTo("MANIFEST_HASH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT evidence_hash FROM attestation_leaf WHERE tenant_id = ? AND file_id = ?",
                String.class, TENANT_ID, evidence.fileId())).isEqualTo(evidence.manifestHash());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT chain_record_id FROM attestation_leaf WHERE tenant_id = ? AND file_id = ?",
                String.class, TENANT_ID, evidence.fileId())).isEqualTo(evidence.chainRecordId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attestation_batch_candidate WHERE tenant_id = ? AND file_id = ?",
                Integer.class, TENANT_ID, evidence.fileId())).isEqualTo(1);
    }

    /**
     * 验证两个并发 worker 对同一 READY candidate 只有一个取得 claim。
     */
    @Test
    void concurrentWorkersShouldClaimCandidateExactlyOnce() throws Exception {
        insertSuccessfulFileWithManifest();
        AttestationCandidateAdmissionResult admission = candidatePersistence.seedEligibleCandidates(
                TENANT_ID, 20, new java.util.Date());
        assertThat(admission.readyCandidates()).isEqualTo(1);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<AttestationCandidateClaim> first = executor.submit(() ->
                    concurrentClaim(ready, start));
            Future<AttestationCandidateClaim> second = executor.submit(() ->
                    concurrentClaim(ready, start));
            boolean workersReady = ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            assertThat(workersReady)
                    .as("两个并发 worker 应在 10 秒内进入领取就绪状态")
                    .isTrue();

            long winners = Stream.of(
                            first.get(10, TimeUnit.SECONDS),
                            second.get(10, TimeUnit.SECONDS))
                    .filter(java.util.Objects::nonNull)
                    .count();
            assertThat(winners).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .as("并发领取 worker 应在 10 秒内停止")
                    .isTrue();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM attestation_batch_candidate WHERE tenant_id = ?",
                Integer.class, TENANT_ID)).isEqualTo(1);
    }

    /**
     * 验证 worker 崩溃后的过期租约会恢复为 READY，并由新 token 继续处理。
     */
    @Test
    void expiredClaimShouldRecoverAfterRestart() {
        insertSuccessfulFileWithManifest();
        candidatePersistence.seedEligibleCandidates(TENANT_ID, 20, new java.util.Date());
        Instant base = Instant.parse("2026-07-14T00:00:00Z");

        AttestationCandidateClaim first = candidatePersistence.claimCandidates(
                TENANT_ID, 1, 3, 30, base);
        int deadLettered = candidatePersistence.recoverExpiredClaims(
                TENANT_ID, 3, base.plusSeconds(31));
        AttestationCandidateClaim recovered = candidatePersistence.claimCandidates(
                TENANT_ID, 1, 3, 30, base.plusSeconds(31));

        assertThat(first).isNotNull();
        assertThat(deadLettered).isZero();
        assertThat(recovered).isNotNull();
        assertThat(recovered.claimToken()).isNotEqualTo(first.claimToken());
        assertThat(recovered.candidates().getFirst().getAttemptCount()).isEqualTo(2);
    }

    /**
     * 在独立线程中设置租户上下文并参与同一 candidate claim 竞争。
     */
    private AttestationCandidateClaim concurrentClaim(
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        TenantContext.setTenantId(TENANT_ID);
        try {
            ready.countDown();
            start.await();
            return candidatePersistence.claimCandidates(
                    TENANT_ID, 1, 3, 120, Instant.now());
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 插入版本匹配的成功文件和唯一 active manifest。
     */
    private TestEvidence insertSuccessfulFileWithManifest() {
        long fileId = IdUtils.nextEntityId();
        long manifestId = IdUtils.nextEntityId();
        String chainRecordId = "chain-record-" + fileId;
        String manifestHash = "sha256:" + String.format("%064x", fileId);
        File file = new File()
                .setId(fileId)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("production-attestation-" + fileId + ".txt")
                .setClassification("document")
                .setFileParam("{\"fileSize\":1,\"contentType\":\"text/plain\"}")
                .setFileHash(chainRecordId)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setVersion(1)
                .setIsLatest(1)
                .setDeleted(0);
        fileMapper.insert(file);

        jdbcTemplate.update("""
                INSERT INTO file_chunk_manifest (
                    id, tenant_id, file_id, file_version, file_hash, schema_id, manifest_hash,
                    hash_algorithm, chunk_size, chunk_count, total_size, storage_backend,
                    manifest_json, status, deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0)
                """,
                manifestId,
                TENANT_ID,
                fileId,
                1,
                chainRecordId,
                "cn.flying.chunk-manifest.v1",
                manifestHash,
                "SHA-256",
                1L,
                1,
                1L,
                "S3",
                "{}");
        return new TestEvidence(fileId, manifestId, manifestHash, chainRecordId);
    }

    /**
     * 保存集成测试创建的文件、manifest 和链记录标识。
     */
    private record TestEvidence(
            Long fileId,
            Long manifestId,
            String manifestHash,
            String chainRecordId
    ) {
    }
}
