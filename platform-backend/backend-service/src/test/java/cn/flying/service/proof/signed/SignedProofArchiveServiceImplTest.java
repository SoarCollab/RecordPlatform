package cn.flying.service.proof.signed;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecureIdCodec;
import cn.flying.common.util.SecurityUtils;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.entity.ProofBundleIssuance;
import cn.flying.dao.entity.ProofSigningKeyRecord;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ProofBundleIssuanceMapper;
import cn.flying.dao.mapper.ProofSigningKeyMapper;
import cn.flying.dao.vo.file.ProofSigningKeyVO;
import cn.flying.dao.vo.file.ProofStatusVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.ContractRegistryEntryResponse;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.attestation.AttestationBatchPersistenceService;
import cn.flying.service.attestation.MerkleProofNode;
import cn.flying.service.attestation.MerkleTreeService;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.verifier.DefaultProofVerifier;
import cn.flying.verifier.VerificationContext;
import cn.flying.verifier.VerificationLimits;
import cn.flying.verifier.contract.SignedProofBundleContract;
import cn.flying.verifier.contract.SignedProofBundleModel;
import cn.flying.verifier.model.ChainRootEvidence;
import cn.flying.verifier.model.PublicProofStatus;
import cn.flying.verifier.model.PublicSigningKey;
import cn.flying.verifier.model.VerificationOutcome;
import cn.flying.verifier.resolver.Resolution;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 签名 proof service 的证据、权限、签发快照和状态治理测试。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignedProofArchiveServiceImplTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final Long FILE_ID = 11L;
    private static final Long LEAF_ID = 901L;
    private static final Long BATCH_ID = 900L;
    private static final Long MANIFEST_ID = 800L;
    private static final String ORIGINAL_TEXT = "abc";
    private static final String CONTENT_HASH =
            "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String MANIFEST_HASH = "sha256:" + "2".repeat(64);
    private static final String PLAIN_HASH = CONTENT_HASH;
    private static final String CIPHER_HASH = "sha256:" + "4".repeat(64);
    private static final String FILE_TRANSACTION_HASH = "0x" + "a".repeat(64);
    private static final String BATCH_TRANSACTION_HASH = "0x" + "b".repeat(64);
    private static final String CHAIN_RECORD_ID = "chain-record-11";
    private static final String STORAGE_PATH = "tenant/7/file/11/chunk/0";
    private static final Date ISSUED_AT = new Date(1_752_451_200_123L);

    @TempDir
    Path tempDirectory;

    @Mock
    private FileMapper fileMapper;
    @Mock
    private AttestationLeafMapper leafMapper;
    @Mock
    private AttestationBatchMapper batchMapper;
    @Mock
    private ProofBundleIssuanceMapper issuanceMapper;
    @Mock
    private ProofSigningKeyMapper signingKeyMapper;
    @Mock
    private FileRemoteClient fileRemoteClient;
    @Mock
    private ChunkManifestService chunkManifestService;
    @Mock
    private AttestationBatchPersistenceService attestationBatchPersistenceService;
    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;
    @Mock
    private TransactionTemplate transactionTemplate;

    private MerkleTreeService merkleTreeService;
    private ProofSigningProvider signingProvider;
    private ProofSigningProperties signingProperties;
    private SignedProofArchiveServiceImpl service;
    private SecureIdCodec originalSecureIdCodec;

    /**
     * 初始化纯单元测试中 LambdaUpdateWrapper 所需的 MyBatis-Plus 列缓存。
     */
    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, AttestationLeaf.class);
        TableInfoHelper.initTableInfo(assistant, ProofBundleIssuance.class);
    }

    /**
     * 初始化真实 Merkle/canonical/JWS 组件与 mock 事实源。
     */
    @BeforeEach
    void setUp() throws Exception {
        originalSecureIdCodec = (SecureIdCodec) ReflectionTestUtils.getField(IdUtils.class, "secureIdCodec");
        TenantContext.setTenantId(TENANT_ID);
        ReflectionTestUtils.setField(
                IdUtils.class,
                "secureIdCodec",
                new SecureIdCodec("SecureTestKey4UnitTests2026XyZ789AbCdEfGhIjKlMnOpQrStUvWxYz1234"));

        ProofCanonicalizer canonicalizer = new ProofCanonicalizer();
        signingProperties = activeSigningProperties();
        signingProvider = new LocalEd25519ProofSigningProvider(signingProperties, canonicalizer);
        DeterministicProofArchiveBuilder archiveBuilder =
                new DeterministicProofArchiveBuilder(canonicalizer, signingProvider);
        merkleTreeService = new MerkleTreeService();
        service = new SignedProofArchiveServiceImpl(
                fileMapper,
                leafMapper,
                batchMapper,
                issuanceMapper,
                signingKeyMapper,
                fileRemoteClient,
                chunkManifestService,
                attestationBatchPersistenceService,
                merkleTreeService,
                archiveBuilder,
                signingProvider,
                canonicalizer,
                snowflakeIdGenerator,
                transactionTemplate);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
    }

    /**
     * 清理租户上下文，避免公开查询测试和其他用例互相污染。
     */
    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(IdUtils.class, "secureIdCodec", originalSecureIdCodec);
        TenantContext.clear();
    }

    /**
     * 验证 owner 首次签发、持久化不可变快照并可按相同证据逐字节重建。
     */
    @Test
    void shouldIssueAndDeterministicallyRebuildValidatedArchive() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(snowflakeIdGenerator.nextId()).thenReturn(1_001L);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            long registrationStartedAt = System.currentTimeMillis();
            ProofArchive first = service.exportByFileId(USER_ID, FILE_ID);
            long registrationCompletedAt = System.currentTimeMillis();

            ArgumentCaptor<ProofBundleIssuance> issuanceCaptor =
                    ArgumentCaptor.forClass(ProofBundleIssuance.class);
            verify(issuanceMapper).insert(issuanceCaptor.capture());
            ProofBundleIssuance issuance = issuanceCaptor.getValue();
            ArgumentCaptor<ProofSigningKeyRecord> keyCaptor =
                    ArgumentCaptor.forClass(ProofSigningKeyRecord.class);
            verify(signingKeyMapper).registerKey(keyCaptor.capture());
            assertThat(issuance.getProofId()).matches("^rp-proof-[0-9a-f]{64}$");
            assertThat(issuance.getStatus()).isEqualTo("ACTIVE");
            assertThat(issuance.getIssuedStatus()).isEqualTo("ACTIVE");
            assertThat(issuance.getManifestHash()).isEqualTo(first.manifestHash());
            assertThat(issuance.getManifestJson()).doesNotContain(CHAIN_RECORD_ID + "\"contentHash");
            assertThat(issuance.getIssuedAt().getTime())
                    .isBetween(registrationStartedAt, registrationCompletedAt)
                    .isGreaterThan(ISSUED_AT.getTime());
            assertThat(keyCaptor.getValue().getFirstSeenAt().getTime())
                    .isBetween(registrationStartedAt, registrationCompletedAt)
                    .isGreaterThan(ISSUED_AT.getTime());
            assertThat(first.entries()).extracting(ProofArchive.ArchiveEntry::name)
                    .containsExactlyElementsOf(ProofArchive.ENTRY_ORDER);
            String policyJson = new String(first.entries().get(6).bytes(), java.nio.charset.StandardCharsets.UTF_8);
            String readme = new String(first.entries().get(7).bytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(policyJson)
                    .contains("record-platform-proof-verification-policy.v2")
                    .contains("CHAIN_WRITE")
                    .contains("CHAIN_QUERY_BEFORE_WRITE")
                    .contains("record-platform-contract-registry-entry.v1")
                    .contains("immutable_snapshot_validation_failed");
            assertThat(readme)
                    .contains("batchChainRoot")
                    .contains("REDEPLOY_ADDRESS")
                    .contains("immutable_snapshot_validation_failed")
                    .contains("exactly one trailing LF byte");
            assertPublicVerifierAccepts(first, issuance);
            InOrder lifecycleOrder = inOrder(fileMapper, issuanceMapper);
            lifecycleOrder.verify(fileMapper)
                    .lockVersionGroupForProofLifecycle(TENANT_ID, FILE_ID);
            lifecycleOrder.verify(fileMapper).selectCount(any());
            lifecycleOrder.verify(issuanceMapper).insert(any(ProofBundleIssuance.class));

            when(issuanceMapper.selectOne(any())).thenReturn(issuance);
            when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(issuance);
            when(signingKeyMapper.selectByIdentity(issuance.getKeyId(), issuance.getKeyVersion()))
                    .thenReturn(keyRecord(issuance, "ACTIVE"));
            ReflectionTestUtils.setField(
                    IdUtils.class,
                    "secureIdCodec",
                    new SecureIdCodec("RotatedSecureIdKeyForProofHistory2026AbCdEfGhIjKlMnOpQrStUvWxYz9876"));
            ProofArchive rebuilt = service.exportByLeafId(USER_ID, LEAF_ID);

            assertThat(rebuilt.toByteArray()).isEqualTo(first.toByteArray());
            assertThat(rebuilt.compactJws()).isEqualTo(first.compactJws());
            assertThat(rebuilt.fileName()).isEqualTo(first.fileName());
        }
    }

    /**
     * 验证全局应急开关在访问文件或历史证据前同时阻断两种 ZIP 导出入口。
     */
    @Test
    void shouldBlockAllProofArchiveExportsWhenSigningIsDisabled() {
        signingProperties.setEnabled(false);

        assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.PERMISSION_SIGNATURE_ERROR));
        assertThatThrownBy(() -> service.exportByLeafId(USER_ID, LEAF_ID))
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.PERMISSION_SIGNATURE_ERROR));

        verify(fileMapper, never()).selectById(any());
        verify(leafMapper, never()).selectOne(any());
    }

    /**
     * 验证版本链锁建立后才判断新版本，已有更高成功版本时首次快照直接签为 SUPERSEDED。
     */
    @Test
    void shouldIssueSupersededSnapshotWhenNewerSuccessfulVersionExists() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(fileMapper.selectCount(any())).thenReturn(1L);
        when(issuanceMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            service.exportByFileId(USER_ID, FILE_ID);

            ArgumentCaptor<ProofBundleIssuance> issuanceCaptor =
                    ArgumentCaptor.forClass(ProofBundleIssuance.class);
            verify(issuanceMapper).insert(issuanceCaptor.capture());
            assertThat(issuanceCaptor.getValue().getIssuedStatus()).isEqualTo("SUPERSEDED");
            assertThat(issuanceCaptor.getValue().getStatus()).isEqualTo("SUPERSEDED");
        }
    }

    /**
     * 验证不可变签发快照漂移会失败关闭并以 CAS 推进 INVALID 公开终态。
     */
    @Test
    void shouldMarkDeterministicallyDriftedSnapshotInvalid() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            service.exportByFileId(USER_ID, FILE_ID);

            ArgumentCaptor<ProofBundleIssuance> issuanceCaptor =
                    ArgumentCaptor.forClass(ProofBundleIssuance.class);
            verify(issuanceMapper).insert(issuanceCaptor.capture());
            ProofBundleIssuance issuance = issuanceCaptor.getValue()
                    .setManifestHash("sha256:" + "9".repeat(64));
            when(issuanceMapper.selectOne(any())).thenReturn(issuance);
            when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(issuance);
            when(signingKeyMapper.selectByIdentity(issuance.getKeyId(), issuance.getKeyVersion()))
                    .thenReturn(keyRecord(issuance, "ACTIVE"));
            when(issuanceMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

            assertFileRecordError(() -> service.exportByLeafId(USER_ID, LEAF_ID));

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            verify(issuanceMapper).update(eq(null), wrapperCaptor.capture());
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> updateWrapper =
                    (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapperCaptor.getValue();
            assertThat(updateWrapper.getParamNameValuePairs().values())
                    .contains("INVALID", "immutable_snapshot_validation_failed");
            assertThat(updateWrapper.getSqlSegment()).contains("status IN");
            assertThat(updateWrapper.getSqlSet()).contains("status_version = status_version + 1");

            issuance.setStatus("INVALID")
                    .setStatusVersion(2L)
                    .setStatusReason("immutable_snapshot_validation_failed");
            when(issuanceMapper.selectPublicByProofId(issuance.getProofId())).thenReturn(issuance);
            assertThat(service.getPublicStatus(issuance.getProofId()).status()).isEqualTo("INVALID");
        }
    }

    /**
     * 验证非 canonical 历史 manifest 使用锁内当前状态推进 INVALID，避免事务外旧 CAS 丢失。
     */
    @Test
    void shouldMarkMalformedPersistedManifestInvalidBeforeEvidenceRebuild() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            service.exportByFileId(USER_ID, FILE_ID);

            ArgumentCaptor<ProofBundleIssuance> issuanceCaptor =
                    ArgumentCaptor.forClass(ProofBundleIssuance.class);
            verify(issuanceMapper).insert(issuanceCaptor.capture());
            ProofBundleIssuance issuance = issuanceCaptor.getValue()
                    .setManifestJson(" " + issuanceCaptor.getValue().getManifestJson())
                    .setStatus("SUPERSEDED")
                    .setStatusVersion(2L);
            when(issuanceMapper.selectOne(any())).thenReturn(issuance);
            when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(issuance);
            when(issuanceMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
            org.mockito.Mockito.clearInvocations(
                    fileMapper,
                    issuanceMapper,
                    batchMapper,
                    chunkManifestService,
                    fileRemoteClient);

            assertFileRecordError(() -> service.exportByLeafId(USER_ID, LEAF_ID));

            InOrder invalidationOrder = inOrder(fileMapper, issuanceMapper);
            invalidationOrder.verify(fileMapper)
                    .lockVersionGroupForProofLifecycle(TENANT_ID, FILE_ID);
            invalidationOrder.verify(issuanceMapper)
                    .selectByLeafForUpdate(TENANT_ID, LEAF_ID);
            @SuppressWarnings("rawtypes")
            ArgumentCaptor<Wrapper> wrapperCaptor = ArgumentCaptor.forClass(Wrapper.class);
            invalidationOrder.verify(issuanceMapper).update(eq(null), wrapperCaptor.capture());
            com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> updateWrapper =
                    (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?>) wrapperCaptor.getValue();
            assertThat(updateWrapper.getSqlSegment()).contains("status_version", "status IN");
            assertThat(updateWrapper.getParamNameValuePairs().values())
                    .contains(2L, "INVALID", "immutable_snapshot_validation_failed");
            verify(batchMapper, never()).selectById(any());
            verify(chunkManifestService, never()).findActiveManifest(any(), any());
            verifyNoInteractions(fileRemoteClient);
        }
    }

    /**
     * 验证历史签发记录缺失 manifest 时会失败关闭并推进 INVALID，不发生空指针解引用。
     */
    @Test
    void shouldMarkMissingPersistedManifestInvalidWithoutNullDereference() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            service.exportByFileId(USER_ID, FILE_ID);

            ArgumentCaptor<ProofBundleIssuance> issuanceCaptor =
                    ArgumentCaptor.forClass(ProofBundleIssuance.class);
            verify(issuanceMapper).insert(issuanceCaptor.capture());
            ProofBundleIssuance issuance = issuanceCaptor.getValue()
                    .setManifestJson(null)
                    .setStatus("SUPERSEDED")
                    .setStatusVersion(2L);
            when(issuanceMapper.selectOne(any())).thenReturn(issuance);
            when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(issuance);
            when(issuanceMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
            org.mockito.Mockito.clearInvocations(
                    fileMapper,
                    issuanceMapper,
                    batchMapper,
                    chunkManifestService,
                    fileRemoteClient);

            assertFileRecordError(() -> service.exportByLeafId(USER_ID, LEAF_ID));

            verify(issuanceMapper).update(eq(null), any(Wrapper.class));
            verify(batchMapper, never()).selectById(any());
            verify(chunkManifestService, never()).findActiveManifest(any(), any());
            verifyNoInteractions(fileRemoteClient);
        }
    }

    /**
     * 验证已撤销证明不能重新导出，且拒绝路径不会把 REVOKED 覆盖为 INVALID。
     */
    @Test
    void shouldRejectRevokedArchiveWithoutChangingTerminalStatus() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            service.exportByFileId(USER_ID, FILE_ID);

            ArgumentCaptor<ProofBundleIssuance> issuanceCaptor =
                    ArgumentCaptor.forClass(ProofBundleIssuance.class);
            verify(issuanceMapper).insert(issuanceCaptor.capture());
            ProofBundleIssuance issuance = issuanceCaptor.getValue();
            ProofBundleIssuance revoked = issuance("REVOKED")
                    .setId(issuance.getId())
                    .setProofId(issuance.getProofId())
                    .setStatusVersion(2L)
                    .setStatusReason("owner_revoked");
            when(issuanceMapper.selectOne(any())).thenReturn(issuance);
            when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(revoked);

            assertFileRecordError(() -> service.exportByLeafId(USER_ID, LEAF_ID));
            verify(issuanceMapper, never()).update(eq(null), any(Wrapper.class));
        }
    }

    /**
     * 验证 contentHash 结构化列与上传来源记录互换或漂移时失败关闭。
     */
    @Test
    void shouldRejectContentHashSemanticSwap() {
        File swapped = file().setContentHash(MANIFEST_HASH);
        when(fileMapper.selectById(FILE_ID)).thenReturn(swapped);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            verify(leafMapper, never()).selectOne(any());
        }
    }

    /**
     * 验证签发前拒绝公共 verifier 无法解释的 source 合同和文件交易哈希。
     */
    @Test
    void shouldRejectUnverifiableSourceContractBeforeRemoteEvidence() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        ChunkManifestChunk validChunk = manifest().chunks().getFirst();
        List<ChunkManifestView> unsupportedManifests = List.of(
                manifestWithContract(
                        "cn.flying.chunk-manifest.v999", "SHA-256", 3, "NONE", "S3", validChunk),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-512", 3, "NONE", "S3", validChunk),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 2, "NONE", "S3", validChunk),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 3, "NONE", "S3",
                        new ChunkManifestChunk(
                                0, PLAIN_HASH, CIPHER_HASH, 3, STORAGE_PATH,
                                "S3", "etag", "SHA-512")),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 3, "NONE", "S3",
                        new ChunkManifestChunk(
                                0, PLAIN_HASH.toUpperCase(Locale.ROOT), CIPHER_HASH, 3, STORAGE_PATH,
                                "S3", "etag", "SHA-256")),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 3, "NONE", "S3",
                        new ChunkManifestChunk(
                                0, PLAIN_HASH, " " + CIPHER_HASH, 3, STORAGE_PATH,
                                "S3", "etag", "SHA-256")),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 3, "NONE", "S3",
                        new ChunkManifestChunk(
                                0, PLAIN_HASH, CIPHER_HASH, 3, "storage\u0000path",
                                "S3", "etag", "SHA-256")),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 3, "NONE\u0000", "S3", validChunk),
                manifestWithContract(
                        SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                        "SHA-256", 3, "NONE", "S3\u0000", validChunk));

        for (ChunkManifestView unsupported : unsupportedManifests) {
            when(chunkManifestService.findActiveManifest(null, FILE_ID))
                    .thenReturn(Optional.of(unsupported));
            try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::isAdmin).thenReturn(false);
                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }
        verify(fileRemoteClient, never()).headObject(any(), any());

        when(chunkManifestService.findActiveManifest(null, FILE_ID)).thenReturn(Optional.of(manifest()));
        for (String invalidTransactionHash : List.of("", "not-a-chain-transaction")) {
            file.setTransactionHash(invalidTransactionHash);
            try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::isAdmin).thenReturn(false);
                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }
        verify(fileRemoteClient, never()).headObject(any(), any());
    }

    /**
     * 验证 chainRecordId 的空白、超长和控制字符会在读取证明叶子前失败关闭。
     */
    @Test
    void shouldRejectUnboundedChainRecordIdBeforeLeafLookup() {
        List<String> invalidChainRecordIds = List.of(
                "",
                "a".repeat(257),
                "chain-record\u0000id");

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            for (String invalidChainRecordId : invalidChainRecordIds) {
                when(fileMapper.selectById(FILE_ID))
                        .thenReturn(file().setFileHash(invalidChainRecordId));

                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }

        verify(leafMapper, never()).selectOne(any());
    }

    /**
     * 验证历史文件缺少可选的链上交易哈希时仍能导出公开证明。
     */
    @Test
    void shouldAcceptFileWithoutTransactionHash() {
        File file = file().setTransactionHash(null);
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            ProofArchive archive = service.exportByFileId(USER_ID, FILE_ID);

            assertThat(archive.compactJws()).isNotBlank();
        }
    }

    /**
     * 验证 Merkle 文本前像必须保持精确小写 canonical 形式，不能在签发时静默规范化。
     */
    @Test
    void shouldRejectNonCanonicalLeafEvidenceHashBeforeDependentLookup() {
        File file = file();
        AttestationLeaf leaf = leaf().setEvidenceHash(MANIFEST_HASH.toUpperCase(Locale.ROOT));
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(leafMapper.selectOne(any())).thenReturn(leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        verify(issuanceMapper, never()).selectOne(any());
        verify(batchMapper, never()).selectById(any());
        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证 active manifest 的摘要文本必须与 leaf 精确一致且为 canonical 形式。
     */
    @Test
    void shouldRejectNonCanonicalActiveManifestHashBeforeStorageHead() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        ChunkManifestView source = manifest();
        ChunkManifestView nonCanonical = new ChunkManifestView(
                source.manifestId(),
                source.fileId(),
                source.fileVersion(),
                source.schemaId(),
                source.fileHash(),
                source.manifestHash().toUpperCase(Locale.ROOT),
                source.hashAlgorithm(),
                source.chunkSize(),
                source.chunkCount(),
                source.totalSize(),
                source.merkleRoot(),
                source.encryptionAlgorithm(),
                source.storageBackend(),
                source.chunks());
        when(chunkManifestService.findActiveManifest(null, FILE_ID)).thenReturn(Optional.of(nonCanonical));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        verify(chunkManifestService, never()).calculateManifestHash(any());
        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证 leafIndex 无法由 proof path 完整归零时在远程 storage HEAD 前失败关闭。
     */
    @Test
    void shouldRejectNonCanonicalMerkleContractBeforeStorageHead() {
        File file = file();
        AttestationLeaf leaf = leaf().setLeafIndex(1);
        mockSuccessfulEvidence(file, leaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证左右两种规范 Merkle 路径都能归零 leafIndex 并重建已确认链根。
     */
    @Test
    void shouldAcceptCanonicalMerklePathsForBothLeafSides() {
        String siblingHash = "9".repeat(64);
        List<AttestationLeaf> leaves = List.of(
                leaf()
                        .setLeafIndex(0)
                        .setProofPathJson(JsonConverter.toJson(List.of(
                                new MerkleProofNode(
                                        MerkleProofNode.RIGHT,
                                        siblingHash)))),
                leaf()
                        .setLeafIndex(1)
                        .setProofPathJson(JsonConverter.toJson(List.of(
                                new MerkleProofNode(
                                        MerkleProofNode.LEFT,
                                        siblingHash)))));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            for (AttestationLeaf proofLeaf : leaves) {
                mockSuccessfulEvidence(file(), proofLeaf);
                String merkleRoot = proofLeaf.getLeafIndex() == 0
                        ? merkleTreeService.calculateParentHash(proofLeaf.getLeafHash(), siblingHash)
                        : merkleTreeService.calculateParentHash(siblingHash, proofLeaf.getLeafHash());
                when(batchMapper.selectById(BATCH_ID)).thenReturn(batch(proofLeaf)
                        .setLeafCount(2)
                        .setMerkleRoot(merkleRoot)
                        .setChainFileHash(merkleRoot));

                ProofArchive archive = service.exportByFileId(USER_ID, FILE_ID);

                assertThat(archive.compactJws()).isNotBlank();
            }
        }
    }

    /**
     * 验证 null、负数 leafIndex 与空 proof path 的组合均返回稳定业务错误。
     */
    @Test
    void shouldRejectMissingOrNegativeMerkleLeafIndex() {
        List<AttestationLeaf> invalidLeaves = List.of(
                leaf().setLeafIndex(null),
                leaf().setLeafIndex(-1));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            for (AttestationLeaf invalidLeaf : invalidLeaves) {
                mockSuccessfulEvidence(file(), invalidLeaf);

                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
    }

    /**
     * 验证 null 节点、错误方向和非法 sibling hash 均由合同校验失败关闭。
     */
    @Test
    void shouldRejectMalformedMerklePathNodesWithStableBusinessError() {
        List<String> invalidProofPaths = List.of(
                JsonConverter.toJson(Collections.singletonList(null)),
                JsonConverter.toJson(List.of(new MerkleProofNode(
                        MerkleProofNode.LEFT,
                        "9".repeat(64)))),
                JsonConverter.toJson(List.of(new MerkleProofNode(
                        MerkleProofNode.RIGHT,
                        "not-a-sha256"))));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            for (String invalidProofPath : invalidProofPaths) {
                AttestationLeaf invalidLeaf = leaf().setProofPathJson(invalidProofPath);
                mockSuccessfulEvidence(file(), invalidLeaf);

                assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                        .isInstanceOf(GeneralException.class)
                        .satisfies(error -> {
                            GeneralException exception = (GeneralException) error;
                            assertThat(exception.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
                            assertThat(exception.getData()).isEqualTo("Merkle proof path 合同不合法");
                        });
            }
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
    }

    /**
     * 验证 leaf 的 chainRecordId、evidenceType 或 manifestHash 不能与其他摘要混用。
     */
    @Test
    void shouldRejectLeafSemanticMismatch() {
        File file = file();
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(leafMapper.selectOne(any())).thenReturn(leaf().setChainRecordId(CONTENT_HASH));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        when(leafMapper.selectOne(any())).thenReturn(leaf().setEvidenceType("LEGACY_CHAIN_RECORD_ID"));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }
    }

    /**
     * 验证缺少持久化叶子主键或批次主键时在后续 Mapper 与远程存储调用前失败关闭。
     */
    @Test
    void shouldRejectLeafWithoutPersistentIdentifiersBeforeDependentLookup() {
        File file = file();
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        List<AttestationLeaf> invalidLeaves = List.of(
                leaf().setId(null),
                leaf().setBatchId(null));

        for (AttestationLeaf invalidLeaf : invalidLeaves) {
            when(leafMapper.selectOne(any())).thenReturn(invalidLeaf);
            try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::isAdmin).thenReturn(false);
                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }

        verify(issuanceMapper, never()).selectOne(any());
        verify(batchMapper, never()).selectById(any());
        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证 canonical manifest 漂移和 storage HEAD 租户/hash/size 漂移均阻断签发。
     */
    @Test
    void shouldRejectManifestOrStorageEvidenceDrift() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(chunkManifestService.calculateManifestHash(any()))
                .thenReturn("sha256:" + "9".repeat(64));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH)).thenReturn(Result.success(
                new StorageObjectHeadVO(
                        true, STORAGE_PATH, CIPHER_HASH, 999L, 999L,
                        "node-a", 3L, "etag", CIPHER_HASH)));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }
    }

    /**
     * 验证批次确认来源与交易哈希不一致时在首次 storage HEAD 前失败关闭。
     */
    @Test
    void shouldRejectInvalidBatchConfirmationProvenanceBeforeStorageHead() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(batchMapper.selectById(BATCH_ID)).thenReturn(
                batch(leaf).setChainTransactionHash("not-a-chain-transaction"));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证批次编号、规范根哈希和 query 回执空值语义均在远程 storage HEAD 前精确校验。
     */
    @Test
    void shouldRejectNonCanonicalBatchReceiptBeforeStorageHead() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        String uppercaseRoot = leaf.getLeafHash().toUpperCase(Locale.ROOT);
        List<AttestationBatch> invalidBatches = List.of(
                batch(leaf).setBatchNo("batch/900"),
                batch(leaf).setMerkleRoot(uppercaseRoot).setChainFileHash(uppercaseRoot),
                batch(leaf)
                        .setConfirmationSource("CHAIN_QUERY_AFTER_WRITE")
                        .setChainTransactionHash(""));

        for (AttestationBatch invalidBatch : invalidBatches) {
            when(batchMapper.selectById(BATCH_ID)).thenReturn(invalidBatch);
            try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
                security.when(SecurityUtils::isAdmin).thenReturn(false);
                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证链查询恢复得到的合法完成批次仍可导出签名证明。
     */
    @Test
    void shouldAcceptQueryConfirmedBatchWithoutTransactionHash() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(batchMapper.selectById(BATCH_ID)).thenReturn(batch(leaf)
                .setConfirmationSource("CHAIN_QUERY_AFTER_WRITE")
                .setChainTransactionHash(null));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            ProofArchive archive = service.exportByFileId(USER_ID, FILE_ID);

            assertThat(archive.compactJws()).isNotBlank();
        }
    }

    /**
     * 验证超过签名证明上限的 manifest 在发起任何远程 HEAD 前被拒绝。
     */
    @Test
    void shouldRejectOversizedManifestBeforeStorageHead() {
        File file = file().setFileSize(129L);
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        List<ChunkManifestChunk> chunks = IntStream.range(0, 129)
                .mapToObj(index -> new ChunkManifestChunk(
                        index,
                        PLAIN_HASH,
                        CIPHER_HASH,
                        1,
                        STORAGE_PATH + "/" + index,
                        "S3",
                        "etag-" + index,
                        "SHA-256"))
                .toList();
        ChunkManifestView oversized = new ChunkManifestView(
                MANIFEST_ID,
                FILE_ID,
                1,
                SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                CHAIN_RECORD_ID,
                MANIFEST_HASH,
                "SHA-256",
                1,
                chunks.size(),
                chunks.size(),
                PLAIN_HASH,
                "NONE",
                "S3",
                chunks);
        when(chunkManifestService.findActiveManifest(null, FILE_ID)).thenReturn(Optional.of(oversized));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
    }

    /**
     * 验证空分片、非连续索引、非正长度和聚合长度不一致都在签名及远程 HEAD 前失败关闭。
     */
    @Test
    void shouldRejectInvalidManifestChunkStructureBeforeStorageHead() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        List<List<ChunkManifestChunk>> invalidChunks = List.of(
                Collections.singletonList(null),
                List.of(new ChunkManifestChunk(
                        1, PLAIN_HASH, CIPHER_HASH, 3, STORAGE_PATH, "S3", "etag", "SHA-256")),
                List.of(new ChunkManifestChunk(
                        0, PLAIN_HASH, CIPHER_HASH, 0, STORAGE_PATH, "S3", "etag", "SHA-256")),
                List.of(new ChunkManifestChunk(
                        0, PLAIN_HASH, CIPHER_HASH, 2, STORAGE_PATH, "S3", "etag", "SHA-256")));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            for (List<ChunkManifestChunk> chunks : invalidChunks) {
                when(chunkManifestService.findActiveManifest(null, FILE_ID))
                        .thenReturn(Optional.of(manifest(chunks)));
                assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
            }
        }

        verify(chunkManifestService, never()).calculateManifestHash(any());
        verifyNoInteractions(fileRemoteClient);
    }

    /**
     * 验证 proof bulkhead 饱和时新请求立即失败且不读取任何业务或存储事实源。
     */
    @Test
    void shouldFailFastWhenProofExportBulkheadIsExhausted() {
        Semaphore permits = (Semaphore) ReflectionTestUtils.getField(service, "exportPermits");
        int acquired = permits.drainPermits();
        try {
            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(RetryableException.class)
                    .satisfies(error -> assertThat(((RetryableException) error).getResultEnum())
                            .isEqualTo(ResultEnum.SERVICE_UNAVAILABLE));

            verifyNoInteractions(fileMapper, fileRemoteClient);
        } finally {
            permits.release(acquired);
        }
    }

    /**
     * 验证远程 HEAD 总预算耗尽后停止证明导出且不把依赖超时归类为永久证据损坏。
     */
    @Test
    void shouldStopStorageValidationWhenOverallBudgetExpires() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        AtomicInteger clockCalls = new AtomicInteger();
        LongSupplier clock = () -> clockCalls.getAndIncrement() < 2
                ? 0L
                : TimeUnit.SECONDS.toNanos(61);
        ReflectionTestUtils.setField(service, "nanoTimeSource", clock);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(RetryableException.class)
                    .satisfies(error -> assertThat(((RetryableException) error).getResultEnum())
                            .isEqualTo(ResultEnum.SERVICE_UNAVAILABLE));
        }

        verify(fileRemoteClient).headObject(STORAGE_PATH, CIPHER_HASH);
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证单次远程 HEAD 阻塞时也会被剩余总预算中断，而不是占用导出许可到 Dubbo 超时。
     */
    @Test
    void shouldEnforceHardDeadlineAroundBlockingStorageHead() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        ReflectionTestUtils.setField(service, "nanoTimeSource", (LongSupplier) System::nanoTime);
        ReflectionTestUtils.setField(
                service,
                "storageValidationBudgetNanos",
                TimeUnit.MILLISECONDS.toNanos(25));
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH)).thenAnswer(invocation -> {
            Thread.sleep(TimeUnit.SECONDS.toMillis(5));
            return Result.success(new StorageObjectHeadVO(
                    true,
                    STORAGE_PATH,
                    CIPHER_HASH,
                    TENANT_ID,
                    TENANT_ID,
                    "node-a",
                    3L,
                    "etag",
                    CIPHER_HASH));
        });

        long startedAt = System.nanoTime();
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(RetryableException.class)
                    .satisfies(error -> assertThat(((RetryableException) error).getResultEnum())
                            .isEqualTo(ResultEnum.SERVICE_UNAVAILABLE));
        }

        assertThat(System.nanoTime() - startedAt).isLessThan(TimeUnit.SECONDS.toNanos(1));
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证损坏的 Merkle JSON 与不可签发 registry 都归一化为明确的文件证据错误。
     */
    @Test
    void shouldNormalizeMalformedMerkleAndInvalidRegistryErrors() {
        File file = file();
        AttestationLeaf malformedLeaf = leaf().setProofPathJson("{");
        mockSuccessfulEvidence(file, malformedLeaf);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(error -> {
                        GeneralException exception = (GeneralException) error;
                        assertThat(exception.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
                        assertThat(exception.getData()).isEqualTo("Merkle proof path 不可解析");
                    });
        }

        AttestationLeaf validLeaf = leaf();
        mockSuccessfulEvidence(file, validLeaf);
        when(attestationBatchPersistenceService.requireContractRegistry(any()))
                .thenReturn(invalidSelfConsistentContractRegistry());
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(error -> {
                        GeneralException exception = (GeneralException) error;
                        assertThat(exception.getResultEnum()).isEqualTo(ResultEnum.FILE_RECORD_ERROR);
                        assertThat(exception.getData()).isEqualTo("合约注册表快照不可用于签发");
                    });
        }
    }

    /**
     * 验证后端旧校验允许但公共 verifier 明确拒绝的 registry 字段不会进入签名包。
     */
    @Test
    void shouldRejectRegistryThatPublicVerifierCannotInterpret() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        ContractRegistryEntryResponse valid = contractRegistry();
        ContractRegistryEntryResponse besuWithEmptyGroup = new ContractRegistryEntryResponse(
                valid.schemaVersion(),
                null,
                valid.contractName(),
                valid.semanticVersion(),
                "BSN_BESU",
                valid.chainId(),
                "",
                valid.contractAddress(),
                valid.abiFingerprintAlgorithm(),
                valid.abiSha256(),
                valid.artifactBytecodeSha256(),
                valid.onChainCodeSha256(),
                valid.deploymentTransactionHash(),
                valid.deploymentBlockNumber(),
                valid.status(),
                valid.effectiveAt(),
                valid.upgradeStrategy()).withCalculatedRegistryFingerprint();
        when(attestationBatchPersistenceService.requireContractRegistry(any()))
                .thenReturn(besuWithEmptyGroup);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        verify(fileRemoteClient, never()).headObject(any(), any());
        verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
    }

    /**
     * 验证跨租户、非 owner 与缺失可信 contentHash 都在访问底层证据前被拒绝。
     */
    @Test
    void shouldEnforceTenantOwnerAndContentHash() {
        when(fileMapper.selectById(FILE_ID)).thenReturn(file().setTenantId(8L));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                            .isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED));
        }

        when(fileMapper.selectById(FILE_ID)).thenReturn(file());
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertThatThrownBy(() -> service.exportByFileId(999L, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                            .isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED));
        }

        when(fileMapper.selectById(FILE_ID)).thenReturn(file().setContentHash(null));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }

        when(fileMapper.selectById(FILE_ID)).thenReturn(file().setStatus(FileUploadStatus.PREPARE.getCode()));
        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertFileRecordError(() -> service.exportByFileId(USER_ID, FILE_ID));
        }
    }

    /**
     * 验证相同 key id/version 若绑定不同 SPKI 会阻断新签发。
     */
    @Test
    void shouldRejectPublicSigningKeyIdentityCollision() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(signingKeyMapper.selectByIdentityForUpdate("proof-key-main", 1)).thenReturn(
                new ProofSigningKeyRecord()
                        .setKeyId("proof-key-main")
                        .setKeyVersion(1)
                        .setSignatureAlgorithm("EdDSA")
                        .setPublicKeySpki("different-spki")
                        .setPublicKeyFingerprint("sha256:" + "f".repeat(64))
                        .setStatus("ACTIVE"));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class)
                    .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                            .isEqualTo(ResultEnum.PERMISSION_SIGNATURE_ERROR));
            verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
        }
    }

    /**
     * 验证并发首次注册相同 key 时，唯一约束竞争会回读并接受完全一致的公钥身份。
     */
    @Test
    void shouldAcceptConcurrentRegistrationOfIdenticalSigningKey() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        ProofSigningKeyMetadata key = signingProvider.currentKey();
        ProofSigningKeyRecord registered = keyRecord(key, "ACTIVE");
        when(signingKeyMapper.registerKey(any(ProofSigningKeyRecord.class))).thenReturn(0);
        when(signingKeyMapper.selectByIdentityForUpdate(key.keyId(), key.keyVersion()))
                .thenReturn(registered);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            ProofArchive archive = service.exportByFileId(USER_ID, FILE_ID);

            assertThat(archive.compactJws()).isNotBlank();
            verify(issuanceMapper).insert(any(ProofBundleIssuance.class));
        }
    }

    /**
     * 验证全局 signing key 首次注册发生瞬态数据库锁竞争时会重跑完整签发事务。
     */
    @Test
    void shouldRetryWholeIssuanceTransactionAfterTransientKeyRegistrationContention() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(signingKeyMapper.registerKey(any(ProofSigningKeyRecord.class)))
                .thenThrow(new CannotAcquireLockException("simulated signing key contention"))
                .thenReturn(1);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            ProofArchive archive = service.exportByFileId(USER_ID, FILE_ID);

            assertThat(archive.compactJws()).isNotBlank();
            verify(transactionTemplate, times(2)).execute(any());
            verify(signingKeyMapper, times(2)).registerKey(any(ProofSigningKeyRecord.class));
            verify(issuanceMapper).insert(any(ProofBundleIssuance.class));
        }
    }

    /**
     * 验证签发事务连续遭遇瞬态数据库锁竞争时会在有限重试后返回可重试错误。
     */
    @Test
    void shouldFailRetryablyAfterIssuanceTransactionContentionIsExhausted() {
        File file = file();
        AttestationLeaf leaf = leaf();
        mockSuccessfulEvidence(file, leaf);
        when(signingKeyMapper.registerKey(any(ProofSigningKeyRecord.class)))
                .thenThrow(new CannotAcquireLockException("persistent signing key contention"));

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertThatThrownBy(() -> service.exportByFileId(USER_ID, FILE_ID))
                    .isInstanceOf(RetryableException.class)
                    .satisfies(error -> assertThat(((RetryableException) error).getResultEnum())
                            .isEqualTo(ResultEnum.SERVICE_UNAVAILABLE));
            verify(transactionTemplate, times(3)).execute(any());
            verify(signingKeyMapper, times(3)).registerKey(any(ProofSigningKeyRecord.class));
            verify(issuanceMapper, never()).insert(any(ProofBundleIssuance.class));
        }
    }

    /**
     * 验证 revoke 幂等推进状态版本，公开状态与公钥响应不暴露内部租户/文件 ID。
     */
    @Test
    void shouldRevokeIdempotentlyAndExposeOnlyPublicViews() {
        File file = file();
        AttestationLeaf leaf = leaf();
        ProofBundleIssuance active = issuance("ACTIVE").setStatusVersion(3L);
        ProofBundleIssuance revoked = issuance("REVOKED")
                .setStatusVersion(4L)
                .setStatusReason("owner request")
                .setRevokedAt(new Date());
        when(leafMapper.selectById(LEAF_ID)).thenReturn(leaf);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(active, revoked);
        when(issuanceMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            ProofStatusVO status = service.revokeByLeafId(USER_ID, LEAF_ID, " owner request ");
            assertThat(status.status()).isEqualTo("REVOKED");
            assertThat(status.statusVersion()).isEqualTo(4L);
        }

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);
            ProofStatusVO repeated = service.revokeByLeafId(USER_ID, LEAF_ID, "ignored");
            assertThat(repeated.statusVersion()).isEqualTo(4L);
        }

        TenantContext.clear();
        when(issuanceMapper.selectPublicByProofId(revoked.getProofId())).thenReturn(revoked);
        when(signingKeyMapper.selectByIdentity(revoked.getKeyId(), revoked.getKeyVersion()))
                .thenReturn(keyRecord(revoked, "ACTIVE"));
        ProofStatusVO publicStatus = service.getPublicStatus(revoked.getProofId());
        ProofSigningKeyVO publicKey = service.getPublicSigningKey(revoked.getKeyId(), revoked.getKeyVersion());

        assertThat(JsonConverter.toJson(publicStatus)).doesNotContain("tenantId", "fileId", "leafId");
        assertThat(publicStatus.status()).isEqualTo("REVOKED");
        assertThat(publicKey.publicKeySpki()).isEqualTo(revoked.getPublicKeySpki());
    }

    /**
     * 验证 INVALID 完整性终态不能被后续撤销降级覆盖。
     */
    @Test
    void shouldRejectRevocationOfInvalidProof() {
        File file = file();
        AttestationLeaf leaf = leaf();
        ProofBundleIssuance invalid = issuance("INVALID")
                .setStatusVersion(2L)
                .setStatusReason("immutable_snapshot_validation_failed");
        when(leafMapper.selectById(LEAF_ID)).thenReturn(leaf);
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(issuanceMapper.selectByLeafForUpdate(TENANT_ID, LEAF_ID)).thenReturn(invalid);

        try (MockedStatic<SecurityUtils> security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::isAdmin).thenReturn(false);

            assertFileRecordError(() -> service.revokeByLeafId(USER_ID, LEAF_ID, "owner request"));
        }

        verify(issuanceMapper, never()).update(eq(null), any(Wrapper.class));
    }

    /**
     * 配置全部成功证据与 mapper 返回值。
     */
    private void mockSuccessfulEvidence(File file, AttestationLeaf leaf) {
        AttestationBatch batch = batch(leaf);
        ChunkManifestView manifest = manifest();
        when(fileMapper.selectById(FILE_ID)).thenReturn(file);
        when(fileMapper.lockVersionGroupForProofLifecycle(TENANT_ID, FILE_ID)).thenReturn(FILE_ID);
        when(fileMapper.selectCount(any())).thenReturn(0L);
        when(leafMapper.selectOne(any())).thenReturn(leaf);
        when(leafMapper.selectById(LEAF_ID)).thenReturn(leaf);
        when(batchMapper.selectById(BATCH_ID)).thenReturn(batch);
        when(chunkManifestService.findActiveManifest(null, FILE_ID)).thenReturn(Optional.of(manifest));
        when(chunkManifestService.calculateManifestHash(any())).thenReturn(MANIFEST_HASH);
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH)).thenReturn(Result.success(
                new StorageObjectHeadVO(
                        true, STORAGE_PATH, CIPHER_HASH, TENANT_ID, TENANT_ID,
                        "node-a", 3L, "etag", CIPHER_HASH)));
        when(attestationBatchPersistenceService.requireContractRegistry(any())).thenReturn(contractRegistry());
        when(issuanceMapper.selectOne(any())).thenReturn(null);
        when(issuanceMapper.insert(any(ProofBundleIssuance.class))).thenReturn(1);
        when(signingKeyMapper.registerKey(any(ProofSigningKeyRecord.class))).thenReturn(1);
        when(signingKeyMapper.selectByIdentityForUpdate(any(), any()))
                .thenAnswer(invocation -> keyRecord(signingProvider.currentKey(), "ACTIVE"));
    }

    /**
     * 构造带独立 contentHash 与 chainRecordId 的成功文件。
     */
    private File file() {
        return new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("evidence.txt")
                .setFileParam(JsonConverter.toJson(java.util.Map.of(
                        "fileSize", 3,
                        "contentType", "text/plain",
                        "contentHash", CONTENT_HASH)))
                .setFileHash(CHAIN_RECORD_ID)
                .setContentHash(CONTENT_HASH)
                .setTransactionHash(FILE_TRANSACTION_HASH)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setDeleted(0)
                .setVersion(1)
                .setVersionGroupId(FILE_ID)
                .setIsLatest(1)
                .setCreateTime(ISSUED_AT);
    }

    /**
     * 构造单叶 Merkle proof，root 等于 leafHash。
     */
    private AttestationLeaf leaf() {
        String leafHash = merkleTreeService.calculateLeafHash(MANIFEST_HASH);
        return new AttestationLeaf()
                .setId(LEAF_ID)
                .setTenantId(TENANT_ID)
                .setBatchId(BATCH_ID)
                .setFileId(FILE_ID)
                .setFileVersion(1)
                .setManifestId(MANIFEST_ID)
                .setFileHash(CHAIN_RECORD_ID)
                .setEvidenceType("MANIFEST_HASH")
                .setEvidenceHash(MANIFEST_HASH)
                .setChainRecordId(CHAIN_RECORD_ID)
                .setLeafHash(leafHash)
                .setLeafIndex(0)
                .setProofPathJson("[]")
                .setProofAlgorithm(MerkleTreeService.PROOF_ALGORITHM)
                .setCreateTime(ISSUED_AT)
                .setDeleted(0);
    }

    /**
     * 构造已完成且链根一致的单叶 batch。
     */
    private AttestationBatch batch(AttestationLeaf leaf) {
        return new AttestationBatch()
                .setId(BATCH_ID)
                .setTenantId(TENANT_ID)
                .setBatchNo("MB-900")
                .setMerkleRoot(leaf.getLeafHash())
                .setProofAlgorithm(MerkleTreeService.PROOF_ALGORITHM)
                .setLeafCount(1)
                .setStatus("COMPLETED")
                .setChainTransactionHash(BATCH_TRANSACTION_HASH)
                .setChainFileHash(leaf.getLeafHash())
                .setConfirmationSource("CHAIN_WRITE")
                .setCreateTime(ISSUED_AT)
                .setDeleted(0);
    }

    /**
     * 构造 active chunk manifest 与一个存储对象。
     */
    private ChunkManifestView manifest() {
        return manifest(List.of(new ChunkManifestChunk(
                0,
                PLAIN_HASH,
                CIPHER_HASH,
                3,
                STORAGE_PATH,
                "S3",
                "etag",
                "SHA-256")));
    }

    /**
     * 构造总长度固定为 3 的 manifest，用于分片结构失败测试。
     */
    private ChunkManifestView manifest(List<ChunkManifestChunk> chunks) {
        return new ChunkManifestView(
                MANIFEST_ID,
                FILE_ID,
                1,
                SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA,
                CHAIN_RECORD_ID,
                MANIFEST_HASH,
                "SHA-256",
                3,
                1,
                3,
                PLAIN_HASH,
                "NONE",
                "S3",
                chunks);
    }

    /**
     * 构造可定向破坏 source schema、算法、尺寸或分片字段的 manifest。
     */
    private ChunkManifestView manifestWithContract(
            String schemaId,
            String hashAlgorithm,
            long chunkSize,
            String encryptionAlgorithm,
            String storageBackend,
            ChunkManifestChunk chunk
    ) {
        return new ChunkManifestView(
                MANIFEST_ID,
                FILE_ID,
                1,
                schemaId,
                CHAIN_RECORD_ID,
                MANIFEST_HASH,
                hashAlgorithm,
                chunkSize,
                1,
                3,
                PLAIN_HASH,
                encryptionAlgorithm,
                storageBackend,
                List.of(chunk));
    }

    /**
     * 构造指纹自洽的不可变合约注册表快照。
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
     * 构造指纹可重算但 schema 不受支持的 registry，验证签发边界不只信任自洽指纹。
     */
    private ContractRegistryEntryResponse invalidSelfConsistentContractRegistry() {
        ContractRegistryEntryResponse valid = contractRegistry();
        return new ContractRegistryEntryResponse(
                "unsupported-registry.v1",
                null,
                valid.contractName(),
                valid.semanticVersion(),
                valid.chainType(),
                valid.chainId(),
                valid.groupId(),
                valid.contractAddress(),
                valid.abiFingerprintAlgorithm(),
                valid.abiSha256(),
                valid.artifactBytecodeSha256(),
                valid.onChainCodeSha256(),
                valid.deploymentTransactionHash(),
                valid.deploymentBlockNumber(),
                valid.status(),
                valid.effectiveAt(),
                valid.upgradeStrategy()).withCalculatedRegistryFingerprint();
    }

    /**
     * 构造用于公开状态和 revoke 的已签发记录。
     */
    private ProofBundleIssuance issuance(String status) {
        ProofSigningKeyMetadata key = signingProvider.currentKey();
        return new ProofBundleIssuance()
                .setId(1_001L)
                .setTenantId(TENANT_ID)
                .setProofId("rp-proof-" + "a".repeat(64))
                .setFileId(FILE_ID)
                .setFileVersion(1)
                .setLeafId(LEAF_ID)
                .setSignatureAlgorithm(key.algorithm())
                .setKeyId(key.keyId())
                .setKeyVersion(key.keyVersion())
                .setPublicKeySpki(key.publicKeySpki())
                .setPublicKeyFingerprint(key.publicKeyFingerprint())
                .setIssuedStatus("ACTIVE")
                .setStatus(status)
                .setStatusVersion(1L)
                .setIssuedAt(ISSUED_AT)
                .setCreateTime(ISSUED_AT)
                .setUpdateTime(ISSUED_AT)
                .setDeleted(0);
    }

    /**
     * 从签发快照构造全局公钥注册记录。
     */
    private ProofSigningKeyRecord keyRecord(ProofBundleIssuance issuance, String status) {
        return new ProofSigningKeyRecord()
                .setId(2_001L)
                .setKeyId(issuance.getKeyId())
                .setKeyVersion(issuance.getKeyVersion())
                .setSignatureAlgorithm(issuance.getSignatureAlgorithm())
                .setPublicKeySpki(issuance.getPublicKeySpki())
                .setPublicKeyFingerprint(issuance.getPublicKeyFingerprint())
                .setStatus(status)
                .setFirstSeenAt(ISSUED_AT)
                .setDeleted(0);
    }

    /**
     * 从签名 provider 元数据构造全局公钥注册记录。
     */
    private ProofSigningKeyRecord keyRecord(ProofSigningKeyMetadata key, String status) {
        return new ProofSigningKeyRecord()
                .setId(2_001L)
                .setKeyId(key.keyId())
                .setKeyVersion(key.keyVersion())
                .setSignatureAlgorithm(key.algorithm())
                .setPublicKeySpki(key.publicKeySpki())
                .setPublicKeyFingerprint(key.publicKeyFingerprint())
                .setStatus(status)
                .setFirstSeenAt(ISSUED_AT)
                .setDeleted(0);
    }

    /**
     * 使用独立 SDK 对后端刚签发的真实 ZIP、JWS 和共享合同执行端到端验收。
     */
    private void assertPublicVerifierAccepts(ProofArchive archive, ProofBundleIssuance issuance) {
        try {
            Path original = tempDirectory.resolve("backend-produced-original.txt");
            Path proof = tempDirectory.resolve("backend-produced-proof.zip");
            Files.writeString(original, ORIGINAL_TEXT);
            Files.write(proof, archive.toByteArray());

            ProofSigningKeyMetadata key = signingProvider.currentKey();
            PublicSigningKey publicKey = new PublicSigningKey(
                    key.keyId(),
                    key.keyVersion(),
                    key.algorithm(),
                    key.publicKeySpki(),
                    key.publicKeyFingerprint(),
                    "backend-production-test");
            Instant issuedAt = issuance.getIssuedAt().toInstant();
            PublicProofStatus status = new PublicProofStatus(
                    issuance.getProofId(),
                    "ACTIVE",
                    String.valueOf(issuance.getStatusVersion()),
                    issuance.getIssuedStatus(),
                    issuance.getKeyId(),
                    issuance.getKeyVersion(),
                    null,
                    issuedAt.toString(),
                    issuedAt.toString(),
                    "backend-production-test");
            ContractRegistryEntryResponse registry = contractRegistry();
            ChainRootEvidence chain = new ChainRootEvidence(
                    ChainRootEvidence.SCHEMA_VERSION,
                    registry.chainType(),
                    registry.chainId(),
                    registry.groupId(),
                    registry.contractAddress(),
                    "MB-900",
                    leaf().getLeafHash(),
                    BATCH_TRANSACTION_HASH,
                    100L,
                    "backend-production-test");
            VerificationContext context = new VerificationContext(
                    VerificationLimits.defaults(),
                    (keyId, keyVersion) -> Resolution.resolved(publicKey),
                    proofId -> Resolution.resolved(status),
                    query -> Resolution.resolved(chain),
                    Clock.fixed(issuedAt.plusSeconds(1), ZoneOffset.UTC));

            assertThat(new DefaultProofVerifier().verify(original, proof, context).outcome())
                    .isEqualTo(VerificationOutcome.VALID);
        } catch (java.io.IOException e) {
            throw new AssertionError("公共 verifier 兼容性 fixture 写入失败", e);
        }
    }

    /**
     * 断言 runnable 以 FILE_RECORD_ERROR 失败。
     */
    private void assertFileRecordError(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(GeneralException.class)
                .satisfies(error -> assertThat(((GeneralException) error).getResultEnum())
                        .isEqualTo(ResultEnum.FILE_RECORD_ERROR));
    }

    /**
     * 构造完整 ACTIVE proof signer 配置。
     */
    private ProofSigningProperties activeSigningProperties() throws Exception {
        KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        ProofSigningProperties properties = new ProofSigningProperties();
        properties.setEnabled(true);
        properties.setAlgorithm("Ed25519");
        properties.setKeyId("proof-key-main");
        properties.setKeyVersion(1);
        properties.setKeyStatus("ACTIVE");
        properties.setPrivateKeyPkcs8(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded()));
        properties.setPublicKeySpki(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        return properties;
    }
}
