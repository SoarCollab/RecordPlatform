package cn.flying.test.proof;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.entity.ProofSigningKeyRecord;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ProofSigningKeyMapper;
import cn.flying.dao.vo.file.ProofStatusVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.FileService;
import cn.flying.service.QuotaService;
import cn.flying.service.attestation.AttestationBatchPersistenceService;
import cn.flying.service.attestation.AttestationLeafEvidence;
import cn.flying.service.attestation.MerkleLeafInput;
import cn.flying.service.attestation.MerkleTreeResult;
import cn.flying.service.attestation.MerkleTreeService;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.proof.signed.LocalEd25519ProofSigningProvider;
import cn.flying.service.proof.signed.ProofArchive;
import cn.flying.service.proof.signed.ProofSigningKeyMetadata;
import cn.flying.service.proof.signed.ProofSigningProperties;
import cn.flying.service.proof.signed.SignedProofArchiveService;
import cn.flying.test.BaseIntegrationTest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 使用真实 MySQL、Redis 和生产 service 入口验证 signed proof 生命周期并发边界。
 */
@Execution(ExecutionMode.SAME_THREAD)
class SignedProofLifecycleConcurrencyIT extends BaseIntegrationTest {

    private static final Long TENANT_ID = 97_140_001L;
    private static final Long USER_ID = 97_140_002L;
    private static final Long FILE_A_ID = 97_140_101L;
    private static final Long FILE_B_ID = 97_140_102L;
    private static final Integer KEY_VERSION = 1;
    private static final String KEY_ID = "it-proof-lifecycle-key";
    private static final String FILE_TABLE = "file";
    private static final String FILE_PRIMARY_INDEX = "PRIMARY";
    private static final String KEY_TABLE = "proof_signing_key";
    private static final String KEY_IDENTITY_INDEX = "uk_proof_signing_key_identity";
    private static final int WAIT_SECONDS = 15;

    @Autowired
    private SignedProofArchiveService signedProofArchiveService;

    @Autowired
    private FileService fileService;

    @Autowired
    private ChunkManifestService chunkManifestService;

    @Autowired
    private AttestationBatchPersistenceService attestationBatchPersistenceService;

    @Autowired
    private MerkleTreeService merkleTreeService;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private AttestationBatchMapper batchMapper;

    @Autowired
    private AttestationLeafMapper leafMapper;

    @Autowired
    private ProofSigningKeyMapper signingKeyMapper;

    @Autowired
    private ProofSigningProperties signingProperties;

    @MockitoSpyBean
    private LocalEd25519ProofSigningProvider signingProvider;

    @MockitoBean
    private QuotaService quotaService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private List<CountDownLatch> releaseLatches;

    /**
     * 清理共享容器数据、配置动态 Ed25519 key，并为每个用例创建独立 worker 池。
     */
    @BeforeEach
    void setUpLifecycleFixtures() throws GeneralSecurityException {
        TenantContext.setTenantId(TENANT_ID);
        cleanUpLifecycleFixtures();
        configureSigningKey();
        executor = Executors.newFixedThreadPool(4);
        releaseLatches = new CopyOnWriteArrayList<>();
    }

    /**
     * 先释放全部并发栅栏并停止 worker，再按外键逆序清理 fixture 和签名配置。
     */
    @AfterEach
    void tearDownLifecycleFixtures() throws InterruptedException {
        if (releaseLatches != null) {
            releaseLatches.forEach(CountDownLatch::countDown);
        }
        boolean terminated = true;
        if (executor != null) {
            executor.shutdownNow();
            terminated = executor.awaitTermination(10, TimeUnit.SECONDS);
        }
        try {
            cleanUpLifecycleFixtures();
        } finally {
            resetSigningKey();
            TenantContext.clear();
        }
        assertThat(terminated).as("并发 worker 必须在清理数据库前全部退出").isTrue();
    }

