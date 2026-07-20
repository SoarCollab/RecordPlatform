package cn.flying.service.integrity;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.manifest.ChunkManifestBatchView;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.sse.SseEmitterManager;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for manifest-backed lightweight, medium, and heavy integrity checks.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Manifest-backed three-tier integrity checks")
class IntegrityCheckServiceThreeTierTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final Long FILE_ID = 99L;
    private static final String CHAIN_RECORD_ID = "chain-record-99";

    @Mock
    private FileMapper fileMapper;

    @Mock
    private IntegrityAlertMapper integrityAlertMapper;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private SseEmitterManager sseEmitterManager;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @Mock
    private ChunkManifestService chunkManifestService;

    private final ChunkManifestCanonicalizer canonicalizer = new ChunkManifestCanonicalizer();
    private IntegrityCheckService service;

    /**
     * Initializes MyBatis-Plus lambda metadata used by query-wrapper assertions.
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, IntegrityAlert.class);
    }

    /**
     * Creates the service with deterministic sampling and successful lock defaults.
     */
    @BeforeEach
    void setUp() throws Exception {
        service = new IntegrityCheckService(
                fileMapper,
                integrityAlertMapper,
                tenantMapper,
                fileRemoteClient,
                sseEmitterManager,
                redissonClient,
                chunkManifestService
        );
        ReflectionTestUtils.setField(service, "sampleRate", 1.0);
        ReflectionTestUtils.setField(service, "batchSize", 50);
        ReflectionTestUtils.setField(service, "lockTimeoutSeconds", 1800L);
        ReflectionTestUtils.setField(service, "heavySampleChunks", 1);
        ReflectionTestUtils.setField(service, "heavyMaxDownloadBytes", 80L * 1024 * 1024);

        when(redissonClient.getLock("integrity-check-lock")).thenReturn(rLock);
        when(rLock.tryLock(0, 1800L, TimeUnit.SECONDS)).thenReturn(true);
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of(TENANT_ID));
        when(integrityAlertMapper.selectCount(any())).thenReturn(0L);
        when(integrityAlertMapper.insert(any(IntegrityAlert.class))).thenAnswer(invocation -> {
            IntegrityAlert alert = invocation.getArgument(0);
            alert.setId(1000L);
            return 1;
        });
        when(chunkManifestService.calculateManifestHash(any()))
                .thenAnswer(invocation -> canonicalizer.manifestHash(invocation.getArgument(0)));
    }

    /**
     * Clears thread-local tenant state between tests.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Verifies lightweight mode HEAD-checks every manifest chunk without content or chain calls.
     */
    @Test
    void lightweight_shouldUseEveryManifestPathAndCipherHashWithoutDownload() {
        File file = file(16L);
        ChunkManifestView manifest = manifest(file, bytes("chunk-a!"), bytes("chunk-b!"));
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        for (ChunkManifestChunk chunk : manifest.chunks()) {
            verify(fileRemoteClient).headObject(chunk.storagePath(), chunk.cipherHash());
        }
        verify(fileRemoteClient, times(2)).headObject(anyString(), anyString());
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
        verify(chunkManifestService, never()).calculateManifestHash(any());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Verifies provider-local replica ETags are not treated as cross-node content hashes.
     */
    @Test
    void lightweight_shouldAcceptDifferentReplicaEtagWhenContentEvidenceMatches() {
        File file = file(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndManifest(file, manifest);
        ChunkManifestChunk chunk = manifest.chunks().getFirst();
        when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                .thenReturn(successHead(new StorageObjectHeadVO(
                        true,
                        chunk.storagePath(),
                        chunk.cipherHash(),
                        TENANT_ID,
                        TENANT_ID,
                        "repaired-node-b",
                        chunk.size(),
                        "\"provider-local-repair-etag\"",
                        chunk.cipherHash()
                )));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
    }

    /**
     * Verifies a file without active manifest produces a distinct migration warning.
     */
    @Test
    void lightweight_shouldClassifyMissingManifestSeparately() {
        File file = file(8L);
        prepareFileAndBatch(file, ChunkManifestBatchView.empty());

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.MANIFEST_MISSING.name());
        assertThat(alert.getSeverity()).isEqualTo(IntegrityAlert.AlertSeverity.WARNING.name());
        assertThat(alert.getEvidence()).contains("active_manifest_missing");
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
    }

    /**
     * Verifies duplicate active rows are not hidden by newest-row selection.
     */
    @Test
    void lightweight_shouldRejectDuplicateActiveManifests() {
        File file = file(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndBatch(file, new ChunkManifestBatchView(
                Map.of(FILE_ID, manifest), Set.of(FILE_ID)));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        assertThat(insertedAlert().getAlertType())
                .isEqualTo(IntegrityAlert.AlertType.MANIFEST_INVALID.name());
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
    }

    /**
     * Verifies a cross-tenant object path is rejected before any storage RPC.
     */
    @Test
    void lightweight_shouldFailClosedOnCrossTenantStoragePath() {
        File file = file(8L);
        ChunkManifestView valid = manifest(file, bytes("content!"));
        ChunkManifestChunk original = valid.chunks().getFirst();
        ChunkManifestChunk crossTenant = new ChunkManifestChunk(
                original.index(), original.plainHash(), original.cipherHash(), original.size(),
                original.storagePath().replace("tenant/7", "tenant/8"),
                original.storageBackend(), original.etag(), original.checksumAlgorithm());
        ChunkManifestView tampered = withChunks(valid, List.of(crossTenant));
        prepareFileAndManifest(file, tampered);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.MANIFEST_INVALID.name());
        assertThat(alert.getEvidence()).contains("storage_path_mismatch");
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
    }

    /**
     * Verifies an out-of-scope database row is rejected before any downstream lookup.
     */
    @Test
    void lightweight_shouldRejectCrossTenantFileBeforeManifestLookup() {
        File file = file(8L).setTenantId(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndManifest(file, manifest);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 1));
        verify(chunkManifestService, never()).findActiveManifests(anyList());
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Verifies missing tenant metadata is a fail-closed metadata mismatch.
     */
    @Test
    void lightweight_shouldDetectMissingTenantMetadata() {
        File file = file(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndManifest(file, manifest);
        ChunkManifestChunk chunk = manifest.chunks().getFirst();
        when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                .thenReturn(successHead(new StorageObjectHeadVO(
                        true,
                        chunk.storagePath(),
                        chunk.cipherHash(),
                        TENANT_ID,
                        null,
                        "node-a",
                        chunk.size(),
                        chunk.etag(),
                        chunk.cipherHash()
                )));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.METADATA_MISMATCH.name());
        assertThat(alert.getEvidence()).contains("field=metadataTenantId");
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
    }

    /**
     * Verifies an explicit missing HEAD result becomes OBJECT_NOT_FOUND.
     */
    @Test
    void lightweight_shouldDetectMissingObject() {
        File file = file(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndManifest(file, manifest);
        ChunkManifestChunk chunk = manifest.chunks().getFirst();
        when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                .thenReturn(successHead(StorageObjectHeadVO.missing(
                        chunk.storagePath(), chunk.cipherHash(), TENANT_ID)));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        assertThat(insertedAlert().getAlertType())
                .isEqualTo(IntegrityAlert.AlertType.OBJECT_NOT_FOUND.name());
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
    }

    /**
     * Verifies medium mode validates canonical data but still performs no download or chain query.
     */
    @Test
    void medium_shouldValidateCanonicalManifestWithoutDownloading() {
        File file = file(16L);
        ChunkManifestView manifest = manifest(file, bytes("chunk-a!"), bytes("chunk-b!"));
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        verify(chunkManifestService).calculateManifestHash(any(ChunkManifestDraft.class));
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
    }

    /**
     * Verifies canonical hash tampering is detected before object RPCs.
     */
    @Test
    void medium_shouldDetectCanonicalManifestHashTampering() {
        File file = file(8L);
        ChunkManifestView valid = manifest(file, bytes("content!"));
        ChunkManifestView tampered = withManifestHash(valid, "sha256:" + "b".repeat(64));
        prepareFileAndManifest(file, tampered);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.MANIFEST_INVALID.name());
        assertThat(alert.getEvidence()).contains("manifest_hash_mismatch");
        assertThat(alert.getActualHash()).startsWith("sha256:");
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
    }

    /**
     * Verifies non-contiguous chunk order is classified as an invalid manifest.
     */
    @Test
    void medium_shouldDetectNonContiguousChunkOrder() {
        File file = file(16L);
        ChunkManifestView valid = manifest(file, bytes("chunk-a!"), bytes("chunk-b!"));
        ChunkManifestChunk second = valid.chunks().get(1);
        ChunkManifestChunk reordered = new ChunkManifestChunk(
                3, second.plainHash(), second.cipherHash(), second.size(), second.storagePath(),
                second.storageBackend(), second.etag(), second.checksumAlgorithm());
        ChunkManifestView tampered = withChunks(valid, List.of(valid.chunks().getFirst(), reordered));
        prepareFileAndManifest(file, tampered);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.MEDIUM);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        assertThat(insertedAlert().getEvidence()).contains("chunk_order_invalid");
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
    }

    /**
     * Verifies heavy mode HEAD-checks all chunks but downloads only one sampled object.
     */
    @Test
    void heavy_shouldDownloadOneSampleAndUseChainRecordId() {
        File file = file(16L);
        byte[] first = bytes("chunk-a!");
        byte[] second = bytes("chunk-b!");
        ChunkManifestView manifest = manifest(file, first, second);
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);
        stubSampleDownloads(manifest, List.of(first, second));
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(successChain(CHAIN_RECORD_ID, file.getFileSize()));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        verify(fileRemoteClient, times(2)).headObject(anyString(), anyString());
        verify(fileRemoteClient, times(1)).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient).getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID);
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Verifies sampled bytes are hashed against cipherHash rather than file.fileHash.
     */
    @Test
    void heavy_shouldDetectSampledCipherHashMismatch() {
        File file = file(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);
        when(fileRemoteClient.getFileListByHash(anyList(), anyList()))
                .thenReturn(new Result<>(ResultEnum.SUCCESS, List.of(bytes("tampered"))));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.CONTENT_HASH_MISMATCH.name());
        assertThat(alert.getActualHash()).isEqualTo(sha256(bytes("tampered")));
        assertThat(alert.getEvidence()).contains("sampled_content_mismatch");
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
    }

    /**
     * Verifies an invalid multi-item response is an execution error rather than a false alert.
     */
    @Test
    void heavy_shouldRejectUnexpectedDownloadItemCount() {
        File file = file(8L);
        byte[] content = bytes("content!");
        ChunkManifestView manifest = manifest(file, content);
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);
        when(fileRemoteClient.getFileListByHash(anyList(), anyList()))
                .thenReturn(new Result<>(ResultEnum.SUCCESS, List.of(content, content)));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 1));
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Verifies chain response mismatches use their own alert type and evidence field.
     */
    @Test
    void heavy_shouldDetectChainRecordMismatch() {
        File file = file(8L);
        byte[] content = bytes("content!");
        ChunkManifestView manifest = manifest(file, content);
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);
        stubSampleDownloads(manifest, List.of(content));
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(successChain("other-chain-record", file.getFileSize()));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.CHAIN_MISMATCH.name());
        assertThat(alert.getChainHash()).isEqualTo("other-chain-record");
    }

    /**
     * Verifies an oversized heavy sample becomes an execution error before bytes are allocated.
     */
    @Test
    void heavy_shouldEnforceDownloadByteLimitBeforeDownload() {
        File file = file(8L);
        ChunkManifestView manifest = manifest(file, bytes("content!"));
        prepareFileAndManifest(file, manifest);
        stubValidHeads(manifest);
        ReflectionTestUtils.setField(service, "heavyMaxDownloadBytes", 7L);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.HEAVY);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 1));
        verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
        verify(fileRemoteClient, never()).getFile(anyString(), anyString());
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Registers one file page and its active manifest batch.
     */
    private void prepareFileAndManifest(File file, ChunkManifestView manifest) {
        prepareFileAndBatch(file, new ChunkManifestBatchView(Map.of(file.getId(), manifest), Set.of()));
    }

    /**
     * Registers one file page and an explicit manifest batch response.
     */
    private void prepareFileAndBatch(File file, ChunkManifestBatchView batch) {
        Page<File> page = new Page<>();
        page.setRecords(List.of(file));
        when(fileMapper.selectPage(any(), any())).thenReturn(page);
        when(chunkManifestService.findActiveManifests(anyList())).thenReturn(batch);
    }

    /**
     * Builds a successful file record whose fileHash is intentionally not a content digest.
     */
    private File file(long fileSize) {
        return new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("manifest.bin")
                .setFileHash(CHAIN_RECORD_ID)
                .setFileSize(fileSize)
                .setVersion(1)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setDeleted(0);
    }

    /**
     * Builds a valid canonical NONE-encryption manifest for the supplied chunk bytes.
     */
    private ChunkManifestView manifest(File file, byte[]... contents) {
        long chunkSize = contents[0].length;
        long totalSize = 0;
        List<ChunkManifestChunk> chunks = new java.util.ArrayList<>();
        for (int index = 0; index < contents.length; index++) {
            byte[] content = contents[index];
            String hash = sha256(content);
            totalSize += content.length;
            chunks.add(new ChunkManifestChunk(
                    index,
                    hash,
                    hash,
                    content.length,
                    "storage/tenant/" + TENANT_ID + "/chunk/" + hash,
                    "S3",
                    "etag-" + index,
                    "SHA-256"
            ));
        }
        ChunkManifestDraft draft = new ChunkManifestDraft(
                ChunkManifestCanonicalizer.SCHEMA_ID,
                file.getFileHash(),
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                chunkSize,
                totalSize,
                null,
                "NONE",
                "S3",
                List.copyOf(chunks)
        );
        return new ChunkManifestView(
                1000L,
                file.getId(),
                file.getVersion(),
                draft.schemaId(),
                draft.fileHash(),
                canonicalizer.manifestHash(draft),
                draft.hashAlgorithm(),
                draft.chunkSize(),
                chunks.size(),
                draft.totalSize(),
                draft.merkleRoot(),
                draft.encryptionAlgorithm(),
                draft.storageBackend(),
                List.copyOf(chunks)
        );
    }

    /**
     * Returns a manifest copy with a substituted persisted canonical hash.
     */
    private ChunkManifestView withManifestHash(ChunkManifestView source, String manifestHash) {
        return new ChunkManifestView(
                source.manifestId(), source.fileId(), source.fileVersion(), source.schemaId(),
                source.fileHash(), manifestHash, source.hashAlgorithm(), source.chunkSize(),
                source.chunkCount(), source.totalSize(), source.merkleRoot(),
                source.encryptionAlgorithm(), source.storageBackend(), source.chunks());
    }

    /**
     * Returns a manifest copy with substituted chunk rows while preserving header evidence.
     */
    private ChunkManifestView withChunks(ChunkManifestView source, List<ChunkManifestChunk> chunks) {
        return new ChunkManifestView(
                source.manifestId(), source.fileId(), source.fileVersion(), source.schemaId(),
                source.fileHash(), source.manifestHash(), source.hashAlgorithm(), source.chunkSize(),
                source.chunkCount(), source.totalSize(), source.merkleRoot(),
                source.encryptionAlgorithm(), source.storageBackend(), chunks);
    }

    /**
     * Stubs fail-closed HEAD metadata that exactly matches every manifest chunk.
     */
    private void stubValidHeads(ChunkManifestView manifest) {
        for (ChunkManifestChunk chunk : manifest.chunks()) {
            StorageObjectHeadVO head = new StorageObjectHeadVO(
                    true,
                    chunk.storagePath(),
                    chunk.cipherHash(),
                    TENANT_ID,
                    TENANT_ID,
                    "node-a",
                    chunk.size(),
                    "\"" + chunk.etag() + "\"",
                    chunk.cipherHash()
            );
            when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                    .thenReturn(successHead(head));
        }
    }

    /**
     * Stubs sampled downloads by looking up the requested manifest cipher hash.
     */
    private void stubSampleDownloads(ChunkManifestView manifest, List<byte[]> contents) {
        Map<String, byte[]> contentByHash = new java.util.HashMap<>();
        for (int index = 0; index < manifest.chunks().size(); index++) {
            contentByHash.put(manifest.chunks().get(index).cipherHash(), contents.get(index));
        }
        when(fileRemoteClient.getFileListByHash(anyList(), anyList())).thenAnswer(invocation -> {
            List<String> hashes = invocation.getArgument(1);
            return new Result<>(ResultEnum.SUCCESS, List.of(contentByHash.get(hashes.getFirst())));
        });
    }

    /**
     * Creates a successful storage HEAD result.
     */
    private Result<StorageObjectHeadVO> successHead(StorageObjectHeadVO head) {
        return new Result<>(ResultEnum.SUCCESS, head);
    }

    /**
     * Creates a successful chain lookup result with the requested returned record ID.
     */
    private Result<FileDetailVO> successChain(String returnedRecordId, long fileSize) {
        FileDetailVO detail = new FileDetailVO(
                String.valueOf(USER_ID),
                "manifest.bin",
                "{}",
                "",
                returnedRecordId,
                "2026-07-13",
                0L,
                fileSize,
                "application/octet-stream"
        );
        return new Result<>(ResultEnum.SUCCESS, detail);
    }

    /**
     * Captures the most recently inserted integrity alert.
     */
    private IntegrityAlert insertedAlert() {
        ArgumentCaptor<IntegrityAlert> captor = ArgumentCaptor.forClass(IntegrityAlert.class);
        verify(integrityAlertMapper, atLeastOnce()).insert(captor.capture());
        return captor.getAllValues().getLast();
    }

    /**
     * Converts a test string to UTF-8 bytes.
     */
    private byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Calculates the canonical sha256-prefixed digest used by direct-upload chunks.
     */
    private String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
