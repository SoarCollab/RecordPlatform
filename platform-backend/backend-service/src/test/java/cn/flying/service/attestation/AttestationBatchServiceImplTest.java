package cn.flying.service.attestation;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationBatchCandidate;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.request.GetAttestationBatchRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.GetAttestationBatchResponse;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.builders.BuilderResetExtension;
import cn.flying.test.builders.FileTestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(BuilderResetExtension.class)
@DisplayName("AttestationBatchServiceImpl")
class AttestationBatchServiceImplTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final long BATCH_ID = 900L;
    private static final String CHAIN_TRANSACTION_HASH = "e".repeat(64);

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private AttestationBatchPersistenceService persistenceService;

    private MerkleTreeService merkleTreeService;
    private AttestationBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        merkleTreeService = new MerkleTreeService();
        service = new AttestationBatchServiceImpl(
                fileMapper,
                merkleTreeService,
                fileRemoteClient,
                persistenceService,
                new AttestationBatchIdempotencyKey());
        lenient().when(fileRemoteClient.getContractRegistry())
                .thenReturn(Result.success(List.of(contractRegistry())));
        lenient().when(persistenceService.requireContractRegistry(any()))
                .thenReturn(contractRegistry());
        TenantContext.setTenantId(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证创建事务提交后先查询链，再执行一次写入并确认完成。
     */
    @Test
    void createBatch_shouldPersistThenConfirmThroughChainStateMachine() {
        MerkleTreeResult tree = stubFilesAndTree();
        AttestationBatch pending = pendingBatch(tree).setClaimToken(null);
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-1").setAttemptCount(1);
        AttestationBatch completed = pendingBatch(tree).setStatus("COMPLETED")
                .setChainTransactionHash(CHAIN_TRANSACTION_HASH).setChainFileHash(tree.merkleRoot());

        when(persistenceService.createOrGet(
                eq(TENANT_ID), anyString(), any(), any(), eq(contractRegistry())))
                .thenReturn(pending);
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any(GetAttestationBatchRequest.class)))
                .thenReturn(Result.success(GetAttestationBatchResponse.notFound(TENANT_ID, BATCH_ID)));
        when(fileRemoteClient.storeAttestationBatch(any(StoreAttestationBatchRequest.class)))
                .thenReturn(Result.success(new StoreAttestationBatchResponse(
                        CHAIN_TRANSACTION_HASH,
                        tree.merkleRoot())));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(completed));

        AttestationBatch actual = service.createBatch(USER_ID, List.of(11L, 12L));

        assertThat(actual.getStatus()).isEqualTo("COMPLETED");
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).createOrGet(
                eq(TENANT_ID), keyCaptor.capture(), any(), any(), eq(contractRegistry()));
        assertThat(keyCaptor.getValue()).hasSize(64);
        verify(persistenceService).confirm(
                TENANT_ID, BATCH_ID, "claim-1", CHAIN_TRANSACTION_HASH,
                tree.merkleRoot(), "CHAIN_WRITE");
    }

    /**
     * 验证非空但格式非法的链写交易哈希不会直接确认，而会进入写后对账与重试。
     */
    @Test
    void submitBatch_shouldNotConfirmMalformedChainWriteTransactionHash() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-invalid-tx").setAttemptCount(1);
        AttestationBatch retry = pendingBatch(tree).setStatus("CHAIN_RETRY");
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any()))
                .thenReturn(
                        Result.success(GetAttestationBatchResponse.notFound(TENANT_ID, BATCH_ID)),
                        Result.success(GetAttestationBatchResponse.notFound(TENANT_ID, BATCH_ID)));
        when(fileRemoteClient.storeAttestationBatch(any()))
                .thenReturn(Result.success(new StoreAttestationBatchResponse("tx-root", tree.merkleRoot())));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(retry));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("CHAIN_RETRY");
        verify(persistenceService, never()).confirm(any(), any(), any(), any(), any(), any());
        verify(persistenceService).retry(
                eq(TENANT_ID), eq(BATCH_ID), eq("claim-invalid-tx"), anyString(), any(Date.class));
    }

    /**
     * 验证链写失败且写后查无记录时持久化退避状态，不回滚本地 batch 或伪造成功。
     */
    @Test
    void submitBatch_shouldScheduleRetryWhenWriteFailsBeforeCommit() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-1").setAttemptCount(1);
        AttestationBatch retry = pendingBatch(tree).setStatus("CHAIN_RETRY").setAttemptCount(1);
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any()))
                .thenReturn(Result.success(GetAttestationBatchResponse.notFound(TENANT_ID, BATCH_ID)));
        when(fileRemoteClient.storeAttestationBatch(any()))
                .thenReturn(Result.error(ResultEnum.CONTRACT_ERROR, null));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(retry));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("CHAIN_RETRY");
        verify(persistenceService).retry(
                eq(TENANT_ID), eq(BATCH_ID), eq("claim-1"), anyString(), any(Date.class));
        verify(persistenceService, never()).confirm(any(), any(), any(), any(), any(), any());
    }

    /**
     * 验证链已提交但响应丢失时通过写后查询恢复完成，且不执行第二次写入。
     */
    @Test
    void submitBatch_shouldRecoverWhenWriteResponseIsLostAfterCommit() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-1").setAttemptCount(1);
        AttestationBatch completed = pendingBatch(tree).setStatus("COMPLETED");
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any()))
                .thenReturn(
                        Result.success(GetAttestationBatchResponse.notFound(TENANT_ID, BATCH_ID)),
                        Result.success(chainBatch(tree)));
        when(fileRemoteClient.storeAttestationBatch(any()))
                .thenReturn(Result.error(ResultEnum.BLOCKCHAIN_TIMEOUT, null));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(completed));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("COMPLETED");
        verify(persistenceService).confirm(
                TENANT_ID, BATCH_ID, "claim-1", null, tree.merkleRoot(), "CHAIN_QUERY_AFTER_WRITE");
    }

    /**
     * 验证重启恢复时提交前已查到一致记录，直接确认且绝不重复链写。
     */
    @Test
    void submitBatch_shouldConfirmPreExistingChainRecordWithoutWritingAgain() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-2").setAttemptCount(2);
        AttestationBatch completed = pendingBatch(tree).setStatus("COMPLETED");
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any())).thenReturn(Result.success(chainBatch(tree)));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(completed));

        service.submitBatch(BATCH_ID);

        verify(fileRemoteClient, never()).storeAttestationBatch(any());
        verify(persistenceService).confirm(
                TENANT_ID, BATCH_ID, "claim-2", null, tree.merkleRoot(), "CHAIN_QUERY_BEFORE_WRITE");
    }

    /**
     * 验证链上业务键已被不同内容占用时进入人工处理，不覆盖本地证明元数据。
     */
    @Test
    void submitBatch_shouldRequireManualReviewForChainDataMismatch() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-1").setAttemptCount(1);
        AttestationBatch manual = pendingBatch(tree).setStatus("MANUAL_REVIEW");
        GetAttestationBatchResponse mismatch = new GetAttestationBatchResponse(
                true, TENANT_ID, BATCH_ID, "MB-900", tree.proofAlgorithm(), "f".repeat(64), 2, 1L);
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any())).thenReturn(Result.success(mismatch));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(manual));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("MANUAL_REVIEW");
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
        verify(persistenceService).manualReview(
                eq(TENANT_ID), eq(BATCH_ID), eq("claim-1"), anyString(),
                eq("CHAIN_QUERY_BEFORE_WRITE"), eq("f".repeat(64)));
    }

    /**
     * 验证链查询故障不是“不存在”，因此不会在未知状态下发起写入。
     */
    @Test
    void submitBatch_shouldNotWriteWhenPreflightQueryFails() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-1").setAttemptCount(1);
        AttestationBatch retry = pendingBatch(tree).setStatus("CHAIN_RETRY");
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any()))
                .thenReturn(Result.error(ResultEnum.BLOCKCHAIN_UNREACHABLE, null));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(retry));

        service.submitBatch(BATCH_ID);

        verify(fileRemoteClient, never()).storeAttestationBatch(any());
        verify(persistenceService).retry(
                eq(TENANT_ID), eq(BATCH_ID), eq("claim-1"), anyString(), any(Date.class));
    }

    /**
     * 验证 registry RPC 的瞬时异常只安排有界重试，绝不在未知合约身份下查询或写链。
     */
    @Test
    void submitBatch_shouldRetryWhenContractRegistryLookupIsTransient() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-registry-retry").setAttemptCount(1);
        AttestationBatch retry = pendingBatch(tree).setStatus("CHAIN_RETRY");
        when(persistenceService.claim(
                eq(TENANT_ID),
                eq(BATCH_ID),
                anyString(),
                any(Date.class),
                any(Date.class),
                eq(AttestationBatchServiceImpl.MAX_ATTEMPTS)))
                .thenReturn(Optional.of(claimed));
        when(fileRemoteClient.getContractRegistry())
                .thenThrow(new IllegalStateException("registry unavailable"));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(retry));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("CHAIN_RETRY");
        verify(persistenceService).retry(
                eq(TENANT_ID),
                eq(BATCH_ID),
                eq("claim-registry-retry"),
                anyString(),
                any(Date.class));
        verify(persistenceService, never()).verifyContractRegistryClaim(any(), any(), any(), any());
        verify(fileRemoteClient, never()).getAttestationBatch(any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证 claim 内快照不再等于 provider ACTIVE 条目时转人工处理，不能跨合约重试。
     */
    @Test
    void submitBatch_shouldRequireManualReviewForStaleContractRegistrySnapshot() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-registry-stale").setAttemptCount(1);
        AttestationBatch manual = pendingBatch(tree).setStatus("MANUAL_REVIEW");
        when(persistenceService.claim(
                eq(TENANT_ID),
                eq(BATCH_ID),
                anyString(),
                any(Date.class),
                any(Date.class),
                eq(AttestationBatchServiceImpl.MAX_ATTEMPTS)))
                .thenReturn(Optional.of(claimed));
        when(persistenceService.verifyContractRegistryClaim(
                TENANT_ID,
                BATCH_ID,
                "claim-registry-stale",
                contractRegistry())).thenReturn(Optional.empty());
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(manual));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("MANUAL_REVIEW");
        verify(persistenceService).manualReview(
                eq(TENANT_ID),
                eq(BATCH_ID),
                eq("claim-registry-stale"),
                anyString(),
                eq(null),
                eq(null));
        verify(fileRemoteClient, never()).getAttestationBatch(any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证 provider 返回字段与 registry fingerprint 不一致时禁止创建批次。
     */
    @Test
    void createBatch_shouldRejectContractRegistryFingerprintMismatch() {
        ContractRegistryEntryResponse registry = contractRegistry();
        ContractRegistryEntryResponse tampered = new ContractRegistryEntryResponse(
                registry.schemaVersion(),
                registry.registryFingerprint(),
                registry.contractName(),
                registry.semanticVersion(),
                registry.chainType(),
                registry.chainId(),
                registry.groupId(),
                "0x2222222222222222222222222222222222222222",
                registry.abiFingerprintAlgorithm(),
                registry.abiSha256(),
                registry.artifactBytecodeSha256(),
                registry.onChainCodeSha256(),
                registry.deploymentTransactionHash(),
                registry.deploymentBlockNumber(),
                registry.status(),
                registry.effectiveAt(),
                registry.upgradeStrategy());
        assertCreationRejectsContractRegistry(tampered);
    }

    /**
     * 验证 ACTIVE registry RPC 混入历史 Sharing 条目时按接口违约失败关闭，不能静默过滤。
     */
    @Test
    void createBatch_shouldRejectHistoricalSharingEntryInActiveRegistryResponse() {
        ContractRegistryEntryResponse active = contractRegistry();
        ContractRegistryEntryResponse deprecated = new ContractRegistryEntryResponse(
                active.schemaVersion(),
                null,
                active.contractName(),
                "1.9.0",
                active.chainType(),
                active.chainId(),
                active.groupId(),
                active.contractAddress(),
                active.abiFingerprintAlgorithm(),
                active.abiSha256(),
                active.artifactBytecodeSha256(),
                active.onChainCodeSha256(),
                active.deploymentTransactionHash(),
                active.deploymentBlockNumber(),
                "DEPRECATED",
                active.effectiveAt(),
                active.upgradeStrategy())
                .withCalculatedRegistryFingerprint();
        stubFilesAndTree();
        when(fileRemoteClient.getContractRegistry()).thenReturn(Result.success(List.of(active, deprecated)));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L, 12L)))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class)
                .satisfies(ex -> assertThat(((cn.flying.common.exception.GeneralException) ex).getResultEnum())
                        .isEqualTo(cn.flying.common.constant.ResultEnum.BLOCKCHAIN_ERROR));

        verify(persistenceService, never()).createOrGet(any(), any(), any(), any(), any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证自洽但格式非法的部署交易哈希不能进入批次快照。
     */
    @Test
    void createBatch_shouldRejectMalformedRegistryDeploymentTransactionHash() {
        ContractRegistryEntryResponse registry = contractRegistry(
                "0x1234", 42L, "2026-07-13T00:00:00Z");

        assertCreationRejectsContractRegistry(registry);
    }

    /**
     * 验证非 RFC 3339 生效时间即使能被宽松解析也不能进入批次快照。
     */
    @Test
    void createBatch_shouldRejectNonRfc3339RegistryEffectiveAt() {
        ContractRegistryEntryResponse registry = contractRegistry(
                null, null, "2026-07-13T00:00:00+00");

        assertCreationRejectsContractRegistry(registry);
    }

    /**
     * 验证第五次仍无法确认时停止自动重试并进入人工处理终态。
     */
    @Test
    void submitBatch_shouldStopAfterMaximumAttempts() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-5").setAttemptCount(5);
        AttestationBatch manual = pendingBatch(tree).setStatus("MANUAL_REVIEW");
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any()))
                .thenReturn(Result.error(ResultEnum.BLOCKCHAIN_UNREACHABLE, null));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(manual));

        service.submitBatch(BATCH_ID);

        verify(persistenceService, never()).retry(any(), any(), any(), any(), any());
        verify(persistenceService).manualReview(
                eq(TENANT_ID), eq(BATCH_ID), eq("claim-5"), anyString(), eq(null), eq(null));
    }

    /**
     * 验证第五次 worker 崩溃后的额外领取只允许对账，链上仍不存在时不会发起第六次写入。
     */
    @Test
    void submitBatch_shouldOnlyReconcileExpiredLeaseAfterWriteLimit() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch claimed = pendingBatch(tree).setStatus("CHAIN_SUBMITTING")
                .setClaimToken("claim-recovery").setAttemptCount(6);
        AttestationBatch manual = pendingBatch(tree).setStatus("MANUAL_REVIEW");
        stubClaim(claimed);
        when(fileRemoteClient.getAttestationBatch(any()))
                .thenReturn(Result.success(GetAttestationBatchResponse.notFound(TENANT_ID, BATCH_ID)));
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(manual));

        service.submitBatch(BATCH_ID);

        verify(fileRemoteClient, never()).storeAttestationBatch(any());
        verify(persistenceService).manualReview(
                eq(TENANT_ID), eq(BATCH_ID), eq("claim-recovery"), anyString(), eq(null), eq(null));
    }

    /**
     * 验证并发 worker 未取得 claim 时只返回当前状态，不调用任何链 RPC。
     */
    @Test
    void submitBatch_shouldDoNothingWhenAnotherWorkerOwnsClaim() {
        MerkleTreeResult tree = sampleTree();
        AttestationBatch submitting = pendingBatch(tree).setStatus("CHAIN_SUBMITTING");
        when(persistenceService.claim(eq(TENANT_ID), eq(BATCH_ID), anyString(), any(), any(), anyInt()))
                .thenReturn(Optional.empty());
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(submitting));

        AttestationBatch actual = service.submitBatch(BATCH_ID);

        assertThat(actual.getStatus()).isEqualTo("CHAIN_SUBMITTING");
        verify(fileRemoteClient, never()).getAttestationBatch(any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证创建数据库事务失败时不会执行任何链调用。
     */
    @Test
    void createBatch_shouldNotCallChainWhenDatabaseCreationFails() {
        stubFilesAndTree();
        when(persistenceService.createOrGet(
                eq(TENANT_ID), anyString(), any(), any(), eq(contractRegistry())))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L, 12L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database unavailable");

        verify(fileRemoteClient, never()).getAttestationBatch(any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证并发幂等唯一键竞争时复用已提交批次。
     */
    @Test
    void createBatch_shouldRecoverDuplicateIdempotencyRace() {
        MerkleTreeResult tree = stubFilesAndTree();
        AttestationBatch completed = pendingBatch(tree).setStatus("COMPLETED");
        when(persistenceService.createOrGet(
                eq(TENANT_ID), anyString(), any(), any(), eq(contractRegistry())))
                .thenThrow(new DuplicateKeyException("duplicate idempotency key"));
        when(persistenceService.findByIdempotencyKey(eq(TENANT_ID), anyString()))
                .thenReturn(Optional.of(completed));

        AttestationBatch actual = service.createBatch(USER_ID, List.of(12L, 11L));

        assertThat(actual).isSameAs(completed);
        verify(persistenceService, never()).claim(any(), any(), any(), any(), any(), anyInt());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证生产创建只使用 manifest evidence，并在本地事务完成后由调用方单独提交链写。
     */
    @Test
    void createProductionBatch_shouldPersistManifestEvidenceWithoutCallingChain() {
        String firstManifestHash = "sha256:" + "a".repeat(64);
        String secondManifestHash = "sha256:" + "b".repeat(64);
        AttestationCandidateClaim claim = new AttestationCandidateClaim(
                TENANT_ID,
                "claim-production",
                List.of(
                        claimedCandidate(11L, 101L, firstManifestHash),
                        claimedCandidate(12L, 102L, secondManifestHash)));
        AttestationBatch pending = new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setStatus("CHAIN_PENDING");
        when(persistenceService.createFromClaimedCandidates(
                eq(TENANT_ID), anyString(), any(), eq(claim), eq(contractRegistry())))
                .thenReturn(pending);
        when(persistenceService.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(pending));

        AttestationBatch actual = service.createProductionBatch(claim);

        assertThat(actual).isSameAs(pending);
        ArgumentCaptor<MerkleTreeResult> treeCaptor = ArgumentCaptor.forClass(MerkleTreeResult.class);
        verify(persistenceService).createFromClaimedCandidates(
                eq(TENANT_ID),
                anyString(),
                treeCaptor.capture(),
                eq(claim),
                eq(contractRegistry()));
        assertThat(treeCaptor.getValue().leaves())
                .extracting(MerkleLeafProof::fileHash)
                .containsExactly(firstManifestHash, secondManifestHash);
        verify(fileRemoteClient, never()).getAttestationBatch(any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证持久化 candidate 被篡改为非 canonical manifest hash 时不会创建 batch。
     */
    @Test
    void createProductionBatch_shouldRejectNonCanonicalManifestEvidence() {
        AttestationCandidateClaim claim = new AttestationCandidateClaim(
                TENANT_ID,
                "claim-production",
                List.of(claimedCandidate(11L, 101L, "SHA256:" + "A".repeat(64))));

        assertThatThrownBy(() -> service.createProductionBatch(claim))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class)
                .satisfies(ex -> assertThat(((cn.flying.common.exception.GeneralException) ex).getResultEnum())
                        .isEqualTo(cn.flying.common.constant.ResultEnum.FILE_RECORD_ERROR));

        verify(persistenceService, never()).createFromClaimedCandidates(
                any(), any(), any(), any(), any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证缺少租户归属的文件按失败关闭处理，不能进入批量存证。
     */
    @Test
    void createBatch_shouldRejectFileWithoutTenantOwnership() {
        File file = successfulFile(11L, "hash-a").setTenantId(null);
        when(fileMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(file));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L)))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class)
                .satisfies(ex -> assertThat(((cn.flying.common.exception.GeneralException) ex).getResultEnum())
                        .isEqualTo(cn.flying.common.constant.ResultEnum.PERMISSION_UNAUTHORIZED));

        verify(persistenceService, never()).createOrGet(any(), any(), any(), any(), any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证缺少用户归属的文件不能被带用户身份的调用方纳入批量存证。
     */
    @Test
    void createBatch_shouldRejectFileWithoutUserOwnership() {
        File file = successfulFile(11L, "hash-a").setUid(null);
        when(fileMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(file));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L)))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class)
                .satisfies(ex -> assertThat(((cn.flying.common.exception.GeneralException) ex).getResultEnum())
                        .isEqualTo(cn.flying.common.constant.ResultEnum.PERMISSION_UNAUTHORIZED));

        verify(persistenceService, never()).createOrGet(any(), any(), any(), any(), any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证上传未完成的文件不能进入 Merkle batch，避免为不稳定内容签发证明。
     */
    @Test
    void createBatch_shouldRejectNonSuccessfulFiles() {
        File file = successfulFile(11L, "hash-a").setStatus(FileUploadStatus.PREPARE.getCode());
        when(fileMapper.selectBatchIds(List.of(11L))).thenReturn(List.of(file));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L)))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class)
                .satisfies(ex -> assertThat(((cn.flying.common.exception.GeneralException) ex).getResultEnum())
                        .isEqualTo(cn.flying.common.constant.ResultEnum.FILE_RECORD_ERROR));

        verify(persistenceService, never()).createOrGet(any(), any(), any(), any(), any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 验证所有公开编排入口都会暂停调用方已有事务。
     */
    @Test
    void publicOrchestrationMethods_shouldUseNotSupportedPropagation() throws Exception {
        for (Method method : List.of(
                AttestationBatchServiceImpl.class.getMethod("createBatch", Long.class, List.class),
                AttestationBatchServiceImpl.class.getMethod("createProductionBatch", AttestationCandidateClaim.class),
                AttestationBatchServiceImpl.class.getMethod("submitBatch", Long.class))) {
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(transactional).isNotNull();
            assertThat(transactional.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);
        }
    }

    /**
     * 为测试配置一个成功文件集合并返回对应规范化 Merkle 树。
     */
    private MerkleTreeResult stubFilesAndTree() {
        File first = successfulFile(11L, "hash-b");
        File second = successfulFile(12L, "hash-a");
        when(fileMapper.selectBatchIds(any())).thenReturn(List.of(first, second));
        return merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(11L, "hash-b"),
                new MerkleLeafInput(12L, "hash-a")));
    }

    /**
     * 创建不依赖 mapper stub 的固定测试 Merkle 树。
     */
    private MerkleTreeResult sampleTree() {
        return merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(11L, "hash-b"),
                new MerkleLeafInput(12L, "hash-a")));
    }

    /**
     * 配置原子领取返回指定 batch。
     */
    private void stubClaim(AttestationBatch claimed) {
        when(persistenceService.claim(eq(TENANT_ID), eq(BATCH_ID), anyString(), any(Date.class),
                any(Date.class), eq(AttestationBatchServiceImpl.MAX_ATTEMPTS)))
                .thenReturn(Optional.of(claimed));
        when(persistenceService.verifyContractRegistryClaim(
                eq(TENANT_ID),
                eq(BATCH_ID),
                eq(claimed.getClaimToken()),
                eq(contractRegistry())))
                .thenReturn(Optional.of(claimed));
    }

    /**
     * 创建包含不可变 Merkle 内容的待提交测试 batch。
     */
    private AttestationBatch pendingBatch(MerkleTreeResult tree) {
        return new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setBatchNo("MB-900")
                .setMerkleRoot(tree.merkleRoot())
                .setProofAlgorithm(tree.proofAlgorithm())
                .setLeafCount(tree.leaves().size())
                .setStatus("CHAIN_PENDING")
                .setAttemptCount(0)
                .setDeleted(0);
    }

    /**
     * 创建 provider 已核验且可绑定到批次的 Sharing 注册表快照。
     */
    private ContractRegistryEntryResponse contractRegistry() {
        return contractRegistry(null, null, "2026-07-13T00:00:00Z");
    }

    /**
     * 创建可覆盖部署证据和生效时间的自洽注册表测试快照。
     */
    private ContractRegistryEntryResponse contractRegistry(
            String deploymentTransactionHash,
            Long deploymentBlockNumber,
            String effectiveAt
    ) {
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
                deploymentTransactionHash,
                deploymentBlockNumber,
                "ACTIVE",
                effectiveAt,
                "REDEPLOY_ADDRESS")
                .withCalculatedRegistryFingerprint();
    }

    /**
     * 断言非法注册表在本地持久化和链 RPC 之前失败关闭。
     */
    private void assertCreationRejectsContractRegistry(ContractRegistryEntryResponse registry) {
        stubFilesAndTree();
        when(fileRemoteClient.getContractRegistry())
                .thenReturn(Result.success(List.of(registry)));

        assertThatThrownBy(() -> service.createBatch(USER_ID, List.of(11L, 12L)))
                .isInstanceOf(cn.flying.common.exception.GeneralException.class)
                .satisfies(ex -> assertThat(((cn.flying.common.exception.GeneralException) ex).getResultEnum())
                        .isEqualTo(cn.flying.common.constant.ResultEnum.BLOCKCHAIN_ERROR));

        verify(persistenceService, never()).createOrGet(any(), any(), any(), any(), any());
        verify(fileRemoteClient, never()).storeAttestationBatch(any());
    }

    /**
     * 创建与本地 batch 完全匹配的链查询响应。
     */
    private GetAttestationBatchResponse chainBatch(MerkleTreeResult tree) {
        return new GetAttestationBatchResponse(
                true,
                TENANT_ID,
                BATCH_ID,
                "MB-900",
                tree.proofAlgorithm(),
                tree.merkleRoot(),
                tree.leaves().size(),
                1_700_000_000_000L);
    }

    /**
     * 创建可进入批量存证的测试文件。
     */
    private File successfulFile(Long id, String hash) {
        return FileTestBuilder.aFile(file -> file
                .setId(id)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileHash(hash)
                .setStatus(FileUploadStatus.SUCCESS.getCode()));
    }

    /**
     * 创建一个与 production claim token 和 manifest 证据绑定的候选。
     */
    private AttestationBatchCandidate claimedCandidate(Long fileId, Long manifestId, String evidenceHash) {
        return new AttestationBatchCandidate()
                .setId(fileId + 1_000L)
                .setTenantId(TENANT_ID)
                .setFileId(fileId)
                .setFileVersion(2)
                .setManifestId(manifestId)
                .setEvidenceType("MANIFEST_HASH")
                .setEvidenceHash(evidenceHash)
                .setChainRecordId("chain-" + fileId)
                .setStatus("CLAIMED")
                .setClaimToken("claim-production")
                .setAttemptCount(1);
    }
}
