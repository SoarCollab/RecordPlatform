package cn.flying.service.attestation;

import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationBatchAttempt;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.mapper.AttestationBatchAttemptMapper;
import cn.flying.dao.mapper.AttestationBatchCandidateMapper;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AttestationBatchPersistenceService")
class AttestationBatchPersistenceServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long BATCH_ID = 900L;
    private static final String CHAIN_TRANSACTION_HASH = "a".repeat(64);
    private static final String CHAIN_ROOT = "b".repeat(64);

    @Mock
    private AttestationBatchMapper batchMapper;

    @Mock
    private AttestationLeafMapper leafMapper;

    @Mock
    private AttestationBatchAttemptMapper attemptMapper;

    @Mock
    private AttestationBatchCandidateMapper candidateMapper;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    private AttestationBatchPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new AttestationBatchPersistenceService(
                batchMapper, leafMapper, attemptMapper, candidateMapper, snowflakeIdGenerator);
    }

    /**
     * 验证 batch 和全部规范化 leaf 在同一创建调用中持久化。
     */
    @Test
    void createOrGet_shouldInsertBatchAndAllLeaves() {
        MerkleTreeResult tree = sampleTree();
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(BATCH_ID, 901L, 902L);

        AttestationBatch actual = service.createOrGet(TENANT_ID, "k".repeat(64), tree);

        assertThat(actual.getId()).isEqualTo(BATCH_ID);
        assertThat(actual.getStatus()).isEqualTo("CHAIN_PENDING");
        assertThat(actual.getIdempotencyKey()).isEqualTo("k".repeat(64));
        assertThat(actual.getAttemptCount()).isZero();
        verify(batchMapper).insert(actual);
        ArgumentCaptor<AttestationLeaf> leafCaptor = ArgumentCaptor.forClass(AttestationLeaf.class);
        verify(leafMapper, org.mockito.Mockito.times(2)).insert(leafCaptor.capture());
        assertThat(leafCaptor.getAllValues())
                .extracting(AttestationLeaf::getFileId)
                .containsExactly(12L, 11L);
        assertThat(leafCaptor.getAllValues())
                .allSatisfy(leaf -> {
                    assertThat(leaf.getFileVersion()).isEqualTo(1);
                    assertThat(leaf.getEvidenceType()).isEqualTo("LEGACY_CHAIN_RECORD_ID");
                    assertThat(leaf.getEvidenceHash()).isEqualTo(leaf.getFileHash());
                });
    }

    /**
     * 验证 production batch、manifest leaf 和 candidate 终结在同一持久化调用中完成。
     */
    @Test
    void createFromClaimedCandidates_shouldBindEveryCandidateToTheBatch() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatchCandidate first = productionCandidate(101L, 11L, "hash-b");
        AttestationBatchCandidate second = productionCandidate(102L, 12L, "hash-a");
        AttestationCandidateClaim claim = new AttestationCandidateClaim(
                TENANT_ID, "claim-production", List.of(first, second));
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(BATCH_ID, 901L, 902L);
        when(candidateMapper.markBatched(TENANT_ID, "claim-production", BATCH_ID)).thenReturn(2);

        AttestationBatch actual = service.createFromClaimedCandidates(
                TENANT_ID, "p".repeat(64), tree, claim);

        assertThat(actual.getId()).isEqualTo(BATCH_ID);
        ArgumentCaptor<AttestationLeaf> leafCaptor = ArgumentCaptor.forClass(AttestationLeaf.class);
        verify(leafMapper, org.mockito.Mockito.times(2)).insert(leafCaptor.capture());
        assertThat(leafCaptor.getAllValues())
                .allSatisfy(leaf -> {
                    assertThat(leaf.getEvidenceType()).isEqualTo("MANIFEST_HASH");
                    assertThat(leaf.getManifestId()).isNotNull();
                    assertThat(leaf.getChainRecordId()).startsWith("chain-");
                });
        verify(candidateMapper).markBatched(TENANT_ID, "claim-production", BATCH_ID);
    }

    /**
     * 验证已存在幂等 batch 时不再插入 batch 或 leaf。
     */
    @Test
    void createOrGet_shouldReuseExistingIdempotentBatch() {
        AttestationBatch existing = new AttestationBatch().setId(BATCH_ID).setTenantId(TENANT_ID);
        when(batchMapper.selectOne(any())).thenReturn(existing);

        AttestationBatch actual = service.createOrGet(TENANT_ID, "k".repeat(64), sampleTree());

        assertThat(actual).isSameAs(existing);
        verify(batchMapper, never()).insert(any(AttestationBatch.class));
        verify(leafMapper, never()).insert(any(AttestationLeaf.class));
    }

    /**
     * 验证新生产 batch 会在插入事务中固化完整 registry JSON 和全部查询字段。
     */
    @Test
    void createOrGet_shouldPersistImmutableContractRegistrySnapshot() {
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
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(BATCH_ID, 901L, 902L);

        AttestationBatch actual = service.createOrGet(
                TENANT_ID, "r".repeat(64), tree, evidence, registry);

        assertThat(actual.getContractRegistryFingerprint())
                .isEqualTo(registry.registryFingerprint());
        assertThat(actual.getChainType()).isEqualTo(registry.chainType());
        assertThat(actual.getChainId()).isEqualTo(registry.chainId());
        assertThat(actual.getChainGroupId()).isEqualTo(registry.groupId());
        assertThat(actual.getContractName()).isEqualTo("Sharing");
        assertThat(actual.getContractVersion()).isEqualTo("2.0.0");
        assertThat(actual.getContractAddress()).isEqualTo(registry.contractAddress());
        assertThat(actual.getContractAbiSha256()).isEqualTo(registry.abiSha256());
        assertThat(actual.getContractArtifactBytecodeSha256())
                .isEqualTo(registry.artifactBytecodeSha256());
        assertThat(actual.getContractCodeSha256()).isEqualTo(registry.onChainCodeSha256());
        assertThat(actual.getContractStatus()).isEqualTo("ACTIVE");
        assertThat(service.requireContractRegistry(actual)).isEqualTo(registry);
        verify(batchMapper).insert(actual);
    }

    /**
     * 验证指纹与字段不一致的 registry 在 batch 或 leaf 写入前失败关闭。
     */
    @Test
    void createOrGet_shouldRejectStaleContractRegistryFingerprintBeforeInsert() {
        MerkleTreeResult tree = sampleTree();
        List<AttestationLeafEvidence> evidence = tree.leaves().stream()
                .map(leaf -> new AttestationLeafEvidence(
                        leaf.fileId(),
                        1,
                        null,
                        AttestationBatchPersistenceService.EVIDENCE_TYPE_LEGACY_CHAIN_RECORD_ID,
                        leaf.fileHash(),
                        leaf.fileHash()))
                .toList();
        when(batchMapper.selectOne(any())).thenReturn(null);
        when(snowflakeIdGenerator.nextId()).thenReturn(BATCH_ID);

        assertThatThrownBy(() -> service.createOrGet(
                TENANT_ID,
                "s".repeat(64),
                tree,
                evidence,
                contractRegistryWithStaleFingerprint()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint is invalid");

        verify(batchMapper, never()).insert(any(AttestationBatch.class));
        verify(leafMapper, never()).insert(any(AttestationLeaf.class));
    }

    /**
     * 验证 registry 只能在当前 claim token、指纹和非空快照同时匹配时继续链写。
     */
    @Test
    void verifyContractRegistryClaim_shouldReturnVerifiedPersistedSnapshot() {
        ContractRegistryEntryResponse registry = contractRegistry();
        AttestationBatch claimed = batchWithRegistry(registry).setClaimToken("claim-registry");
        when(batchMapper.verifyContractRegistryClaim(
                TENANT_ID,
                BATCH_ID,
                "claim-registry",
                registry.registryFingerprint())).thenReturn(1);
        when(batchMapper.selectOne(any())).thenReturn(claimed);

        Optional<AttestationBatch> actual = service.verifyContractRegistryClaim(
                TENANT_ID, BATCH_ID, "claim-registry", registry);

        assertThat(actual).containsSame(claimed);
        verify(batchMapper).verifyContractRegistryClaim(
                TENANT_ID,
                BATCH_ID,
                "claim-registry",
                registry.registryFingerprint());
    }

    /**
     * 验证完整 JSON 与反规范化地址不一致时不能被当作可信 registry 快照读取。
     */
    @Test
    void requireContractRegistry_shouldRejectDenormalizedFieldDrift() {
        AttestationBatch inconsistent = batchWithRegistry(contractRegistry())
                .setContractAddress("0x9999999999999999999999999999999999999999");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.requireContractRegistry(inconsistent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot is inconsistent");
    }

    /**
     * 验证 JSON 与反规范化列即使彼此一致，也不能掩盖 registry 自身的失效指纹。
     */
    @Test
    void requireContractRegistry_shouldRejectSelfConsistentSnapshotWithStaleFingerprint() {
        AttestationBatch stale = batchWithRegistry(contractRegistryWithStaleFingerprint());

        assertThatThrownBy(() -> service.requireContractRegistry(stale))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot is inconsistent");
    }

    /**
     * 验证历史快照允许的三种生命周期状态都必须通过同一结构校验后才能读取。
     */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(strings = {"ACTIVE", "DEPRECATED", "REVOKED"})
    void requireContractRegistry_shouldAcceptStructurallyValidSupportedStatuses(String status) {
        ContractRegistryEntryResponse registry = contractRegistry(
                "record-platform-contract-registry-entry.v1",
                "0x1111111111111111111111111111111111111111",
                "ABI-CANONICAL-JSON-SHA256-V1",
                "0x" + "a".repeat(64),
                42L,
                status,
                "REDEPLOY_ADDRESS");

        assertThat(service.requireContractRegistry(batchWithRegistry(registry)))
                .isEqualTo(registry);
    }

    /**
     * 验证攻击者不能用按非法字段重算的自洽指纹绕过 registry 结构合同。
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("structurallyInvalidContractRegistries")
    void requireContractRegistry_shouldRejectSelfConsistentStructurallyInvalidSnapshot(
            String scenario,
            ContractRegistryEntryResponse registry
    ) {
        assertThat(scenario).isNotBlank();
        assertThat(registry.hasValidRegistryFingerprint()).isTrue();

        assertThatThrownBy(() -> service.requireContractRegistry(batchWithRegistry(registry)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot is inconsistent");
    }

    /**
     * 验证 provider 返回失效指纹时不能触发 claim 的数据库状态迁移。
     */
    @Test
    void verifyContractRegistryClaim_shouldRejectStaleFingerprintBeforeMapperUpdate() {
        ContractRegistryEntryResponse stale = contractRegistryWithStaleFingerprint();

        assertThatThrownBy(() -> service.verifyContractRegistryClaim(
                TENANT_ID, BATCH_ID, "claim-registry", stale))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Active contract registry is required");

        verify(batchMapper, never()).verifyContractRegistryClaim(any(), any(), any(), any());
    }

    /**
     * 验证原子领取成功后使用数据库递增后的 attempt 编号写入审计记录。
     */
    @Test
    void claim_shouldInsertAttemptAuditAfterAtomicUpdate() {
        Date now = Date.from(Instant.parse("2026-07-14T00:00:00Z"));
        Date lease = Date.from(Instant.parse("2026-07-14T00:02:00Z"));
        AttestationBatch claimed = new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setStatus("CHAIN_SUBMITTING")
                .setAttemptCount(2)
                .setClaimToken("claim-2");
        when(batchMapper.claimForSubmission(TENANT_ID, BATCH_ID, "claim-2", now, lease, 5))
                .thenReturn(1);
        when(batchMapper.selectOne(any())).thenReturn(claimed);
        when(snowflakeIdGenerator.nextId()).thenReturn(903L);

        Optional<AttestationBatch> actual = service.claim(
                TENANT_ID, BATCH_ID, "claim-2", now, lease, 5);

        assertThat(actual).containsSame(claimed);
        ArgumentCaptor<AttestationBatchAttempt> attemptCaptor =
                ArgumentCaptor.forClass(AttestationBatchAttempt.class);
        verify(attemptMapper).insert(attemptCaptor.capture());
        assertThat(attemptCaptor.getValue().getAttemptNo()).isEqualTo(2);
        assertThat(attemptCaptor.getValue().getStatus()).isEqualTo("CLAIMED");
    }

    /**
     * 验证未取得原子 claim 时不会写入虚假的 attempt 审计。
     */
    @Test
    void claim_shouldReturnEmptyWhenAnotherWorkerWon() {
        Date now = new Date();
        when(batchMapper.claimForSubmission(eq(TENANT_ID), eq(BATCH_ID), eq("claim"),
                eq(now), any(Date.class), eq(5))).thenReturn(0);

        Optional<AttestationBatch> actual = service.claim(
                TENANT_ID, BATCH_ID, "claim", now, new Date(now.getTime() + 1_000), 5);

        assertThat(actual).isEmpty();
        verify(attemptMapper, never()).insert(any(AttestationBatchAttempt.class));
    }

    /**
     * 验证有效 claim 完成时同时记录确认来源、交易和根。
     */
    @Test
    void confirm_shouldUpdateBatchAndAttemptAtomically() {
        when(batchMapper.confirmSubmission(
                TENANT_ID, BATCH_ID, "claim", CHAIN_TRANSACTION_HASH, CHAIN_ROOT, "CHAIN_WRITE"))
                .thenReturn(1);
        when(attemptMapper.updateResult(
                TENANT_ID, BATCH_ID, "claim", "COMPLETED", "CHAIN_WRITE",
                CHAIN_TRANSACTION_HASH, CHAIN_ROOT, null))
                .thenReturn(1);

        boolean actual = service.confirm(
                TENANT_ID, BATCH_ID, "claim", CHAIN_TRANSACTION_HASH, CHAIN_ROOT, "CHAIN_WRITE");

        assertThat(actual).isTrue();
        verify(attemptMapper).updateResult(
                TENANT_ID, BATCH_ID, "claim", "COMPLETED", "CHAIN_WRITE",
                CHAIN_TRANSACTION_HASH, CHAIN_ROOT, null);
    }

    /**
     * 验证链查询确认只能使用空交易哈希，并仍然原子完成 batch 与 attempt。
     */
    @Test
    void confirm_shouldAcceptQuerySourceOnlyWithoutTransactionHash() {
        when(batchMapper.confirmSubmission(
                TENANT_ID, BATCH_ID, "claim", null, CHAIN_ROOT, "CHAIN_QUERY_BEFORE_WRITE"))
                .thenReturn(1);
        when(attemptMapper.updateResult(
                TENANT_ID, BATCH_ID, "claim", "COMPLETED", "CHAIN_QUERY_BEFORE_WRITE",
                null, CHAIN_ROOT, null))
                .thenReturn(1);

        boolean actual = service.confirm(
                TENANT_ID, BATCH_ID, "claim", null, CHAIN_ROOT, "CHAIN_QUERY_BEFORE_WRITE");

        assertThat(actual).isTrue();
    }

    /**
     * 验证未知来源、伪交易哈希和查询来源携带交易哈希都不能触碰数据库。
     */
    @Test
    void confirm_shouldRejectInvalidConfirmationProvenanceBeforeMapperUpdate() {
        assertThatThrownBy(() -> service.confirm(
                TENANT_ID, BATCH_ID, "claim", "tx", CHAIN_ROOT, "CHAIN_WRITE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(
                TENANT_ID, BATCH_ID, "claim", CHAIN_TRANSACTION_HASH,
                CHAIN_ROOT, "CHAIN_QUERY_AFTER_WRITE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(
                TENANT_ID, BATCH_ID, "claim", null, CHAIN_ROOT, "UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.confirm(
                TENANT_ID, BATCH_ID, "claim", null, CHAIN_ROOT, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(batchMapper, never()).confirmSubmission(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(attemptMapper);
    }

    /**
     * 验证陈旧 claim 不能覆盖批次状态，并留下被忽略的审计结果。
     */
    @Test
    void confirm_shouldRecordStaleAttemptWhenClaimNoLongerOwnsState() {
        when(batchMapper.confirmSubmission(
                TENANT_ID, BATCH_ID, "stale", CHAIN_TRANSACTION_HASH, CHAIN_ROOT, "CHAIN_WRITE"))
                .thenReturn(0);

        boolean actual = service.confirm(
                TENANT_ID, BATCH_ID, "stale", CHAIN_TRANSACTION_HASH, CHAIN_ROOT, "CHAIN_WRITE");

        assertThat(actual).isFalse();
        verify(attemptMapper).updateResult(
                TENANT_ID,
                BATCH_ID,
                "stale",
                "STALE_IGNORED",
                null,
                null,
                null,
                "Claim token no longer owns the batch state");
    }

    /**
     * 验证 batch 已更新但 attempt 审计缺失时立即失败，使真实事务可以整体回滚。
     */
    @Test
    void confirm_shouldFailWhenAttemptAuditCannotBeFinalized() {
        when(batchMapper.confirmSubmission(
                TENANT_ID, BATCH_ID, "claim", CHAIN_TRANSACTION_HASH, CHAIN_ROOT, "CHAIN_WRITE"))
                .thenReturn(1);
        when(attemptMapper.updateResult(
                TENANT_ID, BATCH_ID, "claim", "COMPLETED", "CHAIN_WRITE",
                CHAIN_TRANSACTION_HASH, CHAIN_ROOT, null))
                .thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.confirm(
                        TENANT_ID, BATCH_ID, "claim", CHAIN_TRANSACTION_HASH, CHAIN_ROOT, "CHAIN_WRITE"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("attempt audit row");
    }

    /**
     * 验证退避迁移会同时更新 batch 和 attempt，并按数据库列长度截断错误摘要。
     */
    @Test
    void retry_shouldUpdateBatchAndAttemptAtomically() {
        Date retryAt = Date.from(Instant.parse("2026-07-14T00:05:00Z"));
        String error = "e".repeat(1_100);
        when(batchMapper.scheduleRetry(
                TENANT_ID, BATCH_ID, "claim", "e".repeat(512), retryAt))
                .thenReturn(1);
        when(attemptMapper.updateResult(
                TENANT_ID,
                BATCH_ID,
                "claim",
                "RETRY_SCHEDULED",
                null,
                null,
                null,
                "e".repeat(1_024)))
                .thenReturn(1);

        boolean actual = service.retry(TENANT_ID, BATCH_ID, "claim", error, retryAt);

        assertThat(actual).isTrue();
        verify(attemptMapper).updateResult(
                TENANT_ID,
                BATCH_ID,
                "claim",
                "RETRY_SCHEDULED",
                null,
                null,
                null,
                "e".repeat(1_024));
    }

    /**
     * 验证人工处理终态和链上冲突根会写入同一 attempt 审计事务。
     */
    @Test
    void manualReview_shouldUpdateBatchAndAttemptAtomically() {
        when(batchMapper.markManualReview(
                TENANT_ID, BATCH_ID, "claim", "chain mismatch", "CHAIN_QUERY_BEFORE_WRITE"))
                .thenReturn(1);
        when(attemptMapper.updateResult(
                TENANT_ID,
                BATCH_ID,
                "claim",
                "MANUAL_REVIEW",
                "CHAIN_QUERY_BEFORE_WRITE",
                null,
                "other-root",
                "chain mismatch"))
                .thenReturn(1);

        boolean actual = service.manualReview(
                TENANT_ID,
                BATCH_ID,
                "claim",
                "chain mismatch",
                "CHAIN_QUERY_BEFORE_WRITE",
                "other-root");

        assertThat(actual).isTrue();
        verify(attemptMapper).updateResult(
                TENANT_ID,
                BATCH_ID,
                "claim",
                "MANUAL_REVIEW",
                "CHAIN_QUERY_BEFORE_WRITE",
                null,
                "other-root",
                "chain mismatch");
    }

    /**
     * 创建两个叶子的确定性测试 Merkle 树。
     */
    private MerkleTreeResult sampleTree() {
        return new MerkleTreeService().buildTree(List.of(
                new MerkleLeafInput(11L, "hash-b"),
                new MerkleLeafInput(12L, "hash-a")));
    }

    /**
     * 创建一个由当前 claim 持有的 manifest 证据候选。
     */
    private AttestationBatchCandidate productionCandidate(Long id, Long fileId, String evidenceHash) {
        return new AttestationBatchCandidate()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setFileId(fileId)
                .setFileVersion(2)
                .setManifestId(1_000L + fileId)
                .setEvidenceType("MANIFEST_HASH")
                .setEvidenceHash(evidenceHash)
                .setChainRecordId("chain-" + fileId)
                .setStatus("CLAIMED")
                .setClaimToken("claim-production")
                .setAttemptCount(1);
    }

    /**
     * 创建字段完整且带部署交易证据的 Sharing registry 测试快照。
     */
    private ContractRegistryEntryResponse contractRegistry() {
        return contractRegistry(
                "record-platform-contract-registry-entry.v1",
                "0x1111111111111111111111111111111111111111",
                "ABI-CANONICAL-JSON-SHA256-V1",
                "0x" + "a".repeat(64),
                42L,
                "ACTIVE",
                "REDEPLOY_ADDRESS");
    }

    /**
     * 按结构敏感字段构造并重新计算指纹的 Sharing registry 测试快照。
     */
    private static ContractRegistryEntryResponse contractRegistry(
            String schemaVersion,
            String contractAddress,
            String abiFingerprintAlgorithm,
            String deploymentTransactionHash,
            Long deploymentBlockNumber,
            String status,
            String upgradeStrategy
    ) {
        return new ContractRegistryEntryResponse(
                schemaVersion,
                null,
                "Sharing",
                "2.0.0",
                "LOCAL_FISCO",
                "chain0",
                "group0",
                contractAddress,
                abiFingerprintAlgorithm,
                "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                "sha256:" + "4".repeat(64),
                deploymentTransactionHash,
                deploymentBlockNumber,
                status,
                "2026-07-13T00:00:00Z",
                upgradeStrategy).withCalculatedRegistryFingerprint();
    }

    /**
     * 提供指纹自洽但分别违反 schema、ABI、地址、部署证据和升级策略的攻击样本。
     */
    private static Stream<Arguments> structurallyInvalidContractRegistries() {
        String validAddress = "0x1111111111111111111111111111111111111111";
        String validAbiAlgorithm = "ABI-CANONICAL-JSON-SHA256-V1";
        String validTransactionHash = "0x" + "a".repeat(64);
        return Stream.of(
                Arguments.of("invalid schema", contractRegistry(
                        "record-platform-contract-registry.entry.v1",
                        validAddress,
                        validAbiAlgorithm,
                        validTransactionHash,
                        42L,
                        "ACTIVE",
                        "REDEPLOY_ADDRESS")),
                Arguments.of("invalid ABI algorithm", contractRegistry(
                        "record-platform-contract-registry-entry.v1",
                        validAddress,
                        "SHA-256",
                        validTransactionHash,
                        42L,
                        "ACTIVE",
                        "REDEPLOY_ADDRESS")),
                Arguments.of("invalid contract address", contractRegistry(
                        "record-platform-contract-registry-entry.v1",
                        "0x1234",
                        validAbiAlgorithm,
                        validTransactionHash,
                        42L,
                        "ACTIVE",
                        "REDEPLOY_ADDRESS")),
                Arguments.of("zero contract address", contractRegistry(
                        "record-platform-contract-registry-entry.v1",
                        "0x0000000000000000000000000000000000000000",
                        validAbiAlgorithm,
                        validTransactionHash,
                        42L,
                        "ACTIVE",
                        "REDEPLOY_ADDRESS")),
                Arguments.of("unpaired deployment evidence", contractRegistry(
                        "record-platform-contract-registry-entry.v1",
                        validAddress,
                        validAbiAlgorithm,
                        null,
                        42L,
                        "ACTIVE",
                        "REDEPLOY_ADDRESS")),
                Arguments.of("invalid deployment transaction", contractRegistry(
                        "record-platform-contract-registry-entry.v1",
                        validAddress,
                        validAbiAlgorithm,
                        "0x-deploy-tx",
                        42L,
                        "ACTIVE",
                        "REDEPLOY_ADDRESS")),
                Arguments.of("invalid upgrade strategy", contractRegistry(
                        "record-platform-contract-registry-entry.v1",
                        validAddress,
                        validAbiAlgorithm,
                        validTransactionHash,
                        42L,
                        "ACTIVE",
                        "IMMUTABLE")));
    }

    /**
     * 构造字段已漂移但仍携带旧指纹的 registry，用于验证失败关闭路径。
     */
    private ContractRegistryEntryResponse contractRegistryWithStaleFingerprint() {
        ContractRegistryEntryResponse valid = contractRegistry();
        return new ContractRegistryEntryResponse(
                valid.schemaVersion(),
                valid.registryFingerprint(),
                valid.contractName(),
                valid.semanticVersion(),
                valid.chainType(),
                valid.chainId(),
                valid.groupId(),
                "0x9999999999999999999999999999999999999999",
                valid.abiFingerprintAlgorithm(),
                valid.abiSha256(),
                valid.artifactBytecodeSha256(),
                valid.onChainCodeSha256(),
                valid.deploymentTransactionHash(),
                valid.deploymentBlockNumber(),
                valid.status(),
                valid.effectiveAt(),
                valid.upgradeStrategy());
    }

    /**
     * 把 registry 的完整 JSON 和反规范化字段写入一个测试 batch。
     */
    private AttestationBatch batchWithRegistry(ContractRegistryEntryResponse registry) {
        return new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setContractRegistryFingerprint(registry.registryFingerprint())
                .setContractRegistryJson(JsonConverter.toJson(registry))
                .setChainType(registry.chainType())
                .setChainId(registry.chainId())
                .setChainGroupId(registry.groupId())
                .setContractName(registry.contractName())
                .setContractVersion(registry.semanticVersion())
                .setContractAddress(registry.contractAddress())
                .setContractAbiSha256(registry.abiSha256())
                .setContractArtifactBytecodeSha256(registry.artifactBytecodeSha256())
                .setContractCodeSha256(registry.onChainCodeSha256())
                .setContractStatus(registry.status());
    }
}