    /**
     * 验证首签先持有版本组 anchor 时，真实上传会等待并在提交后推进旧 proof 为 SUPERSEDED。
     */
    @Test
    void firstIssuanceAndUploadShouldSerializeOnAnchorAndEndSuperseded() throws Exception {
        ProofFixture fixture = createProofFixture(FILE_A_ID, "u01");
        File versionTwo = fileService.createNewVersion(
                USER_ID, fixture.fileId(), "upload-v2.bin", 3L, "application/octet-stream");
        insertCurrentSigningKey();

        CountDownLatch keyHeld = new CountDownLatch(1);
        CountDownLatch releaseKey = newReleaseLatch();
        Future<Void> keyBlocker = holdSigningKeyRow(keyHeld, releaseKey);
        Future<ProofArchive> proofFuture = null;
        Future<File> uploadFuture = null;
        try {
            awaitLatch(keyHeld, "签名 key 行未被 blocker 锁定");
            proofFuture = executor.submit(() -> inTenant(
                    () -> signedProofArchiveService.exportByFileId(USER_ID, fixture.fileId())));
            awaitDatabaseCondition(
                    () -> hasExclusiveRecordLock(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()),
                    "首签未持有版本组 anchor 行锁");

            uploadFuture = executor.submit(() -> inTenant(
                    () -> completeDirectUpload(versionTwo, "u02")));
            awaitDatabaseCondition(
                    () -> hasRecordLockWait(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()),
                    "上传事务未在版本组 anchor 上形成真实 MySQL 锁等待");

            releaseKey.countDown();
            ProofArchive archive = proofFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            File uploaded = uploadFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            keyBlocker.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertValidArchive(archive);
            assertThat(uploaded.getStatus()).isEqualTo(FileUploadStatus.SUCCESS.getCode());
        } finally {
            releaseKey.countDown();
            cancelIfRunning(proofFuture);
            cancelIfRunning(uploadFuture);
        }

        IssuanceStatus issuance = loadIssuanceStatus(fixture.leafId());
        assertThat(issuance.issuedStatus()).isEqualTo("ACTIVE");
        assertThat(issuance.status()).isEqualTo("SUPERSEDED");
        assertThat(issuance.statusVersion()).isEqualTo(2L);
        assertThat(issuance.statusReason()).isEqualTo("newer_file_version");
        assertThat(loadFileStatus(versionTwo.getId())).isEqualTo(FileUploadStatus.SUCCESS.getCode());
    }

    /**
     * 验证上传先持有 anchor 并提交 SUCCESS 后，等待中的首次签发直接生成 SUPERSEDED 快照。
     */
    @Test
    void uploadFirstShouldMakeWaitingFirstIssuanceStartSuperseded() throws Exception {
        ProofFixture fixture = createProofFixture(FILE_A_ID, "f01");
        File versionTwo = fileService.createNewVersion(
                USER_ID, fixture.fileId(), "upload-first-v2.bin", 3L, "application/octet-stream");

        CountDownLatch versionRowHeld = new CountDownLatch(1);
        CountDownLatch releaseVersionRow = newReleaseLatch();
        Future<Void> versionRowBlocker = holdFileRow(
                versionTwo.getId(), versionRowHeld, releaseVersionRow);
        Future<File> uploadFuture = null;
        Future<ProofArchive> proofFuture = null;
        try {
            awaitLatch(versionRowHeld, "v2 文件行未被 blocker 锁定");
            uploadFuture = executor.submit(() -> inTenant(
                    () -> completeDirectUpload(versionTwo, "f02")));
            awaitDatabaseCondition(
                    () -> hasRecordLockWait(FILE_TABLE, FILE_PRIMARY_INDEX, versionTwo.getId()),
                    "上传事务未在 v2 更新处形成真实 MySQL 锁等待");
            assertThat(hasExclusiveRecordLock(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()))
                    .as("上传在修改 v2 前必须先持有版本组 anchor")
                    .isTrue();

            proofFuture = executor.submit(() -> inTenant(
                    () -> signedProofArchiveService.exportByFileId(USER_ID, fixture.fileId())));
            awaitDatabaseCondition(
                    () -> hasRecordLockWait(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()),
                    "首次签发未在上传持有的版本组 anchor 上形成真实 MySQL 锁等待");

            releaseVersionRow.countDown();
            File uploaded = uploadFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            ProofArchive archive = proofFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            versionRowBlocker.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(uploaded.getStatus()).isEqualTo(FileUploadStatus.SUCCESS.getCode());
            assertValidArchive(archive);
        } finally {
            releaseVersionRow.countDown();
            cancelIfRunning(uploadFuture);
            cancelIfRunning(proofFuture);
        }

        IssuanceStatus issuance = loadIssuanceStatus(fixture.leafId());
        assertThat(issuance.issuedStatus()).isEqualTo("SUPERSEDED");
        assertThat(issuance.status()).isEqualTo("SUPERSEDED");
        assertThat(issuance.statusVersion()).isEqualTo(1L);
        assertThat(issuance.statusReason()).isNull();
        assertThat(loadFileStatus(versionTwo.getId())).isEqualTo(FileUploadStatus.SUCCESS.getCode());
    }

