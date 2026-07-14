package cn.flying.test.attestation;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.GetAttestationBatchRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.GetAttestationBatchResponse;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.service.attestation.AttestationBatchPersistenceService;
import cn.flying.service.attestation.AttestationBatchService;
import cn.flying.service.attestation.AttestationLeafEvidence;
import cn.flying.service.attestation.MerkleLeafInput;
import cn.flying.service.attestation.MerkleTreeResult;
import cn.flying.service.attestation.MerkleTreeService;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 使用真实 MySQL 事务和唯一键验证 Merkle batch 链提交一致性边界。
 */
class AttestationBatchConsistencyIT extends BaseIntegrationTest {

    private static final Long TENANT_ID = 91_001L;
    private static final Long OTHER_TENANT_ID = 91_002L;
    private static final Long USER_ID = 92_001L;

    @Autowired
    private AttestationBatchService attestationBatchService;

    @Autowired
    private AttestationBatchPersistenceService persistenceService;

    @Autowired
    private MerkleTreeService merkleTreeService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FileRemoteClient remoteClient;

    @BeforeEach
    void setUpTenant() {
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void cleanUpData() {
        TenantContext.clear();
        jdbcTemplate.update("DELETE FROM attestation_batch_attempt WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_leaf WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_batch WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
        jdbcTemplate.update("DELETE FROM file WHERE tenant_id IN (?, ?)",
                TENANT_ID, OTHER_TENANT_ID);
    }

    /**
     * 验证链查询和链写发生时没有本地事务，且 batch、leaf 和 claim 已经对其他连接可见。
     */
    @Test
    void createBatch_shouldCommitDatabaseStateBeforeAnyChainRpcAndRemainIdempotent() {
        File file = insertSuccessfulFile();
        when(remoteClient.getAttestationBatch(any(GetAttestationBatchRequest.class)))
                .thenAnswer(invocation -> {
                    GetAttestationBatchRequest request = invocation.getArgument(0);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT status FROM attestation_batch WHERE tenant_id = ? AND id = ?",
                            String.class,
                            TENANT_ID,
                            request.batchId())).isEqualTo("CHAIN_SUBMITTING");
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM attestation_leaf WHERE tenant_id = ? AND batch_id = ?",
                            Integer.class,
                            TENANT_ID,
                            request.batchId())).isEqualTo(1);
                    assertThat(jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM attestation_batch_attempt WHERE tenant_id = ? AND batch_id = ?",
                            Integer.class,
                            TENANT_ID,
                            request.batchId())).isEqualTo(1);
                    return Result.success(GetAttestationBatchResponse.notFound(
                            request.tenantId(), request.batchId()));
                });
        when(remoteClient.storeAttestationBatch(any(StoreAttestationBatchRequest.class)))
                .thenAnswer(invocation -> {
                    StoreAttestationBatchRequest request = invocation.getArgument(0);
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return Result.success(new StoreAttestationBatchResponse(
                            "a".repeat(64), request.merkleRoot()));
                });

        AttestationBatch first = attestationBatchService.createBatch(USER_ID, List.of(file.getId()));
        AttestationBatch duplicate = attestationBatchService.createBatch(USER_ID, List.of(file.getId()));

        assertThat(first.getStatus()).isEqualTo("COMPLETED");
        assertThat(duplicate.getId()).isEqualTo(first.getId());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attestation_batch WHERE tenant_id = ?", Integer.class, TENANT_ID))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attestation_leaf WHERE tenant_id = ?", Integer.class, TENANT_ID))
                .isEqualTo(1);
        verify(remoteClient, times(1)).getAttestationBatch(any(GetAttestationBatchRequest.class));
        verify(remoteClient, times(1)).storeAttestationBatch(any(StoreAttestationBatchRequest.class));
    }

    /**
     * 验证过期租约可被新 worker 恢复，旧 claim 不能覆盖新 claim 的终态，并保持租户隔离。
     */
    @Test
    void expiredLease_shouldRecoverWhileStaleClaimAndOtherTenantAreRejected() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch batch = persistenceService.createOrGet(TENANT_ID, "a".repeat(64), tree);
        Instant base = Instant.parse("2026-07-14T00:00:00Z");

        Optional<AttestationBatch> firstClaim = persistenceService.claim(
                TENANT_ID,
                batch.getId(),
                "claim-old",
                Date.from(base),
                Date.from(base.plusSeconds(10)),
                5);
        Optional<AttestationBatch> recoveredClaim = persistenceService.claim(
                TENANT_ID,
                batch.getId(),
                "claim-new",
                Date.from(base.plusSeconds(11)),
                Date.from(base.plusSeconds(131)),
                5);

        assertThat(firstClaim).isPresent();
        assertThat(recoveredClaim).isPresent();
        assertThat(recoveredClaim.orElseThrow().getAttemptCount()).isEqualTo(2);
        assertThat(persistenceService.confirm(
                TENANT_ID,
                batch.getId(),
                "claim-old",
                "b".repeat(64),
                tree.merkleRoot(),
                "CHAIN_WRITE")).isFalse();
        assertThat(persistenceService.confirm(
                TENANT_ID,
                batch.getId(),
                "claim-new",
                "c".repeat(64),
                tree.merkleRoot(),
                "CHAIN_WRITE")).isTrue();

        assertThat(persistenceService.findById(TENANT_ID, batch.getId()))
                .get()
                .extracting(AttestationBatch::getStatus, AttestationBatch::getChainTransactionHash)
                .containsExactly("COMPLETED", "c".repeat(64));
        assertThat(persistenceService.findById(OTHER_TENANT_ID, batch.getId())).isEmpty();

        TenantContext.setTenantId(OTHER_TENANT_ID);
        assertThatThrownBy(() -> attestationBatchService.submitBatch(batch.getId()))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class);
    }

    /**
     * 验证真实 SQL 会阻止退避时间前的领取，并在到期后生成连续 attempt 审计。
     */
    @Test
    void retrySchedule_shouldRejectEarlyClaimAndAllowDueClaim() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch batch = persistenceService.createOrGet(TENANT_ID, "c".repeat(64), tree);
        Instant base = Instant.parse("2026-07-14T01:00:00Z");
        Date retryAt = Date.from(base.plusSeconds(30));

        Optional<AttestationBatch> firstClaim = persistenceService.claim(
                TENANT_ID,
                batch.getId(),
                "claim-retry-1",
                Date.from(base),
                Date.from(base.plusSeconds(120)),
                5);
        assertThat(firstClaim).isPresent();
        assertThat(persistenceService.retry(
                TENANT_ID,
                batch.getId(),
                "claim-retry-1",
                "temporary chain failure",
                retryAt)).isTrue();

        Optional<AttestationBatch> earlyClaim = persistenceService.claim(
                TENANT_ID,
                batch.getId(),
                "claim-retry-early",
                Date.from(base.plusSeconds(29)),
                Date.from(base.plusSeconds(149)),
                5);
        Optional<AttestationBatch> dueClaim = persistenceService.claim(
                TENANT_ID,
                batch.getId(),
                "claim-retry-2",
                retryAt,
                Date.from(base.plusSeconds(150)),
                5);

        assertThat(earlyClaim).isEmpty();
        assertThat(dueClaim).isPresent();
        assertThat(dueClaim.orElseThrow().getAttemptCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM attestation_batch_attempt "
                        + "WHERE tenant_id = ? AND batch_id = ? ORDER BY attempt_no",
                String.class,
                TENANT_ID,
                batch.getId()))
                .containsExactly("RETRY_SCHEDULED", "CLAIMED");
    }

    /**
     * 验证两个并发 worker 对同一 pending batch 只有一个能取得有效 claim。
     */
    @Test
    void concurrentWorkers_shouldProduceExactlyOneActiveClaim() throws Exception {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch batch = persistenceService.createOrGet(TENANT_ID, "b".repeat(64), tree);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Date now = new Date();
        Date lease = new Date(now.getTime() + 120_000L);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Optional<AttestationBatch>> first = executor.submit(() ->
                    concurrentClaim(batch.getId(), "claim-a", now, lease, ready, start));
            Future<Optional<AttestationBatch>> second = executor.submit(() ->
                    concurrentClaim(batch.getId(), "claim-b", now, lease, ready, start));
            ready.await();
            start.countDown();

            long winners = List.of(first.get(), second.get()).stream().filter(Optional::isPresent).count();
            assertThat(winners).isEqualTo(1);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM attestation_batch_attempt WHERE tenant_id = ? AND batch_id = ?",
                Integer.class,
                TENANT_ID,
                batch.getId())).isEqualTo(1);
    }

    /**
     * 验证 Flyway JSON 列、MyBatis 映射和 claim CAS 对 registry 快照执行真实数据库闭环。
     */
    @Test
    void contractRegistrySnapshot_shouldRoundTripAndFailClosedForStaleOrLegacyRows() {
        MerkleTreeResult tree = sampleTree();
        ContractRegistryEntryResponse registry = contractRegistry();
        List<AttestationLeafEvidence> evidence = tree.leaves().stream()
                .map(leaf -> new AttestationLeafEvidence(
                        leaf.fileId(),
                        1,
                        null,
                        AttestationBatchPersistenceService.EVIDENCE_TYPE_LEGACY_CHAIN_RECORD_ID,
                        leaf.fileHash(),
                        leaf.fileHash()))
                .toList();

        AttestationBatch batch = persistenceService.createOrGet(
                TENANT_ID,
                "registry-" + "d".repeat(55),
                tree,
                evidence,
                registry);
        AttestationBatch reloaded = persistenceService.findById(TENANT_ID, batch.getId())
                .orElseThrow();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT JSON_VALID(contract_registry_json) FROM attestation_batch "
                        + "WHERE tenant_id = ? AND id = ?",
                Integer.class,
                TENANT_ID,
                batch.getId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(contract_registry_json, '$.contractName')) "
                        + "FROM attestation_batch WHERE tenant_id = ? AND id = ?",
                String.class,
                TENANT_ID,
                batch.getId())).isEqualTo("Sharing");
        assertThat(reloaded)
                .extracting(
                        AttestationBatch::getContractRegistryFingerprint,
                        AttestationBatch::getChainType,
                        AttestationBatch::getChainId,
                        AttestationBatch::getChainGroupId,
                        AttestationBatch::getContractName,
                        AttestationBatch::getContractVersion,
                        AttestationBatch::getContractAddress,
                        AttestationBatch::getContractAbiSha256,
                        AttestationBatch::getContractArtifactBytecodeSha256,
                        AttestationBatch::getContractCodeSha256,
                        AttestationBatch::getContractStatus)
                .containsExactly(
                        registry.registryFingerprint(),
                        registry.chainType(),
                        registry.chainId(),
                        registry.groupId(),
                        registry.contractName(),
                        registry.semanticVersion(),
                        registry.contractAddress(),
                        registry.abiSha256(),
                        registry.artifactBytecodeSha256(),
                        registry.onChainCodeSha256(),
                        registry.status());
        assertThat(persistenceService.requireContractRegistry(reloaded)).isEqualTo(registry);

        Date now = Date.from(Instant.parse("2026-07-14T02:00:00Z"));
        String claimToken = "claim-registry-it";
        assertThat(persistenceService.claim(
                TENANT_ID,
                batch.getId(),
                claimToken,
                now,
                Date.from(now.toInstant().plusSeconds(120)),
                5)).isPresent();
        assertThat(persistenceService.verifyContractRegistryClaim(
                TENANT_ID,
                batch.getId(),
                claimToken,
                registry)).isPresent();
        ContractRegistryEntryResponse staleRegistry = new ContractRegistryEntryResponse(
                registry.schemaVersion(),
                "sha256:" + "9".repeat(64),
                registry.contractName(),
                registry.semanticVersion(),
                registry.chainType(),
                registry.chainId(),
                registry.groupId(),
                registry.contractAddress(),
                registry.abiFingerprintAlgorithm(),
                registry.abiSha256(),
                registry.artifactBytecodeSha256(),
                registry.onChainCodeSha256(),
                registry.deploymentTransactionHash(),
                registry.deploymentBlockNumber(),
                registry.status(),
                registry.effectiveAt(),
                registry.upgradeStrategy());
        assertThat(persistenceService.verifyContractRegistryClaim(
                TENANT_ID,
                batch.getId(),
                claimToken,
                staleRegistry)).isEmpty();

        AttestationBatch legacy = persistenceService.createOrGet(
                TENANT_ID,
                "legacy-" + "e".repeat(57),
                tree);
        AttestationBatch reloadedLegacy = persistenceService.findById(TENANT_ID, legacy.getId())
                .orElseThrow();
        assertThat(reloadedLegacy.getContractRegistryJson()).isNull();
        assertThatThrownBy(() -> persistenceService.requireContractRegistry(reloadedLegacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no contract registry snapshot");
    }

    /**
     * 在独立线程中恢复租户上下文并参与同一时刻的原子 claim 竞争。
     */
    private Optional<AttestationBatch> concurrentClaim(
            Long batchId,
            String claimToken,
            Date now,
            Date lease,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        TenantContext.setTenantId(TENANT_ID);
        try {
            ready.countDown();
            start.await();
            return persistenceService.claim(TENANT_ID, batchId, claimToken, now, lease, 5);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 插入一个可参与批量存证的真实文件记录。
     */
    private File insertSuccessfulFile() {
        long fileId = IdUtils.nextEntityId();
        File file = new File()
                .setId(fileId)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("attestation-consistency-" + fileId + ".txt")
                .setClassification("document")
                .setFileParam("{\"fileSize\":1,\"contentType\":\"text/plain\"}")
                .setFileHash("integration-hash-" + fileId)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setCreateTime(new Date())
                .setDeleted(0);
        fileMapper.insert(file);
        return file;
    }

    /**
     * 创建用于真实状态 SQL 测试的确定性 Merkle 树。
     */
    private MerkleTreeResult sampleTree() {
        return merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(81_001L, "hash-a"),
                new MerkleLeafInput(81_002L, "hash-b")));
    }

    /**
     * 创建带完整部署证据的确定性 registry 快照。
     */
    private ContractRegistryEntryResponse contractRegistry() {
        return new ContractRegistryEntryResponse(
                "record-platform-contract-registry-entry.v1",
                null,
                "Sharing",
                "2.0.0",
                "LOCAL_FISCO",
                "chain0",
                "group0",
                "0x1111111111111111111111111111111111111111",
                "ABI-CANONICAL-JSON-SHA256-V1",
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                "sha256:" + "4".repeat(64),
                "0x" + "a".repeat(64),
                42L,
                "ACTIVE",
                "2026-07-14T00:00:00Z",
                "REDEPLOY_ADDRESS")
                .withCalculatedRegistryFingerprint();
    }
}
