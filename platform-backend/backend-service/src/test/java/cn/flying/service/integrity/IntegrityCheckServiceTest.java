package cn.flying.service.integrity;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.exception.GeneralException;
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
import cn.flying.service.sse.SseEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Operational, dependency-failure, alert-lifecycle, and tenant-context tests.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("IntegrityCheckService operational behavior")
class IntegrityCheckServiceTest {

    private static final Long TENANT_ID = 7L;
    private static final Long USER_ID = 42L;
    private static final Long FILE_ID = 99L;
    private static final String CHAIN_RECORD_ID = "chain-record-99";
    private static final byte[] CONTENT = "content!".getBytes(StandardCharsets.UTF_8);

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
     * Initializes MyBatis-Plus lambda metadata used by service query wrappers.
     */
    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
        TableInfoHelper.initTableInfo(assistant, IntegrityAlert.class);
    }

    /**
     * Creates a deterministic service and successful infrastructure defaults.
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
     * Verifies a successful dependency response with no record becomes CHAIN_NOT_FOUND.
     */
    @Test
    void heavy_shouldClassifyMissingChainRecord() {
        File file = file();
        prepareValidHeavyInputs(file);
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(new Result<>(ResultEnum.GET_USER_FILE_ERROR, null));

        IntegrityCheckStatsVO stats = service.checkIntegrity();

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.CHAIN_NOT_FOUND.name());
        assertThat(alert.getSeverity()).isEqualTo(IntegrityAlert.AlertSeverity.ERROR.name());
        assertThat(alert.getChainHash()).isNull();
    }

    /**
     * Verifies storage availability failures count as execution errors, not corruption.
     */
    @Test
    void lightweight_shouldCountStorageDependencyFailureAsError() {
        File file = file();
        ChunkManifestView manifest = manifest(file);
        prepareFileAndManifest(file, manifest);
        ChunkManifestChunk chunk = manifest.chunks().getFirst();
        when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                .thenReturn(new Result<>(ResultEnum.FILE_SERVICE_ERROR, null));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 1));
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Verifies blockchain availability failures do not create false mismatch alerts.
     */
    @Test
    void heavy_shouldCountBlockchainDependencyFailureAsError() {
        File file = file();
        prepareValidHeavyInputs(file);
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(new Result<>(ResultEnum.BLOCKCHAIN_ERROR, null));

        IntegrityCheckStatsVO stats = service.checkIntegrity();

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 1));
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
    }

    /**
     * Verifies share-saved records query the blockchain with the original uploader.
     */
    @Test
    void heavy_shouldResolveOriginalUploaderForShareSavedFile() {
        Long originFileId = 1234L;
        Long recipientId = 88L;
        File file = file().setUid(recipientId).setOrigin(originFileId);
        File origin = new File().setId(originFileId).setUid(USER_ID).setTenantId(TENANT_ID);
        when(fileMapper.selectByIdIncludeDeleted(originFileId)).thenReturn(origin);
        prepareValidHeavyInputs(file);
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(successChain(CHAIN_RECORD_ID));

        IntegrityCheckStatsVO stats = service.checkIntegrity();

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        verify(fileRemoteClient).getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID);
        verify(fileRemoteClient, never()).getFile(String.valueOf(recipientId), CHAIN_RECORD_ID);
    }

    /**
     * Verifies missing uploader metadata becomes an explicit finding without an invalid remote lookup.
     */
    @Test
    void heavy_shouldClassifyMissingBlockchainUploaderWithoutRemoteLookup() {
        File file = file().setUid(null);
        prepareValidHeavyInputs(file);

        IntegrityCheckStatsVO stats = service.checkIntegrity();

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        IntegrityAlert alert = insertedAlert();
        assertThat(alert.getAlertType()).isEqualTo(IntegrityAlert.AlertType.CHAIN_NOT_FOUND.name());
        assertThat(alert.getEvidence()).contains("reason=chain_uploader_missing");
        verify(fileRemoteClient, never()).getFile(anyString(), eq(CHAIN_RECORD_ID));
    }

    /**
     * Verifies an open alert suppresses duplicate insertion and duplicate SSE delivery.
     */
    @Test
    void alertCreation_shouldDeduplicatePendingAndAcknowledgedFindings() {
        File file = file();
        prepareFileAndBatch(file, ChunkManifestBatchView.empty());
        when(integrityAlertMapper.selectCount(any())).thenReturn(1L);

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 1, 0));
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
        verify(sseEmitterManager, never()).broadcastToAdmins(any(), any());
    }

    /**
     * Verifies new alerts expose stable severity and evidence in the SSE payload.
     */
    @Test
    void alertCreation_shouldBroadcastSeverityAndEvidence() {
        File file = file();
        prepareFileAndBatch(file, ChunkManifestBatchView.empty());

        service.checkIntegrityWithLevel(IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        ArgumentCaptor<SseEvent> eventCaptor = ArgumentCaptor.forClass(SseEvent.class);
        verify(sseEmitterManager).broadcastToAdmins(eq(TENANT_ID), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getPayload()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) eventCaptor.getValue().getPayload();
        assertThat(payload)
                .containsEntry("alertType", IntegrityAlert.AlertType.MANIFEST_MISSING.name())
                .containsEntry("severity", IntegrityAlert.AlertSeverity.WARNING.name());
        assertThat(payload.get("evidence")).asString().contains("active_manifest_missing");
    }

    /**
     * Verifies a failed batch manifest query accounts for each file and does not create alerts.
     */
    @Test
    void checkIntegrity_shouldCountManifestBatchFailureForEveryFile() {
        File first = file();
        File second = file().setId(100L).setFileHash("chain-record-100");
        prepareFiles(List.of(first, second));
        when(chunkManifestService.findActiveManifests(anyList()))
                .thenThrow(new IllegalStateException("database unavailable"));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(2, 0, 2));
        verify(integrityAlertMapper, never()).insert(any(IntegrityAlert.class));
        verify(fileRemoteClient, never()).headObject(anyString(), anyString());
    }

    /**
     * Verifies manual checks restore a caller's prior tenant context after HEAVY execution.
     */
    @Test
    void triggerManualCheck_shouldRestorePreviousTenantContext() {
        File file = file();
        prepareValidHeavyInputs(file);
        when(fileRemoteClient.getFile(String.valueOf(USER_ID), CHAIN_RECORD_ID))
                .thenReturn(successChain(CHAIN_RECORD_ID));
        TenantContext.setTenantId(999L);

        IntegrityCheckStatsVO stats = service.triggerManualCheck(TENANT_ID);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        assertThat(TenantContext.getTenantId()).isEqualTo(999L);
        verify(tenantMapper, never()).selectActiveTenantIds();
    }

    /**
     * Verifies invalid manual tenant input is rejected before lock acquisition.
     */
    @Test
    void triggerManualCheck_shouldRejectNullTenant() {
        assertThatThrownBy(() -> service.triggerManualCheck(null))
                .isInstanceOf(GeneralException.class);

        verify(redissonClient, never()).getLock(anyString());
    }

    /**
     * Verifies lock contention safely skips the run without touching persistence.
     */
    @Test
    void checkIntegrity_shouldSkipWhenDistributedLockIsHeld() throws Exception {
        when(rLock.tryLock(0, 1800L, TimeUnit.SECONDS)).thenReturn(false);

        IntegrityCheckStatsVO stats = service.checkIntegrity();

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(0, 0, 0));
        verify(tenantMapper, never()).selectActiveTenantIds();
        verify(fileMapper, never()).selectPage(any(), any());
    }

    /**
     * Verifies a scheduler run with no active tenants is a clean no-op.
     */
    @Test
    void checkIntegrity_shouldReturnZeroWhenNoTenantsExist() {
        when(tenantMapper.selectActiveTenantIds()).thenReturn(List.of());

        IntegrityCheckStatsVO stats = service.checkIntegrity();

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(0, 0, 0));
        verify(fileMapper, never()).selectPage(any(), any());
    }

    /**
     * Verifies the scan loads fileParam so the transient file size can be derived during validation.
     */
    @Test
    void lightweight_shouldSelectFileParamForDerivedFileSizeValidation() {
        File file = file()
                .setFileSize(null)
                .setFileParam("{\"fileSize\":" + CONTENT.length + "}");
        ChunkManifestView manifest = manifest(file);
        prepareFileAndManifest(file, manifest);
        ChunkManifestChunk chunk = manifest.chunks().getFirst();
        when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                .thenReturn(successHead(chunk));

        IntegrityCheckStatsVO stats = service.checkIntegrityWithLevel(
                IntegrityCheckService.IntegrityCheckLevel.LIGHTWEIGHT);

        assertThat(stats).isEqualTo(new IntegrityCheckStatsVO(1, 0, 0));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<File>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fileMapper).selectPage(any(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSelect()).contains("file_param");
        assertThat(file.getFileSize()).isEqualTo((long) CONTENT.length);
    }

    /**
     * Verifies acknowledgement preserves the alert lifecycle contract.
     */
    @Test
    void acknowledgeAlert_shouldUpdateStatus() {
        IntegrityAlert alert = new IntegrityAlert().setStatus(IntegrityAlert.AlertStatus.PENDING.getCode());
        when(integrityAlertMapper.selectById(1L)).thenReturn(alert);

        service.acknowledgeAlert(1L, USER_ID);

        assertThat(alert.getStatus()).isEqualTo(IntegrityAlert.AlertStatus.ACKNOWLEDGED.getCode());
        verify(integrityAlertMapper).updateById(alert);
    }

    /**
     * Verifies resolution records the operator, timestamp, and note.
     */
    @Test
    void resolveAlert_shouldUpdateResolutionFields() {
        IntegrityAlert alert = new IntegrityAlert().setStatus(IntegrityAlert.AlertStatus.ACKNOWLEDGED.getCode());
        when(integrityAlertMapper.selectById(1L)).thenReturn(alert);

        service.resolveAlert(1L, USER_ID, "re-uploaded");

        assertThat(alert.getStatus()).isEqualTo(IntegrityAlert.AlertStatus.RESOLVED.getCode());
        assertThat(alert.getResolvedBy()).isEqualTo(USER_ID);
        assertThat(alert.getResolvedAt()).isNotNull();
        assertThat(alert.getNote()).isEqualTo("re-uploaded");
        verify(integrityAlertMapper).updateById(alert);
    }

    /**
     * Registers a valid manifest, HEAD, sampled bytes, and file page for heavy tests.
     */
    private void prepareValidHeavyInputs(File file) {
        ChunkManifestView manifest = manifest(file);
        prepareFileAndManifest(file, manifest);
        ChunkManifestChunk chunk = manifest.chunks().getFirst();
        when(fileRemoteClient.headObject(chunk.storagePath(), chunk.cipherHash()))
                .thenReturn(successHead(chunk));
        when(fileRemoteClient.getFileListByHash(List.of(chunk.storagePath()), List.of(chunk.cipherHash())))
                .thenReturn(new Result<>(ResultEnum.SUCCESS, List.of(CONTENT)));
    }

    /**
     * Registers one file and its active manifest batch.
     */
    private void prepareFileAndManifest(File file, ChunkManifestView manifest) {
        prepareFileAndBatch(file, new ChunkManifestBatchView(Map.of(file.getId(), manifest), Set.of()));
    }

    /**
     * Registers one file and an explicit manifest batch response.
     */
    private void prepareFileAndBatch(File file, ChunkManifestBatchView batch) {
        prepareFiles(List.of(file));
        when(chunkManifestService.findActiveManifests(anyList())).thenReturn(batch);
    }

    /**
     * Registers one short file page for scheduler/manual query paths.
     */
    private void prepareFiles(List<File> files) {
        Page<File> page = new Page<>();
        page.setRecords(files);
        when(fileMapper.selectPage(any(), any())).thenReturn(page);
    }

    /**
     * Builds a successful file whose fileHash is an explicit chain record identifier.
     */
    private File file() {
        return new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(USER_ID)
                .setFileName("manifest.bin")
                .setFileHash(CHAIN_RECORD_ID)
                .setFileSize((long) CONTENT.length)
                .setVersion(1)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setDeleted(0);
    }

    /**
     * Builds a valid canonical single-chunk NONE manifest.
     */
    private ChunkManifestView manifest(File file) {
        String hash = sha256(CONTENT);
        ChunkManifestChunk chunk = new ChunkManifestChunk(
                0,
                hash,
                hash,
                CONTENT.length,
                "storage/tenant/" + TENANT_ID + "/chunk/" + hash,
                "S3",
                "etag-0",
                "SHA-256"
        );
        ChunkManifestDraft draft = new ChunkManifestDraft(
                ChunkManifestCanonicalizer.SCHEMA_ID,
                file.getFileHash(),
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                CONTENT.length,
                CONTENT.length,
                null,
                "NONE",
                "S3",
                List.of(chunk)
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
                1,
                draft.totalSize(),
                null,
                draft.encryptionAlgorithm(),
                draft.storageBackend(),
                List.of(chunk)
        );
    }

    /**
     * Creates a complete successful storage HEAD response for one chunk.
     */
    private Result<StorageObjectHeadVO> successHead(ChunkManifestChunk chunk) {
        return new Result<>(ResultEnum.SUCCESS, new StorageObjectHeadVO(
                true,
                chunk.storagePath(),
                chunk.cipherHash(),
                TENANT_ID,
                TENANT_ID,
                "node-a",
                chunk.size(),
                "\"" + chunk.etag() + "\"",
                chunk.cipherHash()
        ));
    }

    /**
     * Creates a successful chain lookup response.
     */
    private Result<FileDetailVO> successChain(String returnedRecordId) {
        FileDetailVO detail = new FileDetailVO(
                String.valueOf(USER_ID), "manifest.bin", "{}", "", returnedRecordId,
                "2026-07-13", 0L, (long) CONTENT.length, "application/octet-stream");
        return new Result<>(ResultEnum.SUCCESS, detail);
    }

    /**
     * Captures the alert inserted by the current test.
     */
    private IntegrityAlert insertedAlert() {
        ArgumentCaptor<IntegrityAlert> captor = ArgumentCaptor.forClass(IntegrityAlert.class);
        verify(integrityAlertMapper).insert(captor.capture());
        return captor.getValue();
    }

    /**
     * Calculates the canonical direct-upload cipher hash.
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