    /**
     * 验证导出读到旧 ACTIVE 快照后，已提交的真实 revoke 会被最终 current-read 观察并拒绝导出。
     */
    @Test
    void concurrentRevokeShouldPreventExportAfterCurrentRead() throws Exception {
        ProofFixture fixture = createProofFixture(FILE_A_ID, "r01");
        assertValidArchive(signedProofArchiveService.exportByFileId(USER_ID, fixture.fileId()));

        CountDownLatch evidenceReached = new CountDownLatch(1);
        CountDownLatch releaseEvidence = newReleaseLatch();
        when(fileRemoteClient.headObject(fixture.storagePath(), fixture.cipherHash()))
                .thenAnswer(invocation -> {
                    evidenceReached.countDown();
                    awaitLatch(releaseEvidence, "存储 HEAD 阻塞点未被释放");
                    return successfulHead(fixture);
                });

        Future<ProofArchive> exportFuture = executor.submit(() -> inTenant(
                () -> signedProofArchiveService.exportByFileId(USER_ID, fixture.fileId())));
        ProofStatusVO revoked;
        try {
            awaitLatch(evidenceReached, "第二次导出未到达存储证据校验点");
            revoked = signedProofArchiveService.revokeByLeafId(
                    USER_ID, fixture.leafId(), "integration revoke");
            releaseEvidence.countDown();

            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> exportFuture.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertThat(failure.getCause())
                    .isInstanceOf(GeneralException.class)
                    .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                            .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
        } finally {
            releaseEvidence.countDown();
            cancelIfRunning(exportFuture);
        }

        assertThat(revoked.status()).isEqualTo("REVOKED");
        assertThat(revoked.statusVersion()).isEqualTo(2L);
        IssuanceStatus issuance = loadIssuanceStatus(fixture.leafId());
        assertThat(issuance.status()).isEqualTo("REVOKED");
        assertThat(issuance.statusVersion()).isEqualTo(2L);
        assertThat(issuance.statusReason()).isEqualTo("integration revoke");
    }

    /**
     * 验证两个独立版本组并发首签相同 key 时，第二事务真实等待唯一键且最终只保留一份身份。
     */
    @Test
    void concurrentIssuancesShouldUpsertOneIdenticalGlobalSigningKey() throws Exception {
        ProofFixture firstFixture = createProofFixture(FILE_A_ID, "k01");
        ProofFixture secondFixture = createProofFixture(FILE_B_ID, "k02");
        CountDownLatch firstReachedSign = new CountDownLatch(1);
        CountDownLatch releaseFirstSign = newReleaseLatch();
        AtomicBoolean firstInvocation = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (firstInvocation.compareAndSet(true, false)) {
                firstReachedSign.countDown();
                awaitLatch(releaseFirstSign, "首个签发事务未被释放");
            }
            return invocation.callRealMethod();
        }).when(signingProvider).sign(any(byte[].class), any(ProofSigningKeyMetadata.class));

        Future<ProofArchive> firstFuture = executor.submit(() -> inTenant(
                () -> signedProofArchiveService.exportByFileId(USER_ID, firstFixture.fileId())));
        Future<ProofArchive> secondFuture = null;
        try {
            awaitLatch(firstReachedSign, "首个签发未在注册 key 后到达签名点");
            secondFuture = executor.submit(() -> inTenant(
                    () -> signedProofArchiveService.exportByFileId(USER_ID, secondFixture.fileId())));
            awaitDatabaseCondition(
                    () -> hasAnyLockWait(KEY_TABLE, KEY_IDENTITY_INDEX),
                    "第二个签发未在全局 key 唯一身份上形成真实 MySQL 锁等待");

            releaseFirstSign.countDown();
            ProofArchive firstArchive = firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            ProofArchive secondArchive = secondFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertValidArchive(firstArchive);
            assertValidArchive(secondArchive);
        } finally {
            releaseFirstSign.countDown();
            cancelIfRunning(firstFuture);
            cancelIfRunning(secondFuture);
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proof_signing_key WHERE key_id = ? AND key_version = ? AND deleted = 0",
                Integer.class,
                KEY_ID,
                KEY_VERSION)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proof_bundle_issuance WHERE tenant_id = ? AND deleted = 0",
                Integer.class,
                TENANT_ID)).isEqualTo(2);

        ProofSigningKeyMetadata expectedKey = signingProvider.currentKey();
        List<IssuanceKeyBinding> bindings = loadIssuanceKeyBindings();
        assertThat(bindings).hasSize(2).allSatisfy(binding -> {
            assertThat(binding.keyId()).isEqualTo(expectedKey.keyId());
            assertThat(binding.keyVersion()).isEqualTo(expectedKey.keyVersion());
            assertThat(binding.publicKeySpki()).isEqualTo(expectedKey.publicKeySpki());
            assertThat(binding.publicKeyFingerprint()).isEqualTo(expectedKey.publicKeyFingerprint());
        });
    }

    /**
     * 验证首签与 createVersion 都遵循 anchor-first 锁序，真实并发下不会形成反向死锁。
     */
    @Test
    void createVersionAndFirstIssuanceShouldUseAnchorFirstWithoutDeadlock() throws Exception {
        ProofFixture fixture = createProofFixture(FILE_A_ID, "c01");
        insertCurrentSigningKey();

        CountDownLatch keyHeld = new CountDownLatch(1);
        CountDownLatch releaseKey = newReleaseLatch();
        Future<Void> keyBlocker = holdSigningKeyRow(keyHeld, releaseKey);
        Future<ProofArchive> proofFuture = null;
        Future<File> createVersionFuture = null;
        try {
            awaitLatch(keyHeld, "签名 key 行未被 blocker 锁定");
            proofFuture = executor.submit(() -> inTenant(
                    () -> signedProofArchiveService.exportByFileId(USER_ID, fixture.fileId())));
            awaitDatabaseCondition(
                    () -> hasExclusiveRecordLock(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()),
                    "首签未持有版本组 anchor 行锁");

            createVersionFuture = executor.submit(() -> inTenant(() -> fileService.createNewVersion(
                    USER_ID,
                    fixture.fileId(),
                    "create-version-v2.bin",
                    3L,
                    "application/octet-stream")));
            awaitDatabaseCondition(
                    () -> hasRecordLockWait(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()),
                    "createVersion 未在版本组 anchor 上形成真实 MySQL 锁等待");

            releaseKey.countDown();
            ProofArchive archive = proofFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            File versionTwo = createVersionFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
            keyBlocker.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertValidArchive(archive);
            assertThat(versionTwo.getStatus()).isEqualTo(FileUploadStatus.PREPARE.getCode());
            assertThat(versionTwo.getVersion()).isEqualTo(2);
            assertThat(versionTwo.getParentVersionId()).isEqualTo(fixture.fileId());
            assertThat(versionTwo.getVersionGroupId()).isEqualTo(fixture.fileId());
        } finally {
            releaseKey.countDown();
            cancelIfRunning(proofFuture);
            cancelIfRunning(createVersionFuture);
        }

        List<File> versionChain = fileMapper.selectVersionChain(fixture.fileId(), TENANT_ID);
        assertThat(versionChain).hasSize(2);
        assertThat(versionChain.get(0).getVersion()).isEqualTo(2);
        assertThat(versionChain.get(0).getIsLatest()).isEqualTo(1);
        assertThat(versionChain.get(1).getVersion()).isEqualTo(1);
        assertThat(versionChain.get(1).getIsLatest()).isEqualTo(0);
        awaitDatabaseCondition(
                () -> !hasRecordLockWait(FILE_TABLE, FILE_PRIMARY_INDEX, fixture.fileId()),
                "并发完成后仍残留版本组 anchor 锁等待");
    }

    /**
     * 验证 composite FK 在数据库边界拒绝跨租户文件和跨文件 leaf 的伪造 issuance 绑定。
     */
    @Test
    void compositeForeignKeysShouldRejectCrossTenantAndCrossFileLeafBindings() {
        ProofFixture firstFixture = createProofFixture(FILE_A_ID, "d01");
        ProofFixture secondFixture = createProofFixture(FILE_B_ID, "d02");
        insertCurrentSigningKey();

        assertThatThrownBy(() -> insertInvalidIssuanceBinding(
                TENANT_ID + 1,
                firstFixture.fileId(),
                firstFixture.leafId(),
                firstFixture.manifestHash(),
                "d"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertInvalidIssuanceBinding(
                TENANT_ID,
                firstFixture.fileId(),
                secondFixture.leafId(),
                firstFixture.manifestHash(),
                "e"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM proof_bundle_issuance WHERE tenant_id IN (?, ?)",
                Integer.class,
                TENANT_ID,
                TENANT_ID + 1)).isZero();
    }

    /**
     * 使用真实 manifest、Merkle 和 batch persistence 创建可直接进入 signed-proof service 的证据 fixture。
     */
    private ProofFixture createProofFixture(Long fileId, String discriminator) {
        byte[] payload = discriminator.getBytes(StandardCharsets.UTF_8);
        if (payload.length != 3) {
            throw new IllegalArgumentException("fixture payload 必须正好为 3 字节");
        }
        String contentHash = sha256(payload);
        String chainRecordId = "chain-record-" + discriminator;
        String storagePath = "tenant/" + TENANT_ID + "/proof/" + discriminator + "/chunk/0";
        String fileParam = JsonConverter.toJson(Map.of(
                "fileSize", 3L,
                "contentType", "application/octet-stream",
                "contentHash", contentHash,
                "encryptionAlgorithm", "NONE"));
        File file = new File()
                .setId(fileId)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("proof-lifecycle-" + discriminator + ".bin")
                .setClassification("document")
                .setFileParam(fileParam)
                .setFileHash(chainRecordId)
                .setContentHash(contentHash)
                .setTransactionHash("0x-file-" + discriminator)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setVersion(1)
                .setIsLatest(1)
                .setVersionGroupId(fileId)
                .setCreateTime(new Date())
                .setDeleted(0);
        assertThat(fileMapper.insert(file)).isEqualTo(1);

        ChunkManifestDraft draft = new ChunkManifestDraft(
                ChunkManifestCanonicalizer.SCHEMA_ID,
                chainRecordId,
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                3L,
                3L,
                null,
                "NONE",
                "S3",
                List.of(new ChunkManifestChunk(
                        0,
                        contentHash,
                        contentHash,
                        3L,
                        storagePath,
                        "S3",
                        "etag-" + discriminator,
                        "SHA-256")));
        ChunkManifestView manifest = chunkManifestService.saveManifest(USER_ID, fileId, draft);

        MerkleTreeResult tree = merkleTreeService.buildTree(List.of(
                new MerkleLeafInput(fileId, manifest.manifestHash())));
        AttestationBatch batch = attestationBatchPersistenceService.createOrGet(
                TENANT_ID,
                "proof-lifecycle-it-" + discriminator,
                tree,
                List.of(new AttestationLeafEvidence(
                        fileId,
                        1,
                        manifest.manifestId(),
                        "MANIFEST_HASH",
                        manifest.manifestHash(),
                        chainRecordId)),
                contractRegistry());
        String claimToken = "proof-fixture-" + discriminator;
        Date claimedAt = new Date();
        assertThat(attestationBatchPersistenceService.claim(
                        TENANT_ID,
                        batch.getId(),
                        claimToken,
                        claimedAt,
                        new Date(claimedAt.getTime() + TimeUnit.MINUTES.toMillis(1)),
                        5))
                .as("fixture batch claim")
                .isPresent();
        assertThat(attestationBatchPersistenceService.verifyContractRegistryClaim(
                        TENANT_ID,
                        batch.getId(),
                        claimToken,
                        contractRegistry()))
                .isPresent();
        String transactionHash = sha256(
                ("batch-" + discriminator).getBytes(StandardCharsets.UTF_8))
                .substring("sha256:".length());
        assertThat(attestationBatchPersistenceService.confirm(
                TENANT_ID,
                batch.getId(),
                claimToken,
                transactionHash,
                tree.merkleRoot(),
                "CHAIN_WRITE")).isTrue();

        AttestationLeaf leaf = leafMapper.selectOne(new LambdaQueryWrapper<AttestationLeaf>()
                .eq(AttestationLeaf::getTenantId, TENANT_ID)
                .eq(AttestationLeaf::getBatchId, batch.getId())
                .eq(AttestationLeaf::getFileId, fileId)
                .eq(AttestationLeaf::getDeleted, 0)
                .last("LIMIT 1"));
        assertThat(leaf).isNotNull();

        ProofFixture fixture = new ProofFixture(
                fileId,
                leaf.getId(),
                batch.getId(),
                manifest.manifestId(),
                contentHash,
                manifest.manifestHash(),
                chainRecordId,
                storagePath,
                contentHash,
                contentHash);
        when(fileRemoteClient.headObject(storagePath, contentHash))
                .thenReturn(successfulHead(fixture));
        return fixture;
    }

    /**
     * 调用真实 FileService 直接上传入口，把指定 PREPARE 版本推进为 SUCCESS。
     */
    private File completeDirectUpload(File targetFile, String discriminator) {
        byte[] payload = discriminator.getBytes(StandardCharsets.UTF_8);
        String contentHash = sha256(payload);
        String storagePath = "tenant/" + TENANT_ID + "/upload/" + discriminator + "/chunk/0";
        when(fileRemoteClient.storeFileOnChain(any()))
                .thenReturn(Result.success(new StoreFileResponse(
                        "0x-upload-" + discriminator,
                        "chain-record-" + discriminator)));
        DirectMultipartCompletedPartVO part = new DirectMultipartCompletedPartVO(
                0,
                storagePath,
                payload.length,
                "etag-" + discriminator,
                contentHash,
                contentHash,
                "SHA-256");
        String fileParam = JsonConverter.toJson(Map.of(
                "fileSize", (long) payload.length,
                "contentType", "application/octet-stream",
                "contentHash", contentHash,
                "encryptionAlgorithm", "NONE"));
        return fileService.storeDirectUploadedFile(
                USER_ID,
                targetFile.getId(),
                targetFile.getFileName(),
                payload.length,
                List.of(part),
                fileParam);
    }

    /**
     * 构造指纹自洽且字段完整的不可变合约注册表快照。
     */
    private ContractRegistryEntryResponse contractRegistry() {
        return new ContractRegistryEntryResponse(
                "record-platform-contract-registry-entry.v1",
                null,
                "Sharing",
                "1.0.0",
                "LOCAL_FISCO",
                "chain0",
                "group0",
                "0x1111111111111111111111111111111111111111",
                "ABI-CANONICAL-JSON-SHA256-V1",
                "sha256:" + "5".repeat(64),
                "sha256:" + "6".repeat(64),
                "sha256:" + "7".repeat(64),
                "0x" + "8".repeat(64),
                100L,
                "ACTIVE",
                "2026-07-01T00:00:00Z",
                "REDEPLOY_ADDRESS").withCalculatedRegistryFingerprint();
    }

    /**
     * 为当前用例动态生成专用 Ed25519 密钥对并开启 proof 导出。
     */
    private void configureSigningKey() throws GeneralSecurityException {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        signingProperties.setEnabled(true);
        signingProperties.setAlgorithm("Ed25519");
        signingProperties.setKeyId(KEY_ID);
        signingProperties.setKeyVersion(KEY_VERSION);
        signingProperties.setKeyStatus("ACTIVE");
        signingProperties.setPrivateKeyPkcs8(
                Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        signingProperties.setPublicKeySpki(
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
    }

    /**
     * 关闭测试 proof key 并清除私钥材料，避免 Spring context 复用造成跨用例泄漏。
     */
    private void resetSigningKey() {
        signingProperties.setEnabled(false);
        signingProperties.setKeyStatus("DISABLED");
        signingProperties.setPrivateKeyPkcs8(null);
        signingProperties.setPublicKeySpki(null);
    }

    /**
     * 预注册与当前 provider 完全一致的全局 key，供锁序用例稳定阻塞 no-op upsert。
     */
    private void insertCurrentSigningKey() {
        ProofSigningKeyMetadata key = signingProvider.currentKey();
        ProofSigningKeyRecord record = new ProofSigningKeyRecord()
                .setId(IdUtils.nextEntityId())
                .setKeyId(key.keyId())
                .setKeyVersion(key.keyVersion())
                .setSignatureAlgorithm(key.algorithm())
                .setPublicKeySpki(key.publicKeySpki())
                .setPublicKeyFingerprint(key.publicKeyFingerprint())
                .setStatus("ACTIVE")
                .setFirstSeenAt(new Date())
                .setDeleted(0);
        signingKeyMapper.registerKey(record);
    }

    /**
     * 绕过 service 直接尝试写入伪造绑定，使测试能够验证数据库 composite FK 本身的拒绝能力。
     */
    private void insertInvalidIssuanceBinding(
            Long tenantId,
            Long fileId,
            Long leafId,
            String manifestHash,
            String proofDiscriminator
    ) {
        ProofSigningKeyMetadata key = signingProvider.currentKey();
        jdbcTemplate.update(
                """
                        INSERT INTO proof_bundle_issuance(
                            id, tenant_id, proof_id, file_id, file_version, leaf_id,
                            manifest_hash, manifest_json, signature_jws, signature_algorithm,
                            key_id, key_version, public_key_spki, public_key_fingerprint,
                            issued_status, status, status_version, issued_at, deleted
                        ) VALUES (?, ?, ?, ?, 1, ?, ?, '{}', 'header.payload.signature', ?,
                                  ?, ?, ?, ?, 'ACTIVE', 'ACTIVE', 1, NOW(3), 0)
                        """,
                IdUtils.nextEntityId(),
                tenantId,
                "rp-proof-" + proofDiscriminator.repeat(64),
                fileId,
                leafId,
                manifestHash,
                key.algorithm(),
                key.keyId(),
                key.keyVersion(),
                key.publicKeySpki(),
                key.publicKeyFingerprint());
    }

    /**
     * 在独立事务中持有 signing key 记录锁，直到测试释放对应 latch。
     */
    private Future<Void> holdSigningKeyRow(
            CountDownLatch keyHeld,
            CountDownLatch releaseKey
    ) {
        return executor.submit(() -> inNewTransaction(() -> {
            Long keyId = jdbcTemplate.queryForObject(
                    "SELECT id FROM proof_signing_key WHERE key_id = ? AND key_version = ? FOR UPDATE",
                    Long.class,
                    KEY_ID,
                    KEY_VERSION);
            assertThat(keyId).isNotNull();
            keyHeld.countDown();
            awaitLatch(releaseKey, "签名 key blocker 等待释放超时");
            return null;
        }));
    }

    /**
     * 在独立事务中持有指定 file 主键行锁，供 upload-first 时序稳定停在真实更新点。
     */
    private Future<Void> holdFileRow(
            Long fileId,
            CountDownLatch rowHeld,
            CountDownLatch releaseRow
    ) {
        return executor.submit(() -> inTenant(() -> inNewTransaction(() -> {
            Long lockedId = jdbcTemplate.queryForObject(
                    "SELECT id FROM file WHERE tenant_id = ? AND id = ? FOR UPDATE",
                    Long.class,
                    TENANT_ID,
                    fileId);
            assertThat(lockedId).isEqualTo(fileId);
            rowHeld.countDown();
            awaitLatch(releaseRow, "file blocker 等待释放超时");
            return null;
        })));
    }

    /**
     * 在有界时间内轮询 MySQL 锁条件，只有 performance_schema 观察到事实后才推进时序。
     */
    private void awaitDatabaseCondition(BooleanSupplier condition, String failureMessage)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new AssertionError(failureMessage);
    }

    /**
     * 查询指定主键记录是否存在排他记录锁，用于确认生产事务已持有版本组 anchor。
     */
    private boolean hasExclusiveRecordLock(String tableName, String indexName, Long recordId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM performance_schema.data_locks
                         WHERE OBJECT_SCHEMA = DATABASE()
                           AND OBJECT_NAME = ?
                           AND INDEX_NAME = ?
                           AND LOCK_TYPE = 'RECORD'
                           AND LOCK_MODE LIKE 'X%'
                           AND LOCK_DATA = CAST(? AS CHAR)
                        """,
                Integer.class,
                tableName,
                indexName,
                recordId);
        return count != null && count > 0;
    }

    /**
     * 查询指定主键记录是否出现在 data_lock_waits，排除线程调度造成的假等待结论。
     */
    private boolean hasRecordLockWait(String tableName, String indexName, Long recordId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM performance_schema.data_lock_waits waits
                          JOIN performance_schema.data_locks requested
                            ON requested.ENGINE = waits.ENGINE
                           AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                         WHERE requested.OBJECT_SCHEMA = DATABASE()
                           AND requested.OBJECT_NAME = ?
                           AND requested.LOCK_TYPE = 'RECORD'
                           AND (
                                (requested.INDEX_NAME = ?
                                 AND requested.LOCK_DATA = CAST(? AS CHAR))
                                OR requested.LOCK_DATA LIKE CONCAT('%', CAST(? AS CHAR), '%')
                           )
                        """,
                Integer.class,
                tableName,
                indexName,
                recordId,
                recordId);
        return count != null && count > 0;
    }

    /**
     * 查询指定唯一索引是否存在真实锁等待，用于验证并发 key upsert 的冲突路径。
     */
    private boolean hasAnyLockWait(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                          FROM performance_schema.data_lock_waits waits
                          JOIN performance_schema.data_locks requested
                            ON requested.ENGINE = waits.ENGINE
                           AND requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                         WHERE requested.OBJECT_SCHEMA = DATABASE()
                           AND requested.OBJECT_NAME = ?
                           AND requested.INDEX_NAME = ?
                           AND requested.LOCK_TYPE = 'RECORD'
                        """,
                Integer.class,
                tableName,
                indexName);
        return count != null && count > 0;
    }

    /**
     * 创建纳入 tearDown 兜底释放范围的 latch，避免失败路径遗留持锁事务。
     */
    private CountDownLatch newReleaseLatch() {
        CountDownLatch latch = new CountDownLatch(1);
        releaseLatches.add(latch);
        return latch;
    }

    /**
     * 等待并发栅栏，超时或中断时立即失败并保留中断状态。
     */
    private void awaitLatch(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException(failureMessage);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, e);
        }
    }

    /**
     * 在 worker 线程内绑定并清理租户上下文。
     */
    private <T> T inTenant(Supplier<T> action) {
        TenantContext.setTenantId(TENANT_ID);
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 在独立 REQUIRES_NEW 事务中执行测试锁定动作，并设置硬事务超时。
     */
    private <T> T inNewTransaction(Supplier<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setTimeout(30);
        return template.execute(status -> action.get());
    }

    /**
     * 在失败路径取消尚未完成的 worker，tearDown 会在释放 latch 后等待其退出。
     */
    private void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }
    }

    /**
     * 构造与 fixture 分片完全一致的成功存储 HEAD 响应。
     */
    private Result<StorageObjectHeadVO> successfulHead(ProofFixture fixture) {
        return Result.success(new StorageObjectHeadVO(
                true,
                fixture.storagePath(),
                fixture.cipherHash(),
                TENANT_ID,
                TENANT_ID,
                "node-proof-it",
                3L,
                "etag-proof-it",
                fixture.cipherHash()));
    }

    /**
     * 断言真实签发结果包含固定八条目和 compact JWS。
     */
    private void assertValidArchive(ProofArchive archive) {
        assertThat(archive).isNotNull();
        assertThat(archive.entries()).hasSize(ProofArchive.ENTRY_ORDER.size());
        assertThat(archive.compactJws()).contains(".");
        assertThat(archive.manifestHash()).startsWith("sha256:");
    }

    /**
     * 从数据库加载一个 leaf 的签发与在线状态快照。
     */
    private IssuanceStatus loadIssuanceStatus(Long leafId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT issued_status, status, status_version, status_reason
                          FROM proof_bundle_issuance
                         WHERE tenant_id = ? AND leaf_id = ? AND deleted = 0
                        """,
                (resultSet, rowNum) -> new IssuanceStatus(
                        resultSet.getString("issued_status"),
                        resultSet.getString("status"),
                        resultSet.getLong("status_version"),
                        resultSet.getString("status_reason")),
                TENANT_ID,
                leafId);
    }

    /**
     * 加载当前租户全部 issuance 的签名 key 绑定，用于验证并发 upsert 后材料完全一致。
     */
    private List<IssuanceKeyBinding> loadIssuanceKeyBindings() {
        return jdbcTemplate.query(
                """
                        SELECT key_id, key_version, public_key_spki, public_key_fingerprint
                          FROM proof_bundle_issuance
                         WHERE tenant_id = ? AND deleted = 0
                         ORDER BY leaf_id
                        """,
                (resultSet, rowNum) -> new IssuanceKeyBinding(
                        resultSet.getString("key_id"),
                        resultSet.getInt("key_version"),
                        resultSet.getString("public_key_spki"),
                        resultSet.getString("public_key_fingerprint")),
                TENANT_ID);
    }

    /**
     * 加载指定文件的持久化上传状态。
     */
    private Integer loadFileStatus(Long fileId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM file WHERE tenant_id = ? AND id = ?",
                Integer.class,
                TENANT_ID,
                fileId);
    }

    /**
     * 计算 sha256 前缀的小写十六进制摘要，确保 fixture hash 来自真实内容字节。
     */
    private String sha256(byte[] payload) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持 SHA-256", e);
        }
    }

    /**
     * 按外键逆序删除当前测试租户与 key 身份创建的全部数据。
     */
    private void cleanUpLifecycleFixtures() {
        jdbcTemplate.update(
                "DELETE FROM proof_bundle_issuance WHERE tenant_id IN (?, ?)",
                TENANT_ID,
                TENANT_ID + 1);
        jdbcTemplate.update(
                "DELETE FROM proof_signing_key WHERE key_id = ? AND key_version = ?",
                KEY_ID,
                KEY_VERSION);
        jdbcTemplate.update("DELETE FROM attestation_batch_attempt WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_batch_candidate WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_leaf WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM attestation_batch WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_chunk_manifest_item WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_chunk_manifest WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file WHERE tenant_id = ?", TENANT_ID);
    }

    /**
     * 一组可直接进入真实 signed-proof 导出链路的不可变证据标识。
     */
    private record ProofFixture(
            Long fileId,
            Long leafId,
            Long batchId,
            Long manifestId,
            String contentHash,
            String manifestHash,
            String chainRecordId,
            String storagePath,
            String plainHash,
            String cipherHash
    ) {
    }

    /**
     * 数据库中的签发时状态与当前在线状态。
     */
    private record IssuanceStatus(
            String issuedStatus,
            String status,
            Long statusVersion,
            String statusReason
    ) {
    }

    /**
     * issuance 保存的全局签名 key 身份材料。
     */
    private record IssuanceKeyBinding(
            String keyId,
            Integer keyVersion,
            String publicKeySpki,
            String publicKeyFingerprint
    ) {
    }
}
