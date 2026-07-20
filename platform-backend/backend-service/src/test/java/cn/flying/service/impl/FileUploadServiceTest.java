package cn.flying.service.impl;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.UidEncoder;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.DirectUploadCompletePartRequest;
import cn.flying.dao.vo.file.DirectUploadCompleteRequest;
import cn.flying.dao.vo.file.DirectUploadCompleteVO;
import cn.flying.dao.vo.file.DirectUploadPartRequest;
import cn.flying.dao.vo.file.DirectUploadSessionRequest;
import cn.flying.dao.vo.file.DirectUploadSessionVO;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.AbortDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CompleteDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CreateDirectMultipartUploadRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.CompleteDirectMultipartUploadResponse;
import cn.flying.platformapi.response.CreateDirectMultipartUploadResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.DirectMultipartUploadPartUrl;
import cn.flying.service.FileService;
import cn.flying.service.QuotaService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.encryption.AesGcmEncryptionStrategy;
import cn.flying.service.encryption.EncryptionStrategyFactory;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.builders.FileUploadStateTestBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FileUploadServiceImpl.
 * Verifies chunk upload workflow, state management, pause/resume, and progress tracking.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileUploadService Tests")
class FileUploadServiceTest {

    @Mock
    private FileUploadRedisStateManager redisStateManager;

    @Mock
    private EncryptionStrategyFactory encryptionStrategyFactory;

    @Mock
    private FileService fileService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private QuotaService quotaService;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock quotaLock;

    @Mock
    private RLock finalizationLock;

    @Mock
    private RLock preparedFileLock;

    @Mock
    private RLock chunkProcessingLock;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private ChunkManifestService chunkManifestService;

    @InjectMocks
    private FileUploadServiceImpl fileUploadService;

    private static MockedStatic<UidEncoder> uidEncoderMock;
    private static MockedStatic<IdUtils> idUtilsMock;

    private static final Long USER_ID = 100L;
    private static final String SUID = "encoded_uid_100";
    private static final String CLIENT_ID = "test_client_123";
    private static final String CONTENT_HASH = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String CHUNK_HASH_0 =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";
    private static final String CHUNK_HASH_1 =
            "sha256:1111111111111111111111111111111111111111111111111111111111111111";

    @BeforeAll
    static void setUpClass() {
        uidEncoderMock = mockStatic(UidEncoder.class);
        uidEncoderMock.when(() -> UidEncoder.encodeUid(anyString())).thenReturn(SUID);
        uidEncoderMock.when(() -> UidEncoder.encodeCid(anyString())).thenReturn(CLIENT_ID);
        idUtilsMock = mockStatic(IdUtils.class);
        idUtilsMock.when(() -> IdUtils.toExternalId(any(Long.class)))
                .thenAnswer(invocation -> "E" + invocation.getArgument(0));
        idUtilsMock.when(() -> IdUtils.fromExternalId(anyString())).thenReturn(9527L);
        idUtilsMock.when(IdUtils::nextEntityId).thenReturn(7001L);
    }

    @AfterAll
    static void tearDownClass() {
        uidEncoderMock.close();
        idUtilsMock.close();
    }

    @BeforeEach
    void setUp() throws InterruptedException {
        FileUploadStateTestBuilder.resetClientIdCounter();
        idUtilsMock.when(IdUtils::nextEntityId).thenReturn(7001L);
        // Skip @PostConstruct initialization
        ReflectionTestUtils.setField(fileUploadService, "eventPublisher", eventPublisher);
        // 让异步分片处理在测试中同步执行，避免线程池/时序不稳定
        ReflectionTestUtils.setField(fileUploadService, "fileProcessingExecutor", (java.util.concurrent.Executor) Runnable::run);
        when(redissonClient.getLock(startsWith("distributed:lock:upload:finalize:session:")))
                .thenReturn(finalizationLock);
        when(finalizationLock.tryLock()).thenReturn(true);
        when(finalizationLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(finalizationLock.isHeldByCurrentThread()).thenReturn(true);
        when(redissonClient.getLock(startsWith("distributed:lock:upload:finalize:prepared-file:")))
                .thenReturn(preparedFileLock);
        when(redissonClient.getLock(startsWith("distributed:lock:upload:process:chunk:")))
                .thenReturn(chunkProcessingLock);
        when(preparedFileLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(preparedFileLock.isHeldByCurrentThread()).thenReturn(true);
        when(quotaLock.tryLock(anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(redisStateManager.addPausedSession(anyString()))
                .thenReturn(FileUploadRedisStateManager.PauseTransitionResult.PAUSED);
        when(redisStateManager.executeWithSessionStateLock(anyString(), any()))
                .thenAnswer(invocation -> {
                    String sessionId = invocation.getArgument(0);
                    FileUploadState current = redisStateManager.getState(sessionId);
                    if (current == null) {
                        throw new IllegalStateException("test session missing: " + sessionId);
                    }
                    Function<FileUploadState, Object> action = invocation.getArgument(1);
                    return action.apply(current);
                });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 构造两分片 direct upload 创建请求。
     */
    private DirectUploadSessionRequest directSessionRequest() {
        DirectUploadSessionRequest request = new DirectUploadSessionRequest();
        request.setFileName("direct.pdf");
        request.setFileSize(1024L);
        request.setContentType("application/pdf");
        request.setChunkSize(512);
        request.setTotalChunks(2);
        request.setParts(List.of(
                directUploadPart(0, 512L, CHUNK_HASH_0, CHUNK_HASH_0),
                directUploadPart(1, 512L, CHUNK_HASH_1, CHUNK_HASH_1)
        ));
        return request;
    }

    /**
     * 构造 direct upload 分片声明。
     */
    private DirectUploadPartRequest directUploadPart(int index, long size, String plainHash, String cipherHash) {
        DirectUploadPartRequest part = new DirectUploadPartRequest();
        part.setIndex(index);
        part.setSize(size);
        part.setPlainHash(plainHash);
        part.setCipherHash(cipherHash);
        part.setChecksumAlgorithm("SHA-256");
        return part;
    }

    /**
     * 构造一个单分片普通上传状态，可选择是否包含完整处理证据。
     *
     * @param clientId 客户端会话ID
     * @param complete 是否写入完整 uploaded/processed/hash/key 状态
     * @return 普通上传会话状态
     */
    private FileUploadState completionState(String clientId, boolean complete) {
        FileUploadState state = new FileUploadState(
                USER_ID,
                "refresh.txt",
                1024L,
                "text/plain",
                clientId,
                1024,
                1);
        state.setTenantId(77L);
        state.setSuid(SUID);
        state.setUploadTempPath(Path.of("uploads").toAbsolutePath().normalize()
                .resolve(SUID).resolve(clientId).toString());
        state.setProcessedTempPath(Path.of("processed").toAbsolutePath().normalize()
                .resolve(SUID).resolve(clientId).toString());
        if (complete) {
            state.getUploadedChunks().add(0);
            state.getProcessedChunks().add(0);
            state.getChunkHashes().put("chunk_0", "chunk-hash-0");
            state.getKeys().put(0, new byte[]{1, 2, 3, 4});
        }
        return state;
    }

    /**
     * 验证原始分片按索引生成可信 SHA-256，并拒绝缺失分片和聚合长度漂移。
     */
    @Test
    void shouldResolveOriginalContentHashFromOrderedChunksAndValidateBoundaries() throws Exception {
        String suid = "hash-user-" + UUID.randomUUID();
        String clientId = "hash-session-" + UUID.randomUUID();
        Path sessionDirectory = Path.of("uploads").toAbsolutePath().normalize().resolve(suid).resolve(clientId);
        Files.createDirectories(sessionDirectory);
        Files.writeString(sessionDirectory.resolve("chunk_0"), "abc", StandardCharsets.UTF_8);
        Files.writeString(sessionDirectory.resolve("chunk_1"), "def", StandardCharsets.UTF_8);

        try {
            FileUploadState state = new FileUploadState(
                    USER_ID, "hash.txt", 6L, "text/plain", clientId, 3, 2);
            String expected = "sha256:bef57ec7f53a6d40beb640a780a639c83bc29ac8a9816f1fc6c5c6dcd93c4721";

            String actual = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "resolveOriginalContentHash", suid, state);
            assertEquals(expected, actual);

            state.setContentHash("  " + expected.toUpperCase() + "  ");
            String cached = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "resolveOriginalContentHash", suid, state);
            assertEquals(expected, cached);

            FileUploadState wrongSize = new FileUploadState(
                    USER_ID, "hash.txt", 7L, "text/plain", clientId, 3, 2);
            GeneralException sizeError = assertThrows(
                    GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileUploadService, "resolveOriginalContentHash", suid, wrongSize));
            assertEquals(ResultEnum.FILE_UPLOAD_ERROR, sizeError.getResultEnum());

            FileUploadState missingChunk = new FileUploadState(
                    USER_ID, "hash.txt", 1L, "text/plain", "missing-" + UUID.randomUUID(), 1, 1);
            GeneralException missingError = assertThrows(
                    GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileUploadService, "resolveOriginalContentHash", suid, missingChunk));
            assertEquals(ResultEnum.FILE_UPLOAD_ERROR, missingError.getResultEnum());
        } finally {
            ReflectionTestUtils.invokeMethod(fileUploadService, "cleanupDirectory", sessionDirectory);
        }
    }

    /**
     * 构造对象存储返回的 direct upload 预签名 URL 元数据。
     */
    private List<DirectMultipartUploadPartUrl> storageUploadPartUrls() {
        long expiresAt = Instant.now().getEpochSecond() + TimeUnit.HOURS.toSeconds(24);
        return List.of(
                new DirectMultipartUploadPartUrl(
                        0,
                        "https://storage.example/upload/0",
                        expiresAt,
                        "storage/tenant/77/chunk/" + CHUNK_HASH_0,
                        "tenant/77/staging/direct-upload/" + CLIENT_ID + "/part-0",
                        "tenant/77/" + CHUNK_HASH_0,
                        "node-a",
                        512L
                ),
                new DirectMultipartUploadPartUrl(
                        1,
                        "https://storage.example/upload/1",
                        expiresAt,
                        "storage/tenant/77/chunk/" + CHUNK_HASH_1,
                        "tenant/77/staging/direct-upload/" + CLIENT_ID + "/part-1",
                        "tenant/77/" + CHUNK_HASH_1,
                        "node-a",
                        512L
                )
        );
    }

    /**
     * 构造已持久化在 Redis 中的 direct upload 会话状态。
     */
    private FileUploadState directUploadState() {
        return directUploadState(CLIENT_ID, "direct.pdf");
    }

    /**
     * 构造指定会话和文件名的 Redis 直传计划，用于同名并发隔离测试。
     */
    private FileUploadState directUploadState(String clientId, String fileName) {
        FileUploadState state = new FileUploadState(
                USER_ID,
                fileName,
                1024L,
                "application/pdf",
                clientId,
                512,
                2
        );
        state.setTenantId(77L);
        state.setSuid(SUID);
        state.setDirectUpload(true);
        state.setDirectUploadParts(List.of(
                new FileUploadState.DirectUploadPartState(
                        0,
                        512L,
                        CHUNK_HASH_0,
                        CHUNK_HASH_0,
                        "SHA-256",
                        "https://storage.example/upload/0",
                        4_102_444_800L,
                        "storage/tenant/77/chunk/" + CHUNK_HASH_0,
                        "tenant/77/staging/direct-upload/" + clientId + "/part-0",
                        "tenant/77/" + CHUNK_HASH_0,
                        "node-a"
                ),
                new FileUploadState.DirectUploadPartState(
                        1,
                        512L,
                        CHUNK_HASH_1,
                        CHUNK_HASH_1,
                        "SHA-256",
                        "https://storage.example/upload/1",
                        4_102_444_800L,
                        "storage/tenant/77/chunk/" + CHUNK_HASH_1,
                        "tenant/77/staging/direct-upload/" + clientId + "/part-1",
                        "tenant/77/" + CHUNK_HASH_1,
                        "node-a"
                )
        ));
        return state;
    }

    /**
     * 构造前端 direct upload 完成请求。
     */
    private DirectUploadCompleteRequest directCompleteRequest() {
        DirectUploadCompletePartRequest first = new DirectUploadCompletePartRequest();
        first.setIndex(0);
        first.setETag("\"etag-0\"");
        DirectUploadCompletePartRequest second = new DirectUploadCompletePartRequest();
        second.setIndex(1);
        second.setETag("\"etag-1\"");
        DirectUploadCompleteRequest request = new DirectUploadCompleteRequest();
        request.setParts(List.of(first, second));
        return request;
    }

    /**
     * 构造与 Redis 直传计划逐字段一致的对象存储完成证据。
     */
    private List<DirectMultipartCompletedPartVO> validDirectCompletedParts() {
        return List.of(
                directCompletedPart(
                        0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 512L, "\"final-etag-0\"",
                        CHUNK_HASH_0, CHUNK_HASH_0, "SHA-256"),
                directCompletedPart(
                        1, "storage/tenant/77/chunk/" + CHUNK_HASH_1, 512L, "\"final-etag-1\"",
                        CHUNK_HASH_1, CHUNK_HASH_1, "SHA-256")
        );
    }

    /**
     * 构造一个对象存储完成分片，便于覆盖每个可信字段的失败关闭测试。
     */
    private DirectMultipartCompletedPartVO directCompletedPart(
            int index,
            String storagePath,
            long size,
            String eTag,
            String plainHash,
            String cipherHash,
            String checksumAlgorithm
    ) {
        return new DirectMultipartCompletedPartVO(
                index, storagePath, size, eTag, plainHash, cipherHash, checksumAlgorithm);
    }

    /**
     * 构造绑定稳定主键的 PREPARE 文件记录。
     */
    private File preparedDirectFile(long fileId, String fileName) {
        return new File()
                .setId(fileId)
                .setTenantId(77L)
                .setUid(USER_ID)
                .setFileName(fileName)
                .setFileSize(1024L)
                .setFileParam("{\"fileSize\":1024}")
                .setStatus(FileUploadStatus.PREPARE.getCode());
    }

    /**
     * 构造带完整链与内容摘要证据的 SUCCESS 文件记录。
     */
    private File storedDirectFile(long fileId, String fileName) {
        return preparedDirectFile(fileId, fileName)
                .setContentHash(CONTENT_HASH)
                .setFileHash("file-hash")
                .setTransactionHash("tx-1")
                .setStatus(FileUploadStatus.SUCCESS.getCode());
    }

    /**
     * 构造与文件结果一致的 active manifest。
     */
    private ChunkManifestView directManifest(long fileId) {
        return new ChunkManifestView(
                900L,
                fileId,
                1,
                "cn.flying.chunk-manifest.v1",
                "file-hash",
                "manifest-hash",
                "SHA-256",
                512L,
                2,
                1024L,
                null,
                "NONE",
                "S3",
                List.of()
        );
    }

    /**
     * 把可信 storage 完成证据写入会话，模拟 storage 已完成后的恢复入口。
     */
    private void checkpointDirectStorage(FileUploadState state) {
        state.setContentHash(CONTENT_HASH);
        state.setDirectCompletedParts(validDirectCompletedParts().stream()
                .map(part -> new FileUploadState.DirectUploadCompletedPartState(
                        part.partIndex(),
                        part.storagePath(),
                        part.size(),
                        part.eTag(),
                        part.plainHash(),
                        part.cipherHash(),
                        part.checksumAlgorithm()))
                .toList());
        state.setDirectFinalizationStage("STORAGE_COMPLETED");
    }

    /**
     * 把稳定 PREPARE 主键写入会话，模拟数据库预占位已经完成。
     */
    private void checkpointDirectPrepare(FileUploadState state, long fileId) {
        checkpointDirectStorage(state);
        state.setPreparedFileId(fileId);
        state.setPrepareStored(true);
        state.setDirectFinalizationStage("PREPARE_STORED");
    }

    @Nested
    @DisplayName("Direct Upload")
    class DirectUpload {

        @Test
        @DisplayName("should create direct upload session and persist presigned part state")
        void shouldCreateDirectUploadSession() {
            TenantContext.setTenantId(77L);
            DirectUploadSessionRequest request = directSessionRequest();
            request.getParts().getFirst().setPlainHash("SHA256:" + CHUNK_HASH_0.substring(7));
            request.getParts().getFirst().setCipherHash("SHA256:" + CHUNK_HASH_0.substring(7));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(null);
            when(fileRemoteClient.createDirectMultipartUpload(any()))
                    .thenReturn(Result.success(new CreateDirectMultipartUploadResponse(CLIENT_ID, storageUploadPartUrls())));

            DirectUploadSessionVO result = fileUploadService.startDirectUpload(USER_ID, request);

            assertNotNull(result);
            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals(2, result.getParts().size());
            assertFalse(result.isResumed());
            assertEquals("https://storage.example/upload/0", result.getParts().getFirst().getUploadUrl());

            ArgumentCaptor<CreateDirectMultipartUploadRequest> storageRequestCaptor =
                    ArgumentCaptor.forClass(CreateDirectMultipartUploadRequest.class);
            verify(fileRemoteClient).createDirectMultipartUpload(storageRequestCaptor.capture());
            assertEquals(CLIENT_ID, storageRequestCaptor.getValue().sessionId());
            assertEquals(CHUNK_HASH_0, storageRequestCaptor.getValue().parts().getFirst().objectName());

            verify(redisStateManager).saveNewState(
                    argThat(state -> state.isDirectUpload()
                            && state.getDirectUploadParts().size() == 2
                            && Objects.equals(
                            state.getDirectUploadParts().getFirst().getPlainHash(), CHUNK_HASH_0)
                            && Objects.equals(
                            state.getDirectUploadParts().getFirst().getCipherHash(), CHUNK_HASH_0)
                            && Objects.equals(
                            state.getDirectUploadParts().getFirst().getStagingObjectName(),
                            "tenant/77/staging/direct-upload/" + CLIENT_ID + "/part-0")),
                    eq(SUID)
            );
            verify(redisStateManager).updateState(argThat(FileUploadState::isDirectUpload));
            verify(quotaService).checkUploadQuota(77L, USER_ID, 1024L);
        }

        /**
         * 验证相同 clientId 的创建判定在会话锁内重读，后续调用只恢复首次持久化的存储计划。
         */
        @Test
        @DisplayName("should serialize same-client direct creation and resume one persisted plan")
        void shouldSerializeSameClientDirectCreationAndResumeOnePlan() {
            TenantContext.setTenantId(77L);
            DirectUploadSessionRequest request = directSessionRequest();
            request.setClientId(CLIENT_ID);
            AtomicReference<FileUploadState> persistedState = new AtomicReference<>();
            when(redisStateManager.getState(CLIENT_ID)).thenAnswer(invocation -> persistedState.get());
            doAnswer(invocation -> {
                FileUploadState state = invocation.getArgument(0);
                assertTrue(persistedState.compareAndSet(null, state));
                return null;
            }).when(redisStateManager).saveNewState(any(FileUploadState.class), eq(SUID));
            when(fileRemoteClient.createDirectMultipartUpload(any()))
                    .thenReturn(Result.success(new CreateDirectMultipartUploadResponse(
                            CLIENT_ID, storageUploadPartUrls())));

            DirectUploadSessionVO created = fileUploadService.startDirectUpload(USER_ID, request);
            DirectUploadSessionVO resumed = fileUploadService.startDirectUpload(USER_ID, request);

            assertFalse(created.isResumed());
            assertTrue(resumed.isResumed());
            assertEquals(created.getClientId(), resumed.getClientId());
            assertEquals(created.getParts().size(), resumed.getParts().size());
            assertEquals(created.getParts().getFirst().getUploadUrl(),
                    resumed.getParts().getFirst().getUploadUrl());
            assertEquals(created.getParts().getFirst().getStoragePath(),
                    resumed.getParts().getFirst().getStoragePath());
            verify(fileRemoteClient).createDirectMultipartUpload(any());
            verify(redisStateManager).saveNewState(any(FileUploadState.class), eq(SUID));

            InOrder creationOrder = inOrder(finalizationLock, redisStateManager, fileRemoteClient);
            creationOrder.verify(finalizationLock).tryLock();
            creationOrder.verify(redisStateManager).getState(CLIENT_ID);
            creationOrder.verify(fileRemoteClient).createDirectMultipartUpload(any());
            creationOrder.verify(redisStateManager).saveNewState(any(FileUploadState.class), eq(SUID));
            creationOrder.verify(finalizationLock).unlock();
            creationOrder.verify(finalizationLock).tryLock();
            creationOrder.verify(redisStateManager).getState(CLIENT_ID);
            creationOrder.verify(redisStateManager).updateLastActivityTime(CLIENT_ID);
            creationOrder.verify(finalizationLock).unlock();
        }

        /**
         * 验证人工对账直传会话不能被 startDirectUpload 当作普通续传刷新活动时间和 TTL。
         */
        @Test
        void manualReconciliationSessionShouldRejectDirectResumeWithoutTtlRefresh() {
            DirectUploadSessionRequest request = directSessionRequest();
            request.setClientId(CLIENT_ID);
            FileUploadState state = directUploadState();
            state.setStatus("finalization_manual_reconciliation_required");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.startDirectUpload(USER_ID, request));

            assertEquals(ResultEnum.FILE_RECORD_ERROR, error.getResultEnum());
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(redisStateManager, never()).updateState(any());
            verify(fileRemoteClient, never()).createDirectMultipartUpload(any());
            verifyNoInteractions(quotaService);
            verify(finalizationLock).unlock();
        }

        /**
         * 验证相同 clientId 的创建锁竞争失败映射为可重试错误，且不会生成第二套存储计划。
         */
        @Test
        @DisplayName("should fail direct creation lock contention without storage or Redis writes")
        void shouldFailDirectCreationLockContentionWithoutSideEffects() {
            DirectUploadSessionRequest request = directSessionRequest();
            request.setClientId(CLIENT_ID);
            when(finalizationLock.tryLock()).thenReturn(false);

            RetryableException exception = assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.startDirectUpload(USER_ID, request));

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, exception.getResultEnum());
            verify(redisStateManager, never()).getState(CLIENT_ID);
            verify(fileRemoteClient, never()).createDirectMultipartUpload(any());
            verify(redisStateManager, never()).saveNewState(any(), anyString());
            verify(finalizationLock, never()).unlock();
        }

        /**
         * 验证 hash 和 checksum 的有界规范在 Service 层同样生效，失败时不请求 storage。
         */
        @Test
        @DisplayName("should reject malformed or oversized direct hashes and checksum before storage")
        void shouldRejectMalformedDirectHashesAndChecksumBeforeStorage() {
            List<DirectUploadSessionRequest> invalidRequests = new ArrayList<>();

            DirectUploadSessionRequest invalidPlainHash = directSessionRequest();
            invalidPlainHash.getParts().getFirst().setPlainHash("sha256:invalid");
            invalidPlainHash.getParts().getFirst().setCipherHash("sha256:invalid");
            invalidRequests.add(invalidPlainHash);

            DirectUploadSessionRequest oversizedCipherHash = directSessionRequest();
            String oversizedHash = "sha256:" + "a".repeat(65);
            oversizedCipherHash.getParts().getFirst().setPlainHash(oversizedHash);
            oversizedCipherHash.getParts().getFirst().setCipherHash(oversizedHash);
            invalidRequests.add(oversizedCipherHash);

            DirectUploadSessionRequest invalidChecksum = directSessionRequest();
            invalidChecksum.getParts().getFirst().setChecksumAlgorithm("MD5");
            invalidRequests.add(invalidChecksum);

            DirectUploadSessionRequest oversizedChecksum = directSessionRequest();
            oversizedChecksum.getParts().getFirst().setChecksumAlgorithm("S".repeat(128));
            invalidRequests.add(oversizedChecksum);

            for (DirectUploadSessionRequest invalidRequest : invalidRequests) {
                GeneralException exception = assertThrows(
                        GeneralException.class,
                        () -> fileUploadService.startDirectUpload(USER_ID, invalidRequest));
                assertEquals(ResultEnum.PARAM_ERROR, exception.getResultEnum());
            }

            verify(fileRemoteClient, never()).createDirectMultipartUpload(any());
            verify(redisStateManager, never()).saveNewState(any(), anyString());
            verify(redisStateManager, never()).updateState(any());
        }

        /**
         * 验证绕过 Controller 时缺失的 wrapper 数值在任何会话锁或 storage 副作用前失败关闭。
         */
        @Test
        void shouldRejectMissingDirectSessionAndPartNumbersAtServiceBoundary() {
            DirectUploadSessionRequest missingFileSize = directSessionRequest();
            missingFileSize.setFileSize(null);
            DirectUploadSessionRequest missingChunkSize = directSessionRequest();
            missingChunkSize.setChunkSize(null);
            DirectUploadSessionRequest missingTotalChunks = directSessionRequest();
            missingTotalChunks.setTotalChunks(null);
            DirectUploadSessionRequest missingPartIndex = directSessionRequest();
            missingPartIndex.getParts().getFirst().setIndex(null);
            DirectUploadSessionRequest missingPartSize = directSessionRequest();
            missingPartSize.getParts().getFirst().setSize(null);

            for (DirectUploadSessionRequest invalid : List.of(
                    missingFileSize,
                    missingChunkSize,
                    missingTotalChunks,
                    missingPartIndex,
                    missingPartSize)) {
                assertThrows(GeneralException.class, () ->
                        fileUploadService.startDirectUpload(USER_ID, invalid));
            }

            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
            verify(redissonClient, never()).getLock(startsWith(
                    "distributed:lock:upload:finalize:session:"));
        }

        /**
         * 验证 storage 创建响应的身份、索引、路径、URL、过期时间与分片计划必须整体可信后才能写 Redis。
         */
        @Test
        @DisplayName("should reject malformed storage create responses without persisting session state")
        void shouldRejectMalformedStorageCreateResponsesWithoutPersistingState() {
            TenantContext.setTenantId(77L);
            DirectMultipartUploadPartUrl first = storageUploadPartUrls().getFirst();
            DirectMultipartUploadPartUrl second = storageUploadPartUrls().get(1);
            long validExpiry = Instant.now().getEpochSecond() + TimeUnit.HOURS.toSeconds(24);
            List<CreateDirectMultipartUploadResponse> malformedResponses = new ArrayList<>();
            malformedResponses.add(null);
            malformedResponses.add(new CreateDirectMultipartUploadResponse(
                    "wrong-session", List.of(first, second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, null));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(first)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(first, first)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, first.uploadUrl(), validExpiry, first.storagePath(), first.stagingObjectName(),
                            first.finalObjectName(), first.nodeName(), 511L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, first.uploadUrl(), validExpiry, "storage/tenant/8/chunk/" + CHUNK_HASH_0,
                            first.stagingObjectName(), first.finalObjectName(), first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, first.uploadUrl(), validExpiry, first.storagePath(),
                            "tenant/77/staging/direct-upload/other-session/part-0",
                            first.finalObjectName(), first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, first.uploadUrl(), validExpiry, first.storagePath(), first.stagingObjectName(),
                            "tenant/77/" + CHUNK_HASH_1, first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, "ftp://storage.example/upload/0", validExpiry, first.storagePath(),
                            first.stagingObjectName(), first.finalObjectName(), first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, "https://user:secret@storage.example/upload/0", validExpiry, first.storagePath(),
                            first.stagingObjectName(), first.finalObjectName(), first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, "https://storage.example/upload/0#fragment", validExpiry, first.storagePath(),
                            first.stagingObjectName(), first.finalObjectName(), first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, first.uploadUrl(), Instant.now().getEpochSecond() - 1L, first.storagePath(),
                            first.stagingObjectName(), first.finalObjectName(), first.nodeName(), 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    new DirectMultipartUploadPartUrl(
                            0, first.uploadUrl(), validExpiry, first.storagePath(), first.stagingObjectName(),
                            first.finalObjectName(), "../node", 512L),
                    second)));
            malformedResponses.add(new CreateDirectMultipartUploadResponse(CLIENT_ID, List.of(
                    first,
                    new DirectMultipartUploadPartUrl(
                            1, first.uploadUrl(), validExpiry, second.storagePath(), second.stagingObjectName(),
                            second.finalObjectName(), second.nodeName(), 512L))));
            AtomicInteger responseIndex = new AtomicInteger();
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(null);
            when(fileRemoteClient.createDirectMultipartUpload(any())).thenAnswer(invocation ->
                    Result.success(malformedResponses.get(responseIndex.getAndIncrement())));

            for (int attempt = 0; attempt < malformedResponses.size(); attempt++) {
                GeneralException exception = assertThrows(
                        GeneralException.class,
                        () -> fileUploadService.startDirectUpload(USER_ID, directSessionRequest()));
                assertEquals(ResultEnum.FILE_SERVICE_ERROR, exception.getResultEnum());
            }

            verify(redisStateManager, never()).saveNewState(any(), anyString());
            verify(redisStateManager, never()).updateState(any());
            verify(redisStateManager, never()).updateLastActivityTime(anyString());
            verify(fileRemoteClient, times(malformedResponses.size())).createDirectMultipartUpload(any());
        }

        @Test
        @DisplayName("should resume existing direct upload only when metadata matches")
        void shouldResumeExistingDirectUploadOnlyWhenMetadataMatches() {
            DirectUploadSessionRequest request = directSessionRequest();
            request.setClientId(CLIENT_ID);
            FileUploadState state = directUploadState();
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            DirectUploadSessionVO result = fileUploadService.startDirectUpload(USER_ID, request);

            assertTrue(result.isResumed());
            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals(2, result.getParts().size());
            verify(redisStateManager).updateLastActivityTime(CLIENT_ID);
            verify(fileRemoteClient, never()).createDirectMultipartUpload(any());
        }

        @Test
        @DisplayName("should reject direct upload resume when part metadata differs")
        void shouldRejectDirectUploadResumeWhenPartMetadataDiffers() {
            DirectUploadSessionRequest request = directSessionRequest();
            request.setClientId(CLIENT_ID);
            request.setParts(List.of(
                    directUploadPart(
                            0,
                            512L,
                            "sha256:2222222222222222222222222222222222222222222222222222222222222222",
                            "sha256:2222222222222222222222222222222222222222222222222222222222222222"),
                    directUploadPart(1, 512L, CHUNK_HASH_1, CHUNK_HASH_1)
            ));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(directUploadState());

            GeneralException exception = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.startDirectUpload(USER_ID, request)
            );

            assertEquals(ResultEnum.PARAM_ERROR, exception.getResultEnum());
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(fileRemoteClient, never()).createDirectMultipartUpload(any());
        }

        @Test
        @DisplayName("should complete direct upload, register file, and persist manifest")
        void shouldCompleteDirectUploadAndPersistManifest() throws Exception {
            long fileId = 7001L;
            FileUploadState state = directUploadState();
            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView manifest = directManifest(fileId);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77")).thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileRemoteClient.completeDirectMultipartUpload(any()))
                    .thenReturn(Result.success(new CompleteDirectMultipartUploadResponse(
                            CLIENT_ID,
                            CONTENT_HASH,
                            validDirectCompletedParts())));
            when(fileService.prepareStoreFileWithStableId(
                    USER_ID, null, fileId, "direct.pdf", 1024L)).thenReturn(preparedFile);
            when(fileService.getById(fileId)).thenReturn(preparedFile);
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID)))
                    .thenReturn(new StoreFileResponse("tx-1", "file-hash"));
            when(fileService.persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(), any(), eq(CLIENT_ID)))
                    .thenReturn(storedFile);
            when(chunkManifestService.calculateManifestHash(any())).thenReturn("manifest-hash");
            when(chunkManifestService.findActiveManifest(USER_ID, fileId)).thenReturn(Optional.empty());
            when(chunkManifestService.saveManifest(eq(USER_ID), eq(fileId), any()))
                    .thenReturn(manifest);

            DirectUploadCompleteVO result = fileUploadService.completeDirectUpload(
                    USER_ID,
                    CLIENT_ID,
                    directCompleteRequest()
            );

            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals("E7001", result.getFileId());
            assertEquals("file-hash", result.getFileHash());
            assertEquals("tx-1", result.getTransactionHash());
            assertEquals("manifest-hash", result.getManifestHash());
            assertEquals("completed", result.getStatus());

            ArgumentCaptor<CompleteDirectMultipartUploadRequest> completeRequestCaptor =
                    ArgumentCaptor.forClass(CompleteDirectMultipartUploadRequest.class);
            verify(fileRemoteClient).completeDirectMultipartUpload(completeRequestCaptor.capture());
            assertEquals(CLIENT_ID, completeRequestCaptor.getValue().sessionId());
            assertEquals("\"etag-0\"", completeRequestCaptor.getValue().parts().getFirst().eTag());
            assertEquals(
                    "tenant/77/staging/direct-upload/" + CLIENT_ID + "/part-0",
                    completeRequestCaptor.getValue().parts().getFirst().stagingObjectName());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<DirectMultipartCompletedPartVO>> storedPartsCaptor = ArgumentCaptor.forClass(List.class);
            verify(fileService).attestDirectUploadedFile(
                    eq(USER_ID),
                    eq(fileId),
                    eq("direct.pdf"),
                    storedPartsCaptor.capture(),
                    argThat(json -> json.contains("DIRECT_MULTIPART") && json.contains(CONTENT_HASH)),
                    eq(CLIENT_ID)
            );
            assertEquals(2, storedPartsCaptor.getValue().size());
            assertEquals(CHUNK_HASH_0, storedPartsCaptor.getValue().get(0).cipherHash());
            assertEquals(
                    "storage/tenant/77/chunk/" + CHUNK_HASH_0,
                    storedPartsCaptor.getValue().get(0).storagePath());
            assertEquals(CHUNK_HASH_1, storedPartsCaptor.getValue().get(1).cipherHash());
            assertEquals(
                    "storage/tenant/77/chunk/" + CHUNK_HASH_1,
                    storedPartsCaptor.getValue().get(1).storagePath());
            assertEquals("\"final-etag-0\"", storedPartsCaptor.getValue().getFirst().eTag());
            verify(fileService).persistDirectUploadedFile(
                    eq(USER_ID),
                    eq(fileId),
                    eq("direct.pdf"),
                    eq(1024L),
                    argThat(json -> json.contains("DIRECT_MULTIPART") && json.contains(CONTENT_HASH)),
                    eq(new StoreFileResponse("tx-1", "file-hash")),
                    eq(CLIENT_ID)
            );
            verify(fileService, never()).storeDirectUploadedFile(
                    anyLong(), any(), anyString(), anyLong(), anyList(), anyString());

            ArgumentCaptor<ChunkManifestDraft> manifestCaptor = ArgumentCaptor.forClass(ChunkManifestDraft.class);
            verify(chunkManifestService).saveManifest(eq(USER_ID), eq(fileId), manifestCaptor.capture());
            assertEquals("file-hash", manifestCaptor.getValue().fileHash());
            assertEquals(2, manifestCaptor.getValue().chunks().size());
            assertEquals("S3", manifestCaptor.getValue().storageBackend());
            assertEquals("\"final-etag-0\"", manifestCaptor.getValue().chunks().getFirst().etag());

            assertTrue(state.isPrepareStored());
            assertEquals(fileId, state.getPreparedFileId());
            assertEquals(CONTENT_HASH, state.getContentHash());
            assertEquals(fileId, state.getDirectFileId());
            assertEquals("file-hash", state.getDirectFileHash());
            assertEquals("tx-1", state.getDirectTransactionHash());
            assertEquals("manifest-hash", state.getDirectManifestHash());
            assertEquals("MANIFEST_STORED", state.getDirectFinalizationStage());
            assertEquals("\"final-etag-0\"", state.getDirectCompletedParts().getFirst().getETag());
            verify(redisStateManager, atLeastOnce()).updateState(state);
            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
            verify(quotaService, times(2)).checkUploadQuota(77L, USER_ID, 1024L);
            verify(preparedFileLock).tryLock(5L, TimeUnit.SECONDS);
            verify(preparedFileLock).unlock();

            InOrder lockOrder = inOrder(finalizationLock, quotaLock, preparedFileLock, fileService);
            lockOrder.verify(finalizationLock).tryLock();
            lockOrder.verify(quotaLock).tryLock(5L, TimeUnit.SECONDS);
            lockOrder.verify(preparedFileLock).tryLock(5L, TimeUnit.SECONDS);
            lockOrder.verify(fileService).prepareStoreFileWithStableId(
                    USER_ID, null, fileId, "direct.pdf", 1024L);
        }

        /**
         * 验证同一用户的同名并发会话各自持有稳定 PREPARE 主键，不会按文件名串单。
         */
        @Test
        @DisplayName("should isolate same-name direct sessions with distinct stable prepare IDs")
        void shouldIsolateSameNameDirectSessionsWithStablePrepareIds() {
            String firstClientId = "same-name-a";
            String secondClientId = "same-name-b";
            FileUploadState firstState = directUploadState(firstClientId, "same-name.pdf");
            FileUploadState secondState = directUploadState(secondClientId, "same-name.pdf");
            idUtilsMock.when(IdUtils::nextEntityId).thenReturn(8101L, 8102L);
            when(redisStateManager.getState(firstClientId)).thenReturn(firstState);
            when(redisStateManager.getState(secondClientId)).thenReturn(secondState);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77")).thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.prepareStoreFileWithStableId(
                    eq(USER_ID), isNull(), anyLong(), eq("same-name.pdf"), eq(1024L)))
                    .thenAnswer(invocation -> preparedDirectFile(invocation.getArgument(2), "same-name.pdf"));

            FileUploadState firstPrepared = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "reserveQuotaAndPrepareStoreFile", USER_ID, firstState);
            FileUploadState secondPrepared = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "reserveQuotaAndPrepareStoreFile", USER_ID, secondState);

            assertNotNull(firstPrepared);
            assertNotNull(secondPrepared);
            assertEquals(8101L, firstPrepared.getPreparedFileId());
            assertEquals(8102L, secondPrepared.getPreparedFileId());
            assertNotEquals(firstPrepared.getPreparedFileId(), secondPrepared.getPreparedFileId());
            verify(fileService).prepareStoreFileWithStableId(
                    USER_ID, null, 8101L, "same-name.pdf", 1024L);
            verify(fileService).prepareStoreFileWithStableId(
                    USER_ID, null, 8102L, "same-name.pdf", 1024L);
            verify(fileService, never()).prepareStoreFile(
                    anyLong(), any(), anyString(), anyLong());
        }

        /**
         * 验证首次链调用响应丢失后保留 CHAIN_ATTESTING，后续 complete 只能阻塞对账而不能重放。
         */
        @Test
        @DisplayName("should never replay chain submission after an ambiguous first failure")
        void shouldNeverReplayChainSubmissionAfterAmbiguousFailure() {
            long fileId = 8201L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77")).thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedFile);
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID)))
                    .thenThrow(new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "响应丢失"));
            when(fileService.getFinalizationRecoveryPhase(USER_ID, fileId))
                    .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING);
            doAnswer(invocation -> {
                state.setStatus("finalization_manual_reconciliation_required");
                return null;
            }).when(redisStateManager).retainManualReconciliationState(
                    same(state), eq("finalization_manual_reconciliation_required"), anyLong());

            GeneralException firstFailure = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, directCompleteRequest()));
            GeneralException secondFailure = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, directCompleteRequest()));

            assertEquals(ResultEnum.BLOCKCHAIN_ERROR, firstFailure.getResultEnum());
            assertEquals(ResultEnum.FILE_RECORD_ERROR, secondFailure.getResultEnum());
            assertEquals("PREPARE_STORED", state.getDirectFinalizationStage());
            // durable ATTESTING 结果不确定后立即转人工，第二次请求不得再进入 FileService。
            verify(fileService, times(1)).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID));
            verify(fileService, never()).persistDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
            verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
        }

        /**
         * 验证旧 Redis CHAIN_ATTESTING 但 durable phase 仍为 NONE 时不会误转人工，重试可安全完成同一会话。
         */
        @Test
        void legacyRedisChainAttestingWithDurableNoneShouldRetryBeforeChainSafely() {
            long fileId = 8202L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            state.setDirectFinalizationStage("CHAIN_ATTESTING");
            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView manifest = directManifest(fileId);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedFile);
            when(fileService.getFinalizationRecoveryPhase(USER_ID, fileId))
                    .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID)))
                    .thenThrow(new IllegalStateException("process stopped before durable claim"))
                    .thenReturn(new StoreFileResponse("tx-1", "file-hash"));
            when(fileService.persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID)))
                    .thenReturn(storedFile);
            when(chunkManifestService.calculateManifestHash(any())).thenReturn("manifest-hash");
            when(chunkManifestService.findActiveManifest(USER_ID, fileId)).thenReturn(Optional.empty());
            when(chunkManifestService.saveManifest(eq(USER_ID), eq(fileId), any())).thenReturn(manifest);

            RetryableException firstFailure = assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeDirectUpload(
                            USER_ID, CLIENT_ID, directCompleteRequest()));
            DirectUploadCompleteVO recovered = fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, directCompleteRequest());

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, firstFailure.getResultEnum());
            assertEquals("completed", recovered.getStatus());
            assertEquals("MANIFEST_STORED", state.getDirectFinalizationStage());
            verify(fileService, times(2)).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID));
            verify(fileService, times(1)).persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID));
            verify(chunkManifestService, times(1)).saveManifest(eq(USER_ID), eq(fileId), any());
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
            verify(redisStateManager, never()).retainManualReconciliationState(
                    any(), anyString(), anyLong());
        }

        /**
         * 验证早期阶段即使被注入成对 tx/fileHash 也不能绕过 CHAIN_ATTESTED 状态机边界。
         */
        @Test
        @DisplayName("should reject forged chain hashes attached to an early finalization stage")
        void shouldRejectForgedChainHashesInEarlyStage() {
            long fileId = 8251L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            state.setDirectFileHash("forged-file-hash");
            state.setDirectTransactionHash("forged-transaction-hash");
            state.setDirectFinalizationStage("PREPARE_STORED");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77")).thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedDirectFile(fileId, "direct.pdf"));

            GeneralException exception = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, directCompleteRequest()));

            assertEquals(ResultEnum.FILE_RECORD_ERROR, exception.getResultEnum());
            verify(fileService, never()).attestDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyString(), anyString());
            verify(fileService, never()).persistDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
            verifyNoInteractions(chunkManifestService);
        }

        /**
         * 验证直传 DB SUCCESS 已提交但 persist 方法退出阶段失败时，重试只恢复检查点并继续 manifest/completed。
         */
        @Test
        void shouldRecoverDirectPostCommitPersistFailureWithoutRepeatingAttestOrPersist() {
            long fileId = 8271L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView manifest = directManifest(fileId);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedFile, storedFile);
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID)))
                    .thenReturn(new StoreFileResponse("tx-1", "file-hash"));
            when(fileService.persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID)))
                    .thenThrow(new IllegalStateException("post-commit response lost"));
            when(chunkManifestService.calculateManifestHash(any())).thenReturn("manifest-hash");
            when(chunkManifestService.findActiveManifest(USER_ID, fileId)).thenReturn(Optional.empty());
            when(chunkManifestService.saveManifest(eq(USER_ID), eq(fileId), any())).thenReturn(manifest);

            assertThrows(
                    IllegalStateException.class,
                    () -> fileUploadService.completeDirectUpload(
                            USER_ID, CLIENT_ID, directCompleteRequest()));
            DirectUploadCompleteVO recovered = fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, directCompleteRequest());

            assertEquals("E8271", recovered.getFileId());
            assertEquals("file-hash", recovered.getFileHash());
            assertEquals("tx-1", recovered.getTransactionHash());
            assertEquals("manifest-hash", recovered.getManifestHash());
            assertEquals("completed", recovered.getStatus());
            verify(fileService, times(1)).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID));
            verify(fileService, times(1)).persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID));
            verify(chunkManifestService, times(1)).saveManifest(eq(USER_ID), eq(fileId), any());
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
        }

        /**
         * 验证链结果和 DB SUCCESS 已提交但 manifest 写入失败时，重试从检查点继续且不再上链。
         */
        @Test
        @DisplayName("should resume after manifest failure from chain-attested checkpoint without re-chain")
        void shouldResumeManifestFailureWithoutReattestingChain() {
            long fileId = 8301L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            state.setDirectFileHash("file-hash");
            state.setDirectTransactionHash("tx-1");
            state.setDirectFinalizationStage("CHAIN_ATTESTED");
            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView manifest = directManifest(fileId);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77")).thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedFile, storedFile);
            when(fileService.persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID)))
                    .thenReturn(storedFile);
            when(chunkManifestService.calculateManifestHash(any())).thenReturn("manifest-hash");
            when(chunkManifestService.findActiveManifest(USER_ID, fileId))
                    .thenReturn(Optional.empty(), Optional.of(manifest));
            when(chunkManifestService.saveManifest(eq(USER_ID), eq(fileId), any()))
                    .thenThrow(new GeneralException(ResultEnum.FILE_RECORD_ERROR, "manifest 暂时不可用"));

            assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, directCompleteRequest()));
            DirectUploadCompleteVO recovered = fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, directCompleteRequest());

            assertEquals("E8301", recovered.getFileId());
            assertEquals("manifest-hash", recovered.getManifestHash());
            assertEquals("completed", recovered.getStatus());
            verify(fileService, never()).attestDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyString(), anyString());
            verify(fileService, times(1)).persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID));
            verify(chunkManifestService, times(1)).saveManifest(eq(USER_ID), eq(fileId), any());
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
        }

        /**
         * 验证相同内容摘要但不同分片拓扑共享一个文件终态，loser 只能校验并拒绝既有 manifest。
         */
        @Test
        void sameContentWithDifferentTopologyShouldNotRechainOrOverwriteManifest() {
            long fileId = 8351L;
            String firstClientId = "topology-a";
            String secondClientId = "topology-b";
            FileUploadState firstState = directUploadState(firstClientId, "direct.pdf");
            checkpointDirectPrepare(firstState, fileId);

            FileUploadState secondState = new FileUploadState(
                    USER_ID, "direct.pdf", 1024L, "application/pdf", secondClientId, 1024, 1);
            secondState.setTenantId(77L);
            secondState.setSuid(SUID);
            secondState.setDirectUpload(true);
            secondState.setDirectUploadParts(List.of(new FileUploadState.DirectUploadPartState(
                    0, 1024L, CONTENT_HASH, CONTENT_HASH, "SHA-256",
                    "https://storage.example/upload/0", 4_102_444_800L,
                    "storage/tenant/77/chunk/" + CONTENT_HASH,
                    "tenant/77/staging/direct-upload/" + secondClientId + "/part-0",
                    "tenant/77/" + CONTENT_HASH, "node-a")));
            secondState.setContentHash(CONTENT_HASH);
            secondState.setDirectCompletedParts(List.of(
                    new FileUploadState.DirectUploadCompletedPartState(
                            0, "storage/tenant/77/chunk/" + CONTENT_HASH, 1024L,
                            "\"final-etag-b\"", CONTENT_HASH, CONTENT_HASH, "SHA-256")));
            secondState.setPreparedFileId(fileId);
            secondState.setPrepareStored(true);
            secondState.setDirectFinalizationStage("PREPARE_STORED");

            DirectUploadCompletePartRequest secondPart = new DirectUploadCompletePartRequest();
            secondPart.setIndex(0);
            secondPart.setETag("\"etag-b\"");
            DirectUploadCompleteRequest secondRequest = new DirectUploadCompleteRequest();
            secondRequest.setParts(List.of(secondPart));

            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView firstManifest = new ChunkManifestView(
                    901L, fileId, 1, "cn.flying.chunk-manifest.v1", "file-hash",
                    "manifest-a", "SHA-256", 512L, 2, 1024L,
                    null, "NONE", "S3", List.of());
            when(redisStateManager.getState(firstClientId)).thenReturn(firstState);
            when(redisStateManager.getState(secondClientId)).thenReturn(secondState);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedFile, storedFile);
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(firstClientId)))
                    .thenReturn(new StoreFileResponse("tx-1", "file-hash"));
            when(fileService.persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(), any(), eq(firstClientId)))
                    .thenReturn(storedFile);
            when(chunkManifestService.calculateManifestHash(any())).thenAnswer(invocation -> {
                ChunkManifestDraft draft = invocation.getArgument(0);
                return draft.chunks().size() == 2 ? "manifest-a" : "manifest-b";
            });
            when(chunkManifestService.findActiveManifest(USER_ID, fileId))
                    .thenReturn(Optional.empty(), Optional.of(firstManifest));
            when(chunkManifestService.saveManifest(eq(USER_ID), eq(fileId), any()))
                    .thenReturn(firstManifest);

            DirectUploadCompleteVO first = fileUploadService.completeDirectUpload(
                    USER_ID, firstClientId, directCompleteRequest());
            GeneralException second = assertThrows(GeneralException.class, () ->
                    fileUploadService.completeDirectUpload(USER_ID, secondClientId, secondRequest));

            assertEquals("manifest-a", first.getManifestHash());
            assertEquals(ResultEnum.FILE_RECORD_ERROR, second.getResultEnum());
            verify(fileService, times(1)).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(firstClientId));
            verify(fileService, never()).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(secondClientId));
            verify(chunkManifestService, times(1)).saveManifest(eq(USER_ID), eq(fileId), any());
        }

        @Test
        @DisplayName("should reject a concurrent direct upload finalizer before side effects")
        void shouldRejectConcurrentDirectUploadFinalizerBeforeSideEffects() {
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(directUploadState());
            when(finalizationLock.tryLock()).thenReturn(false);

            assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeDirectUpload(
                            USER_ID,
                            CLIENT_ID,
                            directCompleteRequest()));

            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
            verify(finalizationLock, never()).unlock();
        }

        /**
         * 验证三类 watchdog 锁都直接尝试 unlock，释放故障不会覆盖主流程原始业务异常。
         */
        @Test
        void shouldReleaseAllWatchdogLocksWithoutOwnershipProbeAndPreserveBusinessFailure() {
            FileUploadState state = directUploadState();
            RLock preparedRelease = mock(RLock.class);
            RLock quotaRelease = mock(RLock.class);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            doThrow(new IllegalStateException("session unlock unavailable"))
                    .when(finalizationLock).unlock();
            doThrow(new IllegalMonitorStateException("prepared lock expired"))
                    .when(preparedRelease).unlock();
            doThrow(new IllegalStateException("quota unlock unavailable"))
                    .when(quotaRelease).unlock();

            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileUploadService, "releasePreparedFileFinalizationLock", preparedRelease, 7001L));
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileUploadService, "releaseQuotaCompletionLock", quotaRelease, 77L));
            GeneralException exception = assertThrows(GeneralException.class, () ->
                    fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, null));

            assertEquals(ResultEnum.PARAM_IS_INVALID, exception.getResultEnum());
            verify(finalizationLock, times(1)).unlock();
            verify(preparedRelease, times(1)).unlock();
            verify(quotaRelease, times(1)).unlock();
            verify(finalizationLock, never()).isHeldByCurrentThread();
            verify(preparedRelease, never()).isHeldByCurrentThread();
            verify(quotaRelease, never()).isHeldByCurrentThread();
        }

        /**
         * 验证稳定 PREPARE 文件锁竞争在任何链、DB SUCCESS 或 manifest 副作用前失败。
         */
        @Test
        void shouldRejectBusyPreparedFileFinalizerBeforeIrreversibleEffects() throws Exception {
            long fileId = 8401L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(preparedFileLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

            RetryableException exception = assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeDirectUpload(
                            USER_ID, CLIENT_ID, directCompleteRequest()));

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, exception.getResultEnum());
            verifyNoInteractions(fileService, chunkManifestService, fileRemoteClient);
            verify(preparedFileLock, never()).unlock();
        }

        /**
         * 验证等待 PREPARE 文件锁被中断时恢复线程中断标志并返回可重试错误。
         */
        @Test
        void shouldRestoreInterruptWhenPreparedFileLockWaitIsInterrupted() throws Exception {
            long fileId = 8402L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(preparedFileLock.tryLock(5L, TimeUnit.SECONDS))
                    .thenThrow(new InterruptedException("interrupted"));

            try {
                RetryableException exception = assertThrows(
                        RetryableException.class,
                        () -> fileUploadService.completeDirectUpload(
                                USER_ID, CLIENT_ID, directCompleteRequest()));
                assertEquals(ResultEnum.SERVICE_UNAVAILABLE, exception.getResultEnum());
                assertTrue(Thread.currentThread().isInterrupted());
                verifyNoInteractions(fileService, chunkManifestService, fileRemoteClient);
            } finally {
                Thread.interrupted();
            }
        }

        /**
         * 验证等待 finalizer 锁期间已完成的直传会话直接返回持久化结果。
         */
        @Test
        @DisplayName("should return completed direct upload after acquiring finalizer lock")
        void shouldReturnCompletedDirectUploadAfterAcquiringFinalizerLock() {
            FileUploadState initial = directUploadState();
            FileUploadState completed = directUploadState();
            completed.setStatus("completed");
            completed.setDirectFileId(42L);
            completed.setDirectFileHash("file-hash");
            completed.setDirectTransactionHash("tx-42");
            completed.setDirectManifestHash("manifest-42");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(initial, completed);

            DirectUploadCompleteVO result = fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, null);

            assertEquals("E42", result.getFileId());
            assertEquals("file-hash", result.getFileHash());
            assertEquals("tx-42", result.getTransactionHash());
            assertEquals("manifest-42", result.getManifestHash());
            assertEquals("completed", result.getStatus());
            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
            verify(finalizationLock).unlock();
            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
        }

        /**
         * 验证 completed 主状态后的终态 TTL/索引收敛故障可重试，且不会重放存储、链或 manifest 副作用。
         */
        @Test
        void completedDirectRetryShouldConvergeWithoutReplayingExternalSideEffects() {
            FileUploadState completed = directUploadState();
            completed.setStatus("completed");
            completed.setDirectFileId(42L);
            completed.setDirectFileHash("file-hash");
            completed.setDirectTransactionHash("tx-42");
            completed.setDirectManifestHash("manifest-42");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(completed);
            doThrow(new IllegalStateException("auxiliary ttl unavailable"))
                    .doNothing()
                    .when(redisStateManager)
                    .markCompleted(CLIENT_ID, SUID, 300);

            RetryableException firstFailure = assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, null));
            DirectUploadCompleteVO recovered = fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, null);

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, firstFailure.getResultEnum());
            assertEquals("completed", recovered.getStatus());
            assertEquals("E42", recovered.getFileId());
            verify(redisStateManager, times(2)).markCompleted(CLIENT_ID, SUID, 300);
            verifyNoInteractions(
                    fileRemoteClient, fileService, quotaService, chunkManifestService, redissonClient);
        }

        /**
         * 验证直传完成请求为空或分片列表为空时在远程副作用前失败关闭。
         */
        @Test
        @DisplayName("should reject missing direct completion metadata")
        void shouldRejectMissingDirectCompletionMetadata() {
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(
                    directUploadState(), directUploadState(), directUploadState(), directUploadState());
            DirectUploadCompleteRequest emptyRequest = new DirectUploadCompleteRequest();
            emptyRequest.setParts(List.of());

            assertThrows(GeneralException.class, () ->
                    fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, null));
            assertThrows(GeneralException.class, () ->
                    fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, emptyRequest));

            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
            verify(finalizationLock, times(2)).unlock();
        }

        /**
         * 验证完成请求缺失 index 时不会静默当作 0，也不会调用 storage 或最终化服务。
         */
        @Test
        void shouldRejectMissingDirectCompletionPartIndexBeforeStorage() {
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(directUploadState(), directUploadState());
            DirectUploadCompletePartRequest part = new DirectUploadCompletePartRequest();
            part.setIndex(null);
            part.setETag("etag");
            DirectUploadCompleteRequest request = new DirectUploadCompleteRequest();
            request.setParts(List.of(part));

            assertThrows(GeneralException.class, () ->
                    fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, request));

            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
        }

        /**
         * 验证绕过 Controller 直接调用 service 时，空白或超长 ETag 仍在 storage 副作用前失败关闭。
         */
        @Test
        @DisplayName("should reject invalid direct completion ETags before storage")
        void shouldRejectInvalidDirectCompletionEtagsBeforeStorage() {
            when(redisStateManager.getState(CLIENT_ID)).thenAnswer(invocation -> directUploadState());
            List<String> invalidEtags = Arrays.asList(
                    null, "", "   ", " leading", "trailing ", "line\nbreak", "\u007f", "x".repeat(256));

            for (String invalidEtag : invalidEtags) {
                DirectUploadCompletePartRequest part = new DirectUploadCompletePartRequest();
                part.setIndex(0);
                part.setETag(invalidEtag);
                DirectUploadCompleteRequest request = new DirectUploadCompleteRequest();
                request.setParts(List.of(part));

                assertThrows(GeneralException.class, () ->
                        fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, request));
            }

            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
            verify(finalizationLock, times(invalidEtags.size())).unlock();
        }

        /**
         * 验证 ETag 服务边界与 manifest VARCHAR(255) 保持一致。
         */
        @Test
        void shouldAcceptAtMost255EtagCharacters() {
            Boolean maxAccepted = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "isSafeDirectUploadEtag", "x".repeat(255));
            Boolean overflowRejected = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "isSafeDirectUploadEtag", "x".repeat(256));

            assertEquals(Boolean.TRUE, maxAccepted);
            assertEquals(Boolean.FALSE, overflowRejected);
        }

        /**
         * 验证 Service 边界拒绝超过 10000 个完成分片，且不会调用 storage 或产生不可逆副作用。
         */
        @Test
        @DisplayName("should reject more than ten thousand completion parts before storage")
        void shouldRejectTooManyCompletionPartsBeforeStorage() {
            when(redisStateManager.getState(CLIENT_ID)).thenAnswer(invocation -> directUploadState());
            DirectUploadCompletePartRequest part = new DirectUploadCompletePartRequest();
            part.setIndex(0);
            part.setETag("\"etag-0\"");
            DirectUploadCompleteRequest request = new DirectUploadCompleteRequest();
            request.setParts(Collections.nCopies(10_001, part));

            GeneralException exception = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeDirectUpload(USER_ID, CLIENT_ID, request));

            assertEquals(ResultEnum.PARAM_ERROR, exception.getResultEnum());
            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
            verify(redisStateManager, never()).markCompleted(anyString(), anyString(), anyInt());
            verify(finalizationLock).unlock();
        }

        /**
         * 验证 storage 完成响应任一可信字段漂移都在 PREPARE、上链、落库和 manifest 前失败关闭。
         */
        @Test
        @DisplayName("should reject every malformed storage completion field before irreversible effects")
        void shouldRejectMalformedStorageCompletionBeforeIrreversibleEffects() {
            String malformedContentHash = "sha256:not-a-canonical-digest";
            List<CompleteDirectMultipartUploadResponse> malformedResponses = new ArrayList<>();
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    "wrong-session", CONTENT_HASH, validDirectCompletedParts()));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(validDirectCompletedParts().getFirst())));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    validDirectCompletedParts().getFirst(),
                    directCompletedPart(
                            0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 512L, "\"etag-0\"",
                            CHUNK_HASH_0, CHUNK_HASH_0, "SHA-256"))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    directCompletedPart(
                            0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 511L, "\"etag-0\"",
                            CHUNK_HASH_0, CHUNK_HASH_0, "SHA-256"),
                    validDirectCompletedParts().get(1))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    directCompletedPart(
                            0, "s3://node-b/wrong", 512L, "\"etag-0\"",
                            CHUNK_HASH_0, CHUNK_HASH_0, "SHA-256"),
                    validDirectCompletedParts().get(1))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    directCompletedPart(
                            0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 512L, "\"etag-0\"",
                            "sha256:wrong", CHUNK_HASH_0, "SHA-256"),
                    validDirectCompletedParts().get(1))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    directCompletedPart(
                            0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 512L, "\"etag-0\"",
                            CHUNK_HASH_0, "sha256:wrong", "SHA-256"),
                    validDirectCompletedParts().get(1))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    directCompletedPart(
                            0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 512L, "\"etag-0\"",
                            CHUNK_HASH_0, CHUNK_HASH_0, "MD5"),
                    validDirectCompletedParts().get(1))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, CONTENT_HASH, List.of(
                    directCompletedPart(
                            0, "storage/tenant/77/chunk/" + CHUNK_HASH_0, 512L, "",
                            CHUNK_HASH_0, CHUNK_HASH_0, "SHA-256"),
                    validDirectCompletedParts().get(1))));
            malformedResponses.add(new CompleteDirectMultipartUploadResponse(
                    CLIENT_ID, malformedContentHash, validDirectCompletedParts()));
            AtomicInteger responseIndex = new AtomicInteger();
            when(redisStateManager.getState(CLIENT_ID)).thenAnswer(invocation -> directUploadState());
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileRemoteClient.completeDirectMultipartUpload(any())).thenAnswer(invocation ->
                    Result.success(malformedResponses.get(responseIndex.getAndIncrement())));

            for (int attempt = 0; attempt < malformedResponses.size(); attempt++) {
                GeneralException exception = assertThrows(
                        GeneralException.class,
                        () -> fileUploadService.completeDirectUpload(
                                USER_ID, CLIENT_ID, directCompleteRequest()));
                assertEquals(ResultEnum.FILE_SERVICE_ERROR, exception.getResultEnum());
            }

            verify(fileService, never()).prepareStoreFileWithStableId(
                    anyLong(), any(), anyLong(), anyString(), anyLong());
            verify(fileService, never()).attestDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyString(), anyString());
            verify(fileService, never()).persistDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
            verifyNoInteractions(chunkManifestService);
            verify(redisStateManager, never()).markCompleted(anyString(), anyString(), anyInt());
            verify(finalizationLock, times(malformedResponses.size())).unlock();
        }

        /**
         * 验证对象存储返回空结果或空完成分片时不会进入文件落库。
         */
        @Test
        @DisplayName("should reject empty storage completion result")
        void shouldRejectEmptyStorageCompletionResult() {
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(
                    directUploadState(), directUploadState(), directUploadState(), directUploadState());
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileRemoteClient.completeDirectMultipartUpload(any()))
                    .thenReturn(
                            Result.success((CompleteDirectMultipartUploadResponse) null),
                            Result.success(new CompleteDirectMultipartUploadResponse(
                                    CLIENT_ID, CONTENT_HASH, List.of())));

            assertThrows(GeneralException.class, () -> fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, directCompleteRequest()));
            assertThrows(GeneralException.class, () -> fileUploadService.completeDirectUpload(
                    USER_ID, CLIENT_ID, directCompleteRequest()));

            verify(fileService, never()).storeDirectUploadedFile(
                    anyLong(), any(), anyString(), anyLong(), anyList(), anyString());
            verify(chunkManifestService, never()).saveManifest(anyLong(), anyLong(), any());
            verify(finalizationLock, times(2)).unlock();
        }

        /**
         * 验证等待 finalizer 锁期间直传分片计划漂移时，不会调用对象存储或推进落库。
         */
        @Test
        @DisplayName("should reject a changed direct upload plan before side effects")
        void shouldRejectChangedDirectUploadPlanBeforeSideEffects() {
            FileUploadState initialState = directUploadState();
            FileUploadState changedState = directUploadState();
            changedState.getDirectUploadParts().getFirst().setStagingObjectName("staging/tampered/0");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(initialState, changedState);

            GeneralException exception = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeDirectUpload(
                            USER_ID,
                            CLIENT_ID,
                            directCompleteRequest()));

            assertEquals(ResultEnum.FILE_UPLOAD_ERROR, exception.getResultEnum());
            verifyNoInteractions(fileRemoteClient, fileService, quotaService, chunkManifestService);
            verify(finalizationLock).unlock();
        }

        @Test
        @DisplayName("should abort direct upload and remove local session")
        void shouldAbortDirectUpload() {
            FileUploadState state = directUploadState();
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(fileRemoteClient.abortDirectMultipartUpload(any())).thenAnswer(invocation -> {
                assertEquals(77L, TenantContext.getTenantId());
                assertFalse(TenantContext.isIgnoreIsolation());
                return Result.success(true);
            });

            boolean result = fileUploadService.abortDirectUpload(USER_ID, CLIENT_ID);

            assertTrue(result);
            ArgumentCaptor<AbortDirectMultipartUploadRequest> abortRequestCaptor =
                    ArgumentCaptor.forClass(AbortDirectMultipartUploadRequest.class);
            verify(fileRemoteClient).abortDirectMultipartUpload(abortRequestCaptor.capture());
            assertEquals(CLIENT_ID, abortRequestCaptor.getValue().sessionId());
            assertEquals(2, abortRequestCaptor.getValue().parts().size());
            assertNull(abortRequestCaptor.getValue().parts().getFirst().eTag());
            verify(redisStateManager).removeSession(CLIENT_ID, SUID);
            verify(finalizationLock).unlock();
        }

        /**
         * 验证 abort 与 complete 使用同一 clientId finalizer 锁，竞争失败时不清理会话。
         */
        @Test
        @DisplayName("should not abort while the same session finalizer lock is held")
        void shouldNotAbortWhileCompleteOwnsFinalizerLock() {
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(directUploadState());
            when(finalizationLock.tryLock()).thenReturn(false);

            assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.abortDirectUpload(USER_ID, CLIENT_ID));

            verify(redissonClient).getLock("distributed:lock:upload:finalize:session:" + CLIENT_ID);
            verify(fileRemoteClient, never()).abortDirectMultipartUpload(any());
            verify(redisStateManager, never()).removeSession(anyString(), anyString());
            verify(finalizationLock, never()).unlock();
        }

        /**
         * 验证已完成会话的 abort 是幂等空操作，不会删除终态恢复证据。
         */
        @Test
        @DisplayName("should keep completed direct session when abort is retried")
        void shouldKeepCompletedDirectSessionWhenAbortIsRetried() {
            FileUploadState completed = directUploadState();
            completed.setStatus("completed");
            completed.setDirectFileId(8401L);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(completed);

            boolean result = fileUploadService.abortDirectUpload(USER_ID, CLIENT_ID);

            assertFalse(result);
            verify(fileRemoteClient, never()).abortDirectMultipartUpload(any());
            verify(redisStateManager, never()).removeSession(anyString(), anyString());
            verify(finalizationLock).unlock();
        }

        /**
         * 验证产生 storage 或后续检查点后 abort 只能等待 complete 恢复，不能删除会话。
         */
        @Test
        @DisplayName("should block abort after direct finalization has started")
        void shouldBlockAbortAfterDirectFinalizationStarted() {
            FileUploadState checkpointed = directUploadState();
            checkpointDirectStorage(checkpointed);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(checkpointed);

            assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.abortDirectUpload(USER_ID, CLIENT_ID));

            verify(fileRemoteClient, never()).abortDirectMultipartUpload(any());
            verify(redisStateManager, never()).removeSession(anyString(), anyString());
            verify(finalizationLock).unlock();
        }

        @Test
        @DisplayName("should keep direct upload session when storage abort fails")
        void shouldKeepDirectUploadSessionWhenStorageAbortFails() {
            FileUploadState state = directUploadState();
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(fileRemoteClient.abortDirectMultipartUpload(any()))
                    .thenReturn(new Result<>(cn.flying.platformapi.constant.ResultEnum.FILE_SERVICE_ERROR, false));

            GeneralException exception = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.abortDirectUpload(USER_ID, CLIENT_ID)
            );

            assertEquals(ResultEnum.FILE_SERVICE_ERROR, exception.getResultEnum());
            verify(redisStateManager, never()).removeSession(eq(CLIENT_ID), anyString());
            verify(finalizationLock).unlock();
        }
    }

    @Nested
    @DisplayName("Start Upload")
    class StartUpload {

        @Test
        @DisplayName("should create upload session with valid parameters")
        void shouldCreateUploadSession() {
            // Given
            String fileName = "test.pdf";
            long fileSize = 1024 * 1024; // 1MB
            String contentType = "application/pdf";
            int chunkSize = 256 * 1024; // 256KB
            int totalChunks = 4;

            when(redisStateManager.getSessionIdByFileClientKey(anyLong(), anyString(), anyString())).thenReturn(null);

            // When
            StartUploadVO result = fileUploadService.startUpload(
                    USER_ID, fileName, fileSize, contentType, null, chunkSize, totalChunks);

            // Then
            assertNotNull(result);
            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals(chunkSize, result.getChunkSize());
            assertEquals(totalChunks, result.getTotalChunks());
            assertFalse(result.isResumed());
            assertTrue(result.getProcessedChunks().isEmpty());

            verify(redisStateManager).saveNewState(any(FileUploadState.class), eq(SUID));
        }

        /**
         * 验证版本续传（绑定 targetFileId）创建会话时不会重复执行增量配额校验。
         */
        @Test
        @DisplayName("should skip quota check for target version upload session")
        void shouldSkipQuotaCheckForTargetVersionUploadSession() {
            String fileName = "version-v2.txt";
            long fileSize = 2048L;
            Long targetFileId = 9527L;

            File targetFile = new File()
                    .setId(targetFileId)
                    .setUid(USER_ID)
                    .setStatus(FileUploadStatus.PREPARE.getCode())
                    .setFileName(fileName)
                    .setFileParam("{\"fileSize\":2048,\"contentType\":\"text/plain\"}");

            when(redisStateManager.getSessionIdByFileClientKey(anyLong(), anyString(), anyString())).thenReturn(null);
            when(fileService.getById(targetFileId)).thenReturn(targetFile);

            StartUploadVO result = fileUploadService.startUpload(
                    USER_ID, fileName, fileSize, "text/plain", null, 256, 8, targetFileId
            );

            assertNotNull(result);
            verify(quotaService, never()).checkUploadQuota(anyLong(), anyLong(), anyLong());
            verify(redisStateManager).saveNewState(
                    argThat(state -> Objects.equals(state.getTargetFileId(), targetFileId)),
                    eq(SUID)
            );
        }

        @Test
        @DisplayName("should resume existing session if found")
        void shouldResumeExistingSession() {
            // Given
            TenantContext.setTenantId(77L);
            String fileName = "test.pdf";
            long fileSize = 1024 * 1024;
            String existingClientId = "existing_client";

            FileUploadState existingState = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 2);
            ReflectionTestUtils.setField(existingState, "clientId", existingClientId);
            ReflectionTestUtils.setField(existingState, "fileName", fileName);
            ReflectionTestUtils.setField(existingState, "fileSize", fileSize);
            existingState.setTenantId(77L);

            when(redisStateManager.getSessionIdByFileClientKey(anyLong(), anyString(), anyString())).thenReturn(existingClientId);
            when(redisStateManager.getState(existingClientId)).thenReturn(existingState);

            // When
            StartUploadVO result = fileUploadService.startUpload(
                    USER_ID, fileName, fileSize, "application/pdf", null, 256 * 1024, 4);

            // Then
            assertNotNull(result);
            assertTrue(result.isResumed());
            assertEquals(existingClientId, result.getClientId());
            assertFalse(result.getProcessedChunks().isEmpty());

            verify(redisStateManager).removePausedSession(existingClientId);
            verify(redisStateManager).updateLastActivityTime(existingClientId);
        }

        @Test
        @DisplayName("should reject invalid file name")
        void shouldRejectInvalidFileName() {
            // Given
            String invalidFileName = "../../../etc/passwd"; // Path traversal

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, invalidFileName, 1024, "text/plain", null, 256, 1));
        }

        @Test
        @DisplayName("should reject file exceeding size limit")
        void shouldRejectOversizedFile() {
            // Given
            long tooLargeSize = 5L * 1024 * 1024 * 1024; // 5GB (limit is 4GB)

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "large.zip", tooLargeSize, "application/zip", null, 256 * 1024, 100));
        }

        @Test
        @DisplayName("should reject unsupported file type")
        void shouldRejectUnsupportedType() {
            // Given
            String unsupportedFile = "script.exe";
            String unsupportedContentType = "application/x-msdownload";

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, unsupportedFile, 1024, unsupportedContentType, null, 256, 1));
        }

        @Test
        @DisplayName("should reject zero file size")
        void shouldRejectZeroFileSize() {
            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "empty.txt", 0, "text/plain", null, 256, 0));
        }

        @Test
        @DisplayName("should reject invalid chunk size")
        void shouldRejectInvalidChunkSize() {
            // When & Then - chunk size exceeds max (80MB)
            int tooLargeChunkSize = 100 * 1024 * 1024; // 100MB
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "test.pdf", 1024 * 1024, "application/pdf", null, tooLargeChunkSize, 1));
        }

        /**
         * 验证创建会话时服务端会重新计算分片总数，拒绝客户端伪造的 totalChunks。
         */
        @Test
        @DisplayName("should reject mismatched total chunk count")
        void shouldRejectMismatchedTotalChunkCount() {
            assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(USER_ID, "test.pdf", 1024 * 1024, "application/pdf", null, 256 * 1024, 999));
        }

        /**
         * 验证总文件大小可以超过单分片内存传输上限，只要客户端按安全分片大小上传。
         */
        @Test
        @DisplayName("should allow file larger than single chunk limit when chunked safely")
        void shouldAllowFileLargerThanSingleChunkLimitWhenChunkedSafely() {
            long fileSize = 81L * 1024 * 1024;
            int chunkSize = 8 * 1024 * 1024;
            int totalChunks = 11;
            when(redisStateManager.getSessionIdByFileClientKey(anyLong(), anyString(), anyString())).thenReturn(null);

            StartUploadVO result = fileUploadService.startUpload(
                    USER_ID,
                    "large.pdf",
                    fileSize,
                    "application/pdf",
                    null,
                    chunkSize,
                    totalChunks
            );

            assertNotNull(result);
            assertEquals(chunkSize, result.getChunkSize());
            assertEquals(totalChunks, result.getTotalChunks());
            verify(redisStateManager).saveNewState(any(FileUploadState.class), eq(SUID));
        }
    }

    @Nested
    @DisplayName("Upload Chunk")
    class UploadChunk {

        /**
         * 验证分片上传时实际字节数必须匹配创建会话时确定的分片计划。
         */
        @Test
        @DisplayName("should reject chunk with unexpected byte size")
        void shouldRejectChunkWithUnexpectedByteSize() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);
            MockMultipartFile smallChunk = new MockMultipartFile(
                    "file",
                    "chunk-0.bin",
                    "application/octet-stream",
                    "too-small".getBytes(StandardCharsets.UTF_8)
            );

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.uploadChunk(USER_ID, CLIENT_ID, 0, smallChunk));

            verify(redisStateManager, never()).addUploadedChunkWithHash(anyString(), anyInt(), anyString());
        }
    }

    @Nested
    @DisplayName("Get Upload Progress")
    class GetUploadProgress {

        @Test
        @DisplayName("should return progress for valid session")
        void shouldReturnProgress() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 1);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When
            ProgressVO result = fileUploadService.getUploadProgress(USER_ID, CLIENT_ID);

            // Then
            assertNotNull(result);
            assertEquals(CLIENT_ID, result.getClientId());
            assertEquals(4, result.getTotalChunks());
            assertEquals(2, result.getUploadedChunkCount());
            assertEquals(1, result.getProcessedChunkCount());
            assertTrue(result.getProgress() > 0);
            assertTrue(result.getProgress() <= 100);

            verify(redisStateManager).updateLastActivityTime(CLIENT_ID);
        }

        @Test
        @DisplayName("should throw exception for non-existent session")
        void shouldThrowForNonExistentSession() {
            // Given
            when(redisStateManager.getState(anyString())).thenReturn(null);

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.getUploadProgress(USER_ID, "non_existent"));
        }

        @Test
        @DisplayName("should reject unauthorized access")
        void shouldRejectUnauthorizedAccess() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L); // Different user

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.getUploadProgress(USER_ID, CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("Pause and Resume Upload")
    class PauseResume {

        @Test
        @DisplayName("should pause active upload")
        void shouldPauseUpload() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When
            fileUploadService.pauseUpload(USER_ID, CLIENT_ID);

            // Then
            verify(redisStateManager).addPausedSession(CLIENT_ID);
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(finalizationLock).tryLock();
            verify(finalizationLock).unlock();
        }

        /**
         * 验证等待 finalizer 锁期间进入人工终态的会话不会重新写入暂停索引。
         */
        @Test
        void shouldRejectPauseWhenSessionBecomesManualTerminalWhileWaitingForLock() {
            FileUploadState activeState = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(activeState, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(activeState, "userId", USER_ID);
            FileUploadState manualState = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(manualState, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(manualState, "userId", USER_ID);
            manualState.setStatus("finalization_manual_reconciliation_required");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(activeState, manualState);

            assertThrows(GeneralException.class,
                    () -> fileUploadService.pauseUpload(USER_ID, CLIENT_ID));

            verify(redisStateManager, never()).addPausedSession(CLIENT_ID);
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(finalizationLock).tryLock();
            verify(finalizationLock).unlock();
        }

        /**
         * 验证本地终态复查后即使 watchdog 丢锁，Redis 原子栅栏仍会拒绝迟到暂停。
         */
        @Test
        void shouldRejectLatePauseWhenAtomicFenceObservesTerminalState() {
            FileUploadState activeState = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(activeState, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(activeState, "userId", USER_ID);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(activeState);
            when(redisStateManager.addPausedSession(CLIENT_ID))
                    .thenReturn(FileUploadRedisStateManager.PauseTransitionResult.TERMINAL);

            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.pauseUpload(USER_ID, CLIENT_ID));

            assertEquals(ResultEnum.FILE_RECORD_ERROR, error.getResultEnum());
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(finalizationLock).unlock();
        }

        @Test
        @DisplayName("should resume paused upload")
        void shouldResumeUpload() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 2);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.removePausedSession(CLIENT_ID)).thenReturn(true);

            // When
            ResumeUploadVO result = fileUploadService.resumeUpload(USER_ID, CLIENT_ID);

            // Then
            assertNotNull(result);
            assertEquals(4, result.getTotalChunks());
            assertEquals(2, result.getProcessedChunks().size());

            verify(redisStateManager).removePausedSession(CLIENT_ID);
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
        }

        @Test
        @DisplayName("should reject pause for non-existent session")
        void shouldRejectPauseForNonExistent() {
            // Given
            when(redisStateManager.getState(anyString())).thenReturn(null);

            // When & Then
            assertThrows(GeneralException.class, () ->
                    fileUploadService.pauseUpload(USER_ID, "non_existent"));
        }

        /**
         * 验证恢复上传时如果会话不存在，会抛出会话不存在异常。
         */
        @Test
        @DisplayName("should reject resume for non-existent session")
        void shouldRejectResumeForNonExistentSession() {
            when(redisStateManager.getState(anyString())).thenReturn(null);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.resumeUpload(USER_ID, "non_existent"));
        }

        /**
         * 验证恢复上传时会执行所有权校验，非所有者会被拒绝。
         */
        @Test
        @DisplayName("should reject resume for unauthorized user")
        void shouldRejectResumeForUnauthorizedUser() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 2);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.resumeUpload(USER_ID, CLIENT_ID));
        }

        /**
         * 验证暂停上传时会执行所有权校验，非所有者会被拒绝。
         */
        @Test
        @DisplayName("should reject pause for unauthorized user")
        void shouldRejectPauseForUnauthorizedUser() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            assertThrows(GeneralException.class, () ->
                    fileUploadService.pauseUpload(USER_ID, CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("Cancel Upload")
    class CancelUpload {

        @Test
        @DisplayName("should cancel and cleanup session")
        void shouldCancelUpload() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When
            boolean result = fileUploadService.cancelUpload(USER_ID, CLIENT_ID);

            // Then
            assertTrue(result);
            verify(redisStateManager).removeSession(CLIENT_ID, SUID);
        }

        @Test
        @DisplayName("should return false for non-existent session")
        void shouldReturnFalseForNonExistent() {
            // Given
            when(redisStateManager.getState(anyString())).thenReturn(null);

            // When
            boolean result = fileUploadService.cancelUpload(USER_ID, "non_existent");

            // Then
            assertFalse(result);
        }

        /**
         * 验证取消上传时会使用用户编码后的 SUID 清理会话状态。
         */
        @Test
        @DisplayName("should remove session with encoded suid")
        void shouldRemoveSessionWithEncodedSuid() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            boolean result = fileUploadService.cancelUpload(USER_ID, CLIENT_ID);

            assertTrue(result);
            verify(redisStateManager).removeSession(CLIENT_ID, SUID);
        }

        /**
         * 验证取消上传前必须校验会话所有者，避免仅凭 clientId 清理他人会话。
         */
        @Test
        @DisplayName("should reject cancel for unauthorized user")
        void shouldRejectCancelForUnauthorizedUser() {
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileUploadService.cancelUpload(USER_ID, CLIENT_ID));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, ex.getResultEnum());
            verify(redisStateManager, never()).removeSession(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Complete Upload Quota Guard")
    class CompleteUploadQuotaGuard {

        /**
         * 验证会话 finalizer 锁竞争失败时不会推进普通上传副作用。
         */
        @Test
        @DisplayName("should reject a concurrent upload finalizer before side effects")
        void shouldRejectConcurrentUploadFinalizerBeforeSideEffects() {
            FileUploadState state = completionState(CLIENT_ID, true);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(finalizationLock.tryLock()).thenReturn(false);

            assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));

            verifyNoInteractions(quotaService, fileService, eventPublisher, fileRemoteClient);
            verify(finalizationLock, never()).unlock();
        }

        /**
         * 验证 Redis 锁客户端故障会映射为可重试错误，释放阶段异常不会覆盖业务结果。
         */
        @Test
        @DisplayName("should translate finalizer lock failures and tolerate release errors")
        void shouldTranslateFinalizerLockFailuresAndTolerateReleaseErrors() {
            FileUploadState state = completionState(CLIENT_ID, true);
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redissonClient.getLock(startsWith("distributed:lock:upload:finalize:session:")))
                    .thenThrow(new IllegalStateException("redis unavailable"));

            assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));
            verifyNoInteractions(quotaService, fileService, eventPublisher, fileRemoteClient);

            RLock failingRelease = mock(RLock.class);
            doThrow(new IllegalStateException("unlock unavailable")).when(failingRelease).unlock();
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileUploadService, "releaseUploadFinalizationLock", failingRelease, CLIENT_ID));
            assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(
                    fileUploadService, "releaseUploadFinalizationLock", null, CLIENT_ID));
            verify(failingRelease, times(1)).unlock();
            verify(failingRelease, never()).isHeldByCurrentThread();
        }

        /**
         * 反射调用 reserveQuotaAndPrepareStoreFile，便于单测覆盖幂等分支。
         *
         * @param userId 用户ID
         * @param state 上传会话状态
         */
        private void invokeReserveQuotaAndPrepareStoreFile(Long userId, FileUploadState state) throws Exception {
            Method method = FileUploadServiceImpl.class.getDeclaredMethod(
                    "reserveQuotaAndPrepareStoreFile", Long.class, FileUploadState.class
            );
            method.setAccessible(true);
            method.invoke(fileUploadService, userId, state);
        }

        /**
         * 验证 completeUpload 在租户级锁内执行配额复核，并在超限时阻断 PREPARE 入库。
         */
        @Test
        @DisplayName("should recheck quota under tenant lock before prepare")
        void shouldRecheckQuotaUnderTenantLockBeforePrepare() throws InterruptedException {
            String clientId = "quota-guard-client";
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "quota-guard.bin",
                    1024L,
                    "application/octet-stream",
                    clientId,
                    256,
                    0
            );
            state.setTenantId(77L);

            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            doThrow(new GeneralException(ResultEnum.QUOTA_EXCEEDED, "quota exceeded"))
                    .when(quotaService).checkUploadQuota(77L, USER_ID, 1024L);

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileUploadService.completeUpload(USER_ID, clientId));

            assertEquals(ResultEnum.QUOTA_EXCEEDED, ex.getResultEnum());
            verify(quotaLock).tryLock(5L, TimeUnit.SECONDS);
            verify(quotaLock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
            verify(quotaLock, never()).lock();
            verify(quotaLock, never()).lock(anyLong(), any(TimeUnit.class));
            verify(quotaService).checkUploadQuota(77L, USER_ID, 1024L);
            verify(fileService, never()).prepareStoreFile(anyLong(), any(), anyString(), anyLong());
            verify(quotaLock).unlock();
        }

        /**
         * 验证租户配额锁等待超时时返回可重试错误，且不会进入配额、数据库或 Redis 临界区。
         */
        @Test
        @DisplayName("should return retryable error when quota completion lock is busy")
        void shouldReturnRetryableWhenQuotaCompletionLockIsBusy() throws InterruptedException {
            FileUploadState state = completionState("quota-lock-busy", true);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

            RetryableException exception = assertThrows(
                    RetryableException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileUploadService, "recheckQuotaBeforeFinalProcessing", USER_ID, state));

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, exception.getResultEnum());
            verify(quotaLock).tryLock(5L, TimeUnit.SECONDS);
            verifyNoInteractions(quotaService, fileService);
            verify(redisStateManager, never()).updateState(any());
            verify(quotaLock, never()).unlock();
        }

        /**
         * 验证租户配额锁等待被中断时恢复中断标记并返回可重试错误，不推进 PREPARE 副作用。
         */
        @Test
        @DisplayName("should preserve interrupt status when quota lock wait is interrupted")
        void shouldPreserveInterruptStatusWhenQuotaLockWaitIsInterrupted() throws InterruptedException {
            FileUploadState state = completionState("quota-lock-interrupted", true);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.tryLock(5L, TimeUnit.SECONDS))
                    .thenThrow(new InterruptedException("interrupted"));
            assertFalse(Thread.currentThread().isInterrupted());

            try {
                RetryableException exception = assertThrows(
                        RetryableException.class,
                        () -> ReflectionTestUtils.invokeMethod(
                                fileUploadService, "reserveQuotaAndPrepareStoreFile", USER_ID, state));

                assertEquals(ResultEnum.SERVICE_UNAVAILABLE, exception.getResultEnum());
                assertTrue(Thread.currentThread().isInterrupted());
                verify(redisStateManager, never()).getState("quota-lock-interrupted");
                verifyNoInteractions(quotaService, fileService);
                verify(quotaLock, never()).unlock();
            } finally {
                Thread.interrupted();
            }
        }

        /**
         * 验证完成上传前必须校验会话所有者，避免非所有者推进存证和配额流程。
         */
        @Test
        @DisplayName("should reject complete for unauthorized user")
        void shouldRejectCompleteForUnauthorizedUser() {
            FileUploadState state = FileUploadStateTestBuilder.aCompletedUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileUploadService.completeUpload(USER_ID, CLIENT_ID));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, ex.getResultEnum());
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(fileService, never()).prepareStoreFile(anyLong(), any(), anyString(), anyLong());
            verifyNoInteractions(redissonClient);
        }

        /**
         * 验证完成态会话重复提交 complete 时直接幂等返回，不重放任何不可逆副作用。
         */
        @Test
        @DisplayName("should ignore complete replay for completed session")
        void shouldIgnoreCompleteReplayForCompletedSession() {
            FileUploadState state = FileUploadStateTestBuilder.aCompletedUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);
            state.setStatus("completed");

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            fileUploadService.completeUpload(USER_ID, CLIENT_ID);

            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verifyNoInteractions(redissonClient, quotaService, fileService, eventPublisher);
        }

        /**
         * 验证普通上传 completed 主状态后的 TTL/索引收敛可重试，且不重复事件、Saga 或链调用。
         */
        @Test
        void completedLegacyRetryShouldConvergeWithoutReplayingFinalizationSideEffects() {
            FileUploadState completed = FileUploadStateTestBuilder.aCompletedUploadState();
            ReflectionTestUtils.setField(completed, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(completed, "userId", USER_ID);
            completed.setSuid(SUID);
            completed.setStatus("completed");
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(completed);
            doThrow(new IllegalStateException("auxiliary ttl unavailable"))
                    .doNothing()
                    .when(redisStateManager)
                    .markCompleted(CLIENT_ID, SUID, 300);

            RetryableException firstFailure = assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));
            assertDoesNotThrow(() -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, firstFailure.getResultEnum());
            verify(redisStateManager, times(2)).markCompleted(CLIENT_ID, SUID, 300);
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verifyNoInteractions(redissonClient, quotaService, fileService, eventPublisher, fileRemoteClient);
        }

        /**
         * 验证 DB 已进入 SUCCESS 而 Redis 尚未 completed 的崩溃窗口可安全恢复，且不重放事件、Saga 或链调用。
         */
        @Test
        void shouldRecoverLegacyDbSuccessWithoutReplayingFinalizationSideEffects() {
            long preparedFileId = 9303L;
            FileUploadState state = completionState(CLIENT_ID, true);
            state.setPrepareStored(true);
            state.setPreparedFileId(preparedFileId);
            state.setContentHash(CONTENT_HASH);
            File storedFile = new File()
                    .setId(preparedFileId)
                    .setTenantId(77L)
                    .setUid(USER_ID)
                    .setFileName("refresh.txt")
                    .setFileSize(1024L)
                    .setContentHash(CONTENT_HASH)
                    .setFileHash("chain-file-id")
                    .setTransactionHash("tx-id")
                    .setStatus(FileUploadStatus.SUCCESS.getCode());
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(fileService.getById(preparedFileId)).thenReturn(storedFile);

            fileUploadService.completeUpload(USER_ID, CLIENT_ID);

            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
            verify(redisStateManager, never()).updateLastActivityTime(CLIENT_ID);
            verify(fileService, never()).storeFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyList(), anyString(), anyString());
            verifyNoInteractions(eventPublisher, fileRemoteClient, quotaService);
            verify(finalizationLock).unlock();
        }

        /**
         * 验证补 Redis 完成态失败后保留 SUCCESS 恢复能力，重试仍不重复执行 Saga 或链。
         */
        @Test
        void redisCompletionFailureShouldRemainRetryableAfterLegacyDbSuccess() {
            long preparedFileId = 9304L;
            FileUploadState state = completionState(CLIENT_ID, true);
            state.setPrepareStored(true);
            state.setPreparedFileId(preparedFileId);
            state.setContentHash(CONTENT_HASH);
            File storedFile = new File()
                    .setId(preparedFileId)
                    .setTenantId(77L)
                    .setUid(USER_ID)
                    .setFileName("refresh.txt")
                    .setFileSize(1024L)
                    .setContentHash(CONTENT_HASH)
                    .setFileHash("chain-file-id")
                    .setTransactionHash("tx-id")
                    .setStatus(FileUploadStatus.SUCCESS.getCode());
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(fileService.getById(preparedFileId)).thenReturn(storedFile);
            doThrow(new IllegalStateException("redis unavailable"))
                    .doNothing()
                    .when(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);

            RetryableException firstFailure = assertThrows(
                    RetryableException.class,
                    () -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));
            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, firstFailure.getResultEnum());
            assertDoesNotThrow(() -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));

            verify(redisStateManager, times(2)).markCompleted(CLIENT_ID, SUID, 300);
            verify(redisStateManager, never()).removeSession(eq(CLIENT_ID), anyString());
            verify(fileService, never()).storeFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyList(), anyString(), anyString());
            verifyNoInteractions(eventPublisher, fileRemoteClient, quotaService);
        }

        /**
         * 验证稳定主键指向其他租户或身份的 SUCCESS 时失败关闭，绝不借用该终态。
         */
        @Test
        void shouldRejectLegacySuccessOwnedByAnotherTenant() {
            long preparedFileId = 9305L;
            FileUploadState state = completionState(CLIENT_ID, true);
            state.setPrepareStored(true);
            state.setPreparedFileId(preparedFileId);
            state.setContentHash(CONTENT_HASH);
            File otherTenantFile = new File()
                    .setId(preparedFileId)
                    .setTenantId(88L)
                    .setUid(USER_ID)
                    .setFileName("refresh.txt")
                    .setFileSize(1024L)
                    .setContentHash(CONTENT_HASH)
                    .setFileHash("chain-file-id")
                    .setTransactionHash("tx-id")
                    .setStatus(FileUploadStatus.SUCCESS.getCode());
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(fileService.getById(preparedFileId)).thenReturn(otherTenantFile);

            GeneralException error = assertThrows(
                    GeneralException.class,
                    () -> fileUploadService.completeUpload(USER_ID, CLIENT_ID));

            assertEquals(ResultEnum.FILE_RECORD_ERROR, error.getResultEnum());
            verify(redisStateManager, never()).markCompleted(anyString(), anyString(), anyLong());
            verify(fileService, never()).storeFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyList(), anyString(), anyString());
            verifyNoInteractions(eventPublisher, fileRemoteClient, quotaService);
        }

        /**
         * 验证同步事件发布完整返回后才允许写入 Redis completed 状态。
         */
        @Test
        void shouldMarkCompletedOnlyAfterSynchronousStorageEventReturns() {
            FileUploadState state = completionState(CLIENT_ID, true);
            state.setPreparedFileId(9301L);
            state.setContentHash(CONTENT_HASH);
            java.util.concurrent.atomic.AtomicBoolean storageFinalizationReturned =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            doAnswer(invocation -> {
                storageFinalizationReturned.set(true);
                return null;
            }).when(eventPublisher).publishEvent(any(FileStorageEvent.class));
            doAnswer(invocation -> {
                assertTrue(storageFinalizationReturned.get());
                return null;
            }).when(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);

            ReflectionTestUtils.invokeMethod(
                    fileUploadService,
                    "publishFileStorageEventAndMarkCompleted",
                    USER_ID,
                    SUID,
                    state,
                    List.of(new java.io.File("processed-part-0")),
                    List.of("hash-0"));

            InOrder completionOrder = inOrder(eventPublisher, redisStateManager);
            completionOrder.verify(eventPublisher).publishEvent(any(FileStorageEvent.class));
            completionOrder.verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
        }

        /**
         * 验证同步监听器抛出的存证失败会回传，并阻止 Redis completed 状态。
         */
        @Test
        void storageEventFailureShouldPropagateWithoutMarkingCompleted() {
            FileUploadState state = completionState(CLIENT_ID, true);
            state.setPreparedFileId(9302L);
            state.setContentHash(CONTENT_HASH);
            GeneralException storageFailure =
                    new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "chain write failed");
            doThrow(storageFailure).when(eventPublisher).publishEvent(any(FileStorageEvent.class));

            GeneralException actual = assertThrows(
                    GeneralException.class,
                    () -> ReflectionTestUtils.invokeMethod(
                            fileUploadService,
                            "publishFileStorageEventAndMarkCompleted",
                            USER_ID,
                            SUID,
                            state,
                            List.of(new java.io.File("processed-part-0")),
                            List.of("hash-0")));

            assertSame(storageFailure, actual);
            verify(redisStateManager, never()).markCompleted(anyString(), anyString(), anyLong());
        }

        /**
         * 验证未处理分片不会在 finalizer 锁内等待，并立即释放锁供异步任务继续。
         */
        @Test
        @DisplayName("should return immediately when async chunks are still pending")
        void shouldReturnImmediatelyWhenAsyncChunksAreStillPending() {
            String clientId = "pending-async";
            FileUploadState initial = completionState(clientId, false);
            TenantContext.setTenantId(77L);
            when(redisStateManager.getState(clientId)).thenReturn(initial);

            RetryableException error = assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(1),
                    () -> assertThrows(
                            RetryableException.class,
                            () -> fileUploadService.completeUpload(USER_ID, clientId)));

            assertEquals(ResultEnum.SERVICE_UNAVAILABLE, error.getResultEnum());
            verify(finalizationLock).unlock();
            verifyNoInteractions(quotaService, eventPublisher);
            verify(fileService, never()).prepareStoreFile(anyLong(), any(), anyString(), anyLong());
        }

        /**
         * 验证进程重启丢失内存 future 后，complete 会从 uploaded/hash/raw 证据重排任务，下一次重试越过处理门槛。
         */
        @Test
        void completeShouldRequeuePersistedRawChunkAfterProcessRestart() throws Exception {
            String clientId = "restart-requeue-" + UUID.randomUUID();
            FileUploadState state = completionState(clientId, false);
            byte[] rawBytes = new byte[1024];
            Arrays.fill(rawBytes, (byte) 3);
            Path rawDirectory = Path.of("uploads").toAbsolutePath().normalize()
                    .resolve(SUID).resolve(clientId);
            Path processedDirectory = Path.of("processed").toAbsolutePath().normalize()
                    .resolve(SUID).resolve(clientId);
            Path rawChunk = rawDirectory.resolve("chunk_0");
            Path processedChunk = processedDirectory.resolve("encrypted_chunk_0");
            Files.createDirectories(rawDirectory);
            Files.write(rawChunk, rawBytes);
            String plainHash = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "calculateChunkHashBase64", rawChunk);
            state.getUploadedChunks().add(0);
            state.getChunkHashes().put("chunk_0", plainHash);
            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(encryptionStrategyFactory.getStrategy())
                    .thenReturn(new AesGcmEncryptionStrategy());
            when(encryptionStrategyFactory.getCurrentAlgorithmName()).thenReturn("AES-256-GCM");
            when(redisStateManager.getOrCreateChunkKey(
                    eq(clientId), eq(0), any(byte[].class)))
                    .thenAnswer(invocation -> invocation.getArgument(2));
            doAnswer(invocation -> {
                state.getProcessedChunks().add(0);
                return null;
            }).when(redisStateManager).addProcessedChunk(clientId, 0);

            try {
                assertThrows(
                        RetryableException.class,
                        () -> fileUploadService.completeUpload(USER_ID, clientId));

                assertTrue(state.getProcessedChunks().contains(0));
                assertTrue(Files.isRegularFile(processedChunk));
                when(redissonClient.getLock(
                        "distributed:lock:upload:quota:complete:tenant:77"))
                        .thenReturn(quotaLock);
                doThrow(new GeneralException(ResultEnum.QUOTA_EXCEEDED, "quota guard reached"))
                        .when(quotaService).checkUploadQuota(77L, USER_ID, 1024L);
                GeneralException secondAttempt = assertThrows(
                        GeneralException.class,
                        () -> fileUploadService.completeUpload(USER_ID, clientId));
                assertEquals(ResultEnum.QUOTA_EXCEEDED, secondAttempt.getResultEnum());
            } finally {
                Files.deleteIfExists(rawChunk);
                Files.deleteIfExists(processedChunk);
                Files.deleteIfExists(rawDirectory);
                Files.deleteIfExists(processedDirectory);
            }
        }

        /**
         * 验证重载快照的最后分片密钥会进入后续事件参数，而旧快照缺失的密钥不会被复用。
         */
        @Test
        @DisplayName("should use refreshed keys when generating completion parameters")
        void shouldUseRefreshedKeysWhenGeneratingCompletionParameters() {
            String clientId = "refresh-key-state";
            FileUploadState initial = completionState(clientId, false);
            FileUploadState latest = completionState(clientId, true);
            latest.setContentHash(CONTENT_HASH);
            TenantContext.setTenantId(77L);
            when(redisStateManager.getState(clientId)).thenReturn(latest);

            FileUploadState refreshed = ReflectionTestUtils.invokeMethod(
                    fileUploadService,
                    "requireLatestCompletionState",
                    USER_ID,
                    clientId,
                    initial,
                    1);
            String fileParam = ReflectionTestUtils.invokeMethod(
                    fileUploadService, "generateFileParam", refreshed);

            assertSame(latest, refreshed);
            assertNotNull(fileParam);
            assertTrue(fileParam.contains(Base64.getEncoder().encodeToString(latest.getKeys().get(0))));
            assertTrue(fileParam.contains(CONTENT_HASH));
        }

        /**
         * 验证完成等待后的状态丢失、所有者漂移和上传计划漂移均在副作用前失败关闭。
         */
        @Test
        @DisplayName("should fail closed when refreshed completion state drifts")
        void shouldFailClosedWhenRefreshedCompletionStateDrifts() {
            String clientId = "refresh-drift-state";
            FileUploadState initial = completionState(clientId, false);
            FileUploadState otherOwner = completionState(clientId, true);
            otherOwner.setUserId(999L);
            FileUploadState changedPlan = completionState(clientId, true);
            changedPlan.setTotalChunks(2);
            TenantContext.setTenantId(77L);
            when(redisStateManager.getState(clientId)).thenReturn(null, otherOwner, changedPlan);

            GeneralException missing = assertThrows(GeneralException.class, () ->
                    ReflectionTestUtils.invokeMethod(
                            fileUploadService,
                            "requireLatestCompletionState",
                            USER_ID,
                            clientId,
                            initial,
                            1));
            GeneralException unauthorized = assertThrows(GeneralException.class, () ->
                    ReflectionTestUtils.invokeMethod(
                            fileUploadService,
                            "requireLatestCompletionState",
                            USER_ID,
                            clientId,
                            initial,
                            1));
            GeneralException drifted = assertThrows(GeneralException.class, () ->
                    ReflectionTestUtils.invokeMethod(
                            fileUploadService,
                            "requireLatestCompletionState",
                            USER_ID,
                            clientId,
                            initial,
                            1));

            assertEquals(ResultEnum.UPLOAD_SESSION_NOT_FOUND, missing.getResultEnum());
            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED, unauthorized.getResultEnum());
            assertEquals(ResultEnum.FILE_UPLOAD_ERROR, drifted.getResultEnum());
            verifyNoInteractions(redissonClient, quotaService, fileService, eventPublisher);
        }

        /**
         * 验证首次预占位会写入 PREPARE 元数据并回写会话幂等标记。
         */
        @Test
        @DisplayName("should mark prepare stored after reserve success")
        void shouldMarkPrepareStoredAfterReserveSuccess() throws Exception {
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "prepare-once.bin",
                    2048L,
                    "application/octet-stream",
                    "prepare-once-client",
                    256,
                    0
            );
            state.setTenantId(77L);
            state.setPrepareStored(false);

            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(redisStateManager.getState("prepare-once-client")).thenReturn(state);
            when(fileService.prepareStoreFileWithStableId(
                    USER_ID, null, 7001L, "prepare-once.bin", 2048L))
                    .thenReturn(new File()
                            .setId(7001L)
                            .setTenantId(77L)
                            .setUid(USER_ID)
                            .setFileName("prepare-once.bin")
                            .setFileSize(2048L)
                            .setStatus(FileUploadStatus.PREPARE.getCode()));

            invokeReserveQuotaAndPrepareStoreFile(USER_ID, state);

            verify(quotaService).checkUploadQuota(77L, USER_ID, 2048L);
            verify(fileService).prepareStoreFileWithStableId(
                    USER_ID, null, 7001L, "prepare-once.bin", 2048L);
            assertEquals(7001L, state.getPreparedFileId());
            verify(redisStateManager, times(2)).updateState(any(FileUploadState.class));
            assertTrue(state.isPrepareStored());
            verify(quotaLock).unlock();
        }

        /**
         * 验证版本上传场景会将 PREPARE 预占位绑定到目标 fileId。
         */
        @Test
        @DisplayName("should bind prepare reserve to target file id")
        void shouldBindPrepareReserveToTargetFileId() throws Exception {
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "version-v2.bin",
                    4096L,
                    "application/octet-stream",
                    "version-client",
                    256,
                    0,
                    9527L
            );
            state.setTenantId(77L);
            state.setPrepareStored(false);

            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(redisStateManager.getState("version-client")).thenReturn(state);
            when(fileService.prepareStoreFileWithStableId(
                    USER_ID, 9527L, 9527L, "version-v2.bin", 4096L))
                    .thenReturn(new File()
                            .setId(9527L)
                            .setTenantId(77L)
                            .setUid(USER_ID)
                            .setFileName("version-v2.bin")
                            .setFileSize(4096L)
                            .setStatus(FileUploadStatus.PREPARE.getCode()));

            invokeReserveQuotaAndPrepareStoreFile(USER_ID, state);

            verify(quotaService, never()).checkUploadQuota(anyLong(), anyLong(), anyLong());
            verify(fileService).prepareStoreFileWithStableId(
                    USER_ID, 9527L, 9527L, "version-v2.bin", 4096L);
            assertEquals(9527L, state.getPreparedFileId());
            verify(redisStateManager, times(2)).updateState(any(FileUploadState.class));
            assertTrue(state.isPrepareStored());
            verify(quotaLock).unlock();
        }

        /**
         * 验证会话已落库 PREPARE 时，重试调用不会重复计费或重复插入元数据。
         */
        @Test
        @DisplayName("should skip duplicate prepare when already reserved")
        void shouldSkipDuplicatePrepareWhenAlreadyReserved() throws Exception {
            FileUploadState state = new FileUploadState(
                    USER_ID,
                    "prepare-retry.bin",
                    2048L,
                    "application/octet-stream",
                    "prepare-retry-client",
                    256,
                    0
            );
            state.setTenantId(77L);
            state.setPrepareStored(true);
            state.setPreparedFileId(7001L);

            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(redisStateManager.getState("prepare-retry-client")).thenReturn(state);

            invokeReserveQuotaAndPrepareStoreFile(USER_ID, state);

            verify(quotaService, never()).checkUploadQuota(anyLong(), anyLong(), anyLong());
            verify(fileService, never()).prepareStoreFileWithStableId(
                    anyLong(), any(), anyLong(), anyString(), anyLong());
            verify(redisStateManager, never()).updateState(any(FileUploadState.class));
            verify(quotaLock).unlock();
        }
    }

    @Nested
    @DisplayName("NEXT_KEY Idempotency")
    class NextKeyIdempotency {

        /**
         * 验证重复执行追加密钥时不会重复写入 NEXT_KEY 元数据。
         */
        @Test
        @DisplayName("should append next key metadata only once")
        void shouldAppendNextKeyMetadataOnlyOnce() throws Exception {
            Path tempChunk = Files.createTempFile("chunk-next-key-", ".bin");
            tempChunk.toFile().deleteOnExit();
            Files.writeString(tempChunk, "cipher-data\n--HASH--\nhash-value", StandardCharsets.UTF_8);

            byte[] nextKey = new byte[32];
            Arrays.fill(nextKey, (byte) 7);

            Method appendMethod = FileUploadServiceImpl.class.getDeclaredMethod(
                    "appendKeyToFile", Path.class, byte[].class, String.class, int.class
            );
            appendMethod.setAccessible(true);

            appendMethod.invoke(fileUploadService, tempChunk, nextKey, "hash-value", 0);
            appendMethod.invoke(fileUploadService, tempChunk, nextKey, "hash-value", 0);

            String content = Files.readString(tempChunk, StandardCharsets.UTF_8);
            String separatorRegex = "\\Q\n--NEXT_KEY--\n\\E";
            int separatorCount = content.split(separatorRegex, -1).length - 1;
            assertEquals(1, separatorCount);
        }

        /**
         * 验证崩溃留下的半截 NEXT_KEY 尾部会失败关闭，且原文件字节保持不变。
         */
        @Test
        void partialNextKeyTailShouldNeverBeAppendedAgain() throws Exception {
            Path tempChunk = Files.createTempFile("chunk-partial-next-key-", ".bin");
            Files.writeString(
                    tempChunk,
                    "cipher-data\n--HASH--\nhash-value\n--NEXT_KEY--\nAQI",
                    StandardCharsets.UTF_8);
            byte[] before = Files.readAllBytes(tempChunk);
            Method appendMethod = FileUploadServiceImpl.class.getDeclaredMethod(
                    "appendKeyToFile", Path.class, byte[].class, String.class, int.class);
            appendMethod.setAccessible(true);

            InvocationTargetException error = assertThrows(
                    InvocationTargetException.class,
                    () -> appendMethod.invoke(
                            fileUploadService, tempChunk, new byte[32], "hash-value", 0));

            assertInstanceOf(IOException.class, error.getCause());
            assertArrayEquals(before, Files.readAllBytes(tempChunk));
        }

        /**
         * 验证已完整写入的不同有效密钥不能被当作幂等成功或覆盖。
         */
        @Test
        void differentCompleteNextKeyShouldFailClosedWithoutOverwrite() throws Exception {
            Path tempChunk = Files.createTempFile("chunk-different-next-key-", ".bin");
            Files.writeString(tempChunk, "cipher-data\n--HASH--\nhash-value", StandardCharsets.UTF_8);
            byte[] firstKey = new byte[32];
            Arrays.fill(firstKey, (byte) 1);
            byte[] differentKey = new byte[32];
            Arrays.fill(differentKey, (byte) 2);
            Method appendMethod = FileUploadServiceImpl.class.getDeclaredMethod(
                    "appendKeyToFile", Path.class, byte[].class, String.class, int.class);
            appendMethod.setAccessible(true);
            appendMethod.invoke(fileUploadService, tempChunk, firstKey, "hash-value", 0);
            byte[] committed = Files.readAllBytes(tempChunk);

            InvocationTargetException error = assertThrows(
                    InvocationTargetException.class,
                    () -> appendMethod.invoke(
                            fileUploadService, tempChunk, differentKey, "hash-value", 0));

            assertInstanceOf(IOException.class, error.getCause());
            assertArrayEquals(committed, Files.readAllBytes(tempChunk));
        }
    }

    @Nested
    @DisplayName("Check File Status")
    class CheckFileStatus {

        @Test
        @DisplayName("should return UPLOADING status for active upload")
        void shouldReturnUploadingStatus() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadStateWithChunks(4, 2, 1);
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);

            // When
            var result = fileUploadService.checkFileStatus(USER_ID, CLIENT_ID);

            // Then
            assertNotNull(result);
            assertEquals("UPLOADING", result.getStatus());
            assertFalse(result.isPaused());
        }

        @Test
        @DisplayName("should return PAUSED status for paused upload")
        void shouldReturnPausedStatus() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(true);

            // When
            var result = fileUploadService.checkFileStatus(USER_ID, CLIENT_ID);

            // Then
            assertEquals("PAUSED", result.getStatus());
            assertTrue(result.isPaused());
        }

        @Test
        @DisplayName("should return PROCESSING_COMPLETE when all chunks processed")
        void shouldReturnProcessingComplete() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.aCompletedUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);

            // When
            var result = fileUploadService.checkFileStatus(USER_ID, CLIENT_ID);

            // Then
            assertEquals("PROCESSING_COMPLETE", result.getStatus());
        }
    }

    @Nested
    @DisplayName("Ownership Validation")
    class OwnershipValidation {

        @Test
        @DisplayName("should allow owner to access session")
        void shouldAllowOwner() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", USER_ID);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When & Then - no exception
            assertDoesNotThrow(() -> fileUploadService.getUploadProgress(USER_ID, CLIENT_ID));
        }

        @Test
        @DisplayName("should reject non-owner access")
        void shouldRejectNonOwner() {
            // Given
            FileUploadState state = FileUploadStateTestBuilder.anUploadState();
            ReflectionTestUtils.setField(state, "clientId", CLIENT_ID);
            ReflectionTestUtils.setField(state, "userId", 999L);

            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);

            // When & Then
            Long differentUserId = 888L;
            assertThrows(GeneralException.class, () ->
                    fileUploadService.getUploadProgress(differentUserId, CLIENT_ID));
        }
    }

    @Nested
    @DisplayName("CleanupExpiredUploadSessions")
    class CleanupExpiredUploadSessions {

        @Test
        @DisplayName("should be protected by distributed lock to avoid multi-instance overlap")
        void shouldBeProtectedByDistributedLock() throws Exception {
            Method method = FileUploadServiceImpl.class.getDeclaredMethod("cleanupExpiredUploadSessions");
            DistributedLock lock = method.getAnnotation(DistributedLock.class);

            assertNotNull(lock);
            assertEquals("upload:session:cleanup", lock.key());
            assertEquals(3600, lock.leaseTime());
            assertFalse(lock.throwOnFailure());
        }

        @Test
        @DisplayName("should cleanup expired active session")
        void shouldCleanupExpiredActiveSession() {
            String clientId = "expired_active_1";
            FileUploadState state = completionState(clientId, false);
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redisStateManager.isSessionPaused(clientId)).thenReturn(false);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).removeSession(clientId, SUID);
        }

        @Test
        @DisplayName("should cleanup expired paused session with longer timeout")
        void shouldCleanupExpiredPausedSession() {
            String clientId = "expired_paused_1";
            FileUploadState state = completionState(clientId, false);
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(25));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redisStateManager.isSessionPaused(clientId)).thenReturn(true);
            when(redisStateManager.getPauseAwareLastActivityTime(state))
                    .thenReturn(state.getLastActivityTime());

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).removeSession(clientId, SUID);
        }

        /**
         * 验证主状态活动时间很旧但刚暂停的会话不会被定时任务立即清理。
         */
        @Test
        void shouldUseAtomicPauseTimestampForPausedCleanupEligibility() {
            String clientId = "recently_paused_1";
            FileUploadState state = completionState(clientId, false);
            state.setLastActivityTime(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(25));
            long recentlyPausedAt = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1);

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(state);
            when(redisStateManager.isSessionPaused(clientId)).thenReturn(true);
            when(redisStateManager.getPauseAwareLastActivityTime(state)).thenReturn(recentlyPausedAt);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager, never()).removeSession(eq(clientId), anyString());
            verify(finalizationLock, never()).tryLock();
        }

        @Test
        @DisplayName("should abort expired direct upload session before removing Redis state")
        void shouldAbortExpiredDirectUploadSessionBeforeRemovingState() {
            FileUploadState state = directUploadState();
            state.setCleanupRetryCount(null);
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            when(fileRemoteClient.abortDirectMultipartUpload(any())).thenReturn(Result.success(true));

            fileUploadService.cleanupExpiredUploadSessions();

            ArgumentCaptor<AbortDirectMultipartUploadRequest> abortRequestCaptor =
                    ArgumentCaptor.forClass(AbortDirectMultipartUploadRequest.class);
            verify(fileRemoteClient).abortDirectMultipartUpload(abortRequestCaptor.capture());
            assertEquals(CLIENT_ID, abortRequestCaptor.getValue().sessionId());
            assertEquals(2, abortRequestCaptor.getValue().parts().size());
            assertEquals(
                    "tenant/77/staging/direct-upload/" + CLIENT_ID + "/part-0",
                    abortRequestCaptor.getValue().parts().getFirst().stagingObjectName());
            verify(redisStateManager).removeSession(CLIENT_ID, SUID);
            verify(redisStateManager, never()).removeSession(CLIENT_ID, "");
            assertNull(TenantContext.getTenantId());
            assertFalse(TenantContext.isIgnoreIsolation());
        }

        /**
         * 验证直传主状态在扫描后过期时只清调度索引，辅助证据不得被 destructive remove。
         */
        @Test
        void shouldOnlyClearDirectIndexesWhenStateExpiresAfterFinalizerLock() {
            FileUploadState state = directUploadState();
            state.setLastActivityTime(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state, state, null);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager, never()).removeSession(eq(CLIENT_ID), anyString());
            verify(redisStateManager).clearSessionIndexes(CLIENT_ID);
            verify(fileRemoteClient, never()).abortDirectMultipartUpload(any());
            verify(finalizationLock).tryLock();
            verify(finalizationLock).unlock();
        }

        @Test
        @DisplayName("should retain expired direct upload session when storage abort fails")
        void shouldRetainExpiredDirectUploadSessionWhenStorageAbortFails() {
            FileUploadState state = directUploadState();
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            TenantContext.setTenantId(99L);
            TenantContext.setIgnoreIsolation(true);
            when(fileRemoteClient.abortDirectMultipartUpload(any())).thenAnswer(invocation -> {
                assertEquals(77L, TenantContext.getTenantId());
                assertFalse(TenantContext.isIgnoreIsolation());
                return new Result<>(cn.flying.platformapi.constant.ResultEnum.FILE_SERVICE_ERROR, false);
            });

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager, never()).removeSession(eq(CLIENT_ID), anyString());
            verify(redisStateManager).updateState(argThat(updated ->
                    updated.isDirectUpload() && updated.getCleanupRetryCount() == 1));
            assertEquals(99L, TenantContext.getTenantId());
            assertTrue(TenantContext.isIgnoreIsolation());
        }

        /**
         * 验证远程 abort 连续失败达到上限后退出 active 调度并保留有限期人工清理诊断。
         */
        @Test
        void shouldMoveDirectCleanupToManualStateAfterMaxRetries() {
            FileUploadState state = directUploadState();
            state.setCleanupRetryCount(2);
            state.setLastActivityTime(System.currentTimeMillis() - java.util.concurrent.TimeUnit.HOURS.toMillis(13));
            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            when(fileRemoteClient.abortDirectMultipartUpload(any()))
                    .thenReturn(new Result<>(cn.flying.platformapi.constant.ResultEnum.FILE_SERVICE_ERROR, false));

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).retainManualReconciliationState(
                    same(state),
                    eq("cleanup_manual_required"),
                    eq(7 * 24 * 60 * 60L));
            assertEquals(3, state.getCleanupRetryCount());
            verify(redisStateManager, never()).markCompleted(eq(CLIENT_ID), eq(SUID), anyLong());
            verify(redisStateManager, never()).removeSession(eq(CLIENT_ID), anyString());
        }

        /**
         * 验证定时清理可使用 Redis 中的可信完成分片恢复 direct checkpoint，并且不重复调用 storage 或链。
         */
        @Test
        void shouldResumeExpiredDirectCheckpointInsideExistingFinalizerLock() {
            long fileId = 9101L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            state.setLastActivityTime(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(13));
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView manifest = directManifest(fileId);

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(fileService.getById(fileId)).thenReturn(storedFile);
            when(chunkManifestService.calculateManifestHash(any())).thenReturn("manifest-hash");
            when(chunkManifestService.findActiveManifest(USER_ID, fileId))
                    .thenReturn(Optional.of(manifest));

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
            verify(fileService, never()).attestDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyString(), anyString());
            verify(fileService, never()).persistDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
            verify(finalizationLock, times(1)).tryLock();
            verify(finalizationLock, times(1)).unlock();
            assertNull(TenantContext.getTenantId());
            assertFalse(TenantContext.isIgnoreIsolation());
        }

        /**
         * 验证自动恢复连续失败达到上限后退出调度集合，并以七天 TTL 保留人工对账证据。
         */
        @Test
        void shouldBoundDirectCheckpointRecoveryFailuresAndMoveToManualReconciliation() {
            FileUploadState state = directUploadState();
            checkpointDirectStorage(state);
            state.setCleanupRetryCount(2);
            state.setLastActivityTime(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            doThrow(new GeneralException(ResultEnum.QUOTA_EXCEEDED, "quota unavailable"))
                    .when(quotaService).checkUploadQuota(77L, USER_ID, 1024L);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).retainManualReconciliationState(
                    same(state),
                    eq("finalization_manual_reconciliation_required"),
                    eq(7 * 24 * 60 * 60L));
            assertEquals(3, state.getCleanupRetryCount());
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
            verify(fileService, never()).attestDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyList(), anyString(), anyString());
            verify(redisStateManager, never()).markCompleted(anyString(), anyString(), anyLong());
        }

        /**
         * 验证旧 Redis CHAIN_ATTESTING 但 durable NONE 时，定时恢复先回退 PREPARE 再安全完成同一会话。
         */
        @Test
        void oldRedisChainAttestingWithDurableNoneShouldResumeSafely() {
            long fileId = 9201L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            state.setDirectFinalizationStage("CHAIN_ATTESTING");
            state.setLastActivityTime(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(13));
            File preparedFile = preparedDirectFile(fileId, "direct.pdf");
            File storedFile = storedDirectFile(fileId, "direct.pdf");
            ChunkManifestView manifest = directManifest(fileId);

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedFile);
            when(fileService.getFinalizationRecoveryPhase(USER_ID, fileId))
                    .thenReturn(FileService.FinalizationRecoveryPhase.NONE);
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID)))
                    .thenThrow(new IllegalStateException("stopped before durable claim"))
                    .thenReturn(new StoreFileResponse("tx-1", "file-hash"));
            when(fileService.persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID)))
                    .thenReturn(storedFile);
            when(chunkManifestService.calculateManifestHash(any())).thenReturn("manifest-hash");
            when(chunkManifestService.findActiveManifest(USER_ID, fileId)).thenReturn(Optional.empty());
            when(chunkManifestService.saveManifest(eq(USER_ID), eq(fileId), any())).thenReturn(manifest);

            fileUploadService.cleanupExpiredUploadSessions();
            fileUploadService.cleanupExpiredUploadSessions();

            assertEquals("MANIFEST_STORED", state.getDirectFinalizationStage());
            verify(fileService, times(2)).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID));
            verify(fileService, times(1)).persistDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), eq(1024L), anyString(),
                    eq(new StoreFileResponse("tx-1", "file-hash")), eq(CLIENT_ID));
            verify(chunkManifestService, times(1)).saveManifest(eq(USER_ID), eq(fileId), any());
            verify(redisStateManager).markCompleted(CLIENT_ID, SUID, 300);
            verify(redisStateManager, never()).retainManualReconciliationState(
                    any(), anyString(), anyLong());
            verify(fileRemoteClient, never()).completeDirectMultipartUpload(any());
        }

        /**
         * 验证定时恢复首次进入链调用后响应不确定时立即转人工，不把旧快照写回并等待下一轮重放。
         */
        @Test
        void chainFailureDuringRecoveryShouldImmediatelyFailClosedToManual() {
            long fileId = 9202L;
            FileUploadState state = directUploadState();
            checkpointDirectPrepare(state, fileId);
            state.setLastActivityTime(System.currentTimeMillis() - TimeUnit.HOURS.toMillis(13));

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(CLIENT_ID));
            when(redisStateManager.getState(CLIENT_ID)).thenReturn(state);
            when(redisStateManager.isSessionPaused(CLIENT_ID)).thenReturn(false);
            when(redissonClient.getLock("distributed:lock:upload:quota:complete:tenant:77"))
                    .thenReturn(quotaLock);
            when(quotaLock.isHeldByCurrentThread()).thenReturn(true);
            when(fileService.getById(fileId)).thenReturn(preparedDirectFile(fileId, "direct.pdf"));
            when(fileService.attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID)))
                    .thenThrow(new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "响应不确定"));
            when(fileService.getFinalizationRecoveryPhase(USER_ID, fileId))
                    .thenReturn(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING);
            doAnswer(invocation -> {
                state.setStatus("finalization_manual_reconciliation_required");
                return null;
            }).when(redisStateManager).retainManualReconciliationState(
                    same(state), eq("finalization_manual_reconciliation_required"), anyLong());

            fileUploadService.cleanupExpiredUploadSessions();

            assertEquals("PREPARE_STORED", state.getDirectFinalizationStage());
            verify(redisStateManager).retainManualReconciliationState(
                    same(state),
                    eq("finalization_manual_reconciliation_required"),
                    eq(7 * 24 * 60 * 60L));
            verify(fileService, times(1)).attestDirectUploadedFile(
                    eq(USER_ID), eq(fileId), eq("direct.pdf"), anyList(), anyString(), eq(CLIENT_ID));
            verify(fileService, never()).persistDirectUploadedFile(
                    anyLong(), anyLong(), anyString(), anyLong(), anyString(), any(), anyString());
            verify(redisStateManager, never()).updateState(argThat(updated ->
                    "finalization_recovery_pending".equals(updated.getStatus())));
        }

        @Test
        @DisplayName("should handle missing state session by clearing only scheduler indexes")
        void shouldHandleMissingStateSession() {
            String clientId = "missing_state_1";

            when(redisStateManager.getAllActiveSessionIds()).thenReturn(java.util.Set.of(clientId));
            when(redisStateManager.getState(clientId)).thenReturn(null);

            fileUploadService.cleanupExpiredUploadSessions();

            verify(redisStateManager).clearSessionIndexes(clientId);
            verify(redisStateManager, never()).removeSession(eq(clientId), anyString());
            verify(redisStateManager, never()).removePausedSession(clientId);
        }
    }

    @Nested
    @DisplayName("Path Traversal Validation")
    class PathTraversalValidation {

        @Test
        @DisplayName("should reject path traversal in clientId during startUpload")
        void shouldRejectPathTraversalInClientId() {
            String traversalClientId = "../../malicious";

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileUploadService.startUpload(
                            USER_ID, "test.pdf", 1024 * 1024, "application/pdf",
                            traversalClientId, 256 * 1024, 4));

            assertEquals(ResultEnum.PARAM_IS_INVALID, ex.getResultEnum());
        }

        @Test
        @DisplayName("should reject path traversal in getProcessedSessionDir")
        void shouldRejectPathTraversalInProcessedSessionDir() throws Exception {
            Method method = FileUploadServiceImpl.class.getDeclaredMethod(
                    "getProcessedSessionDir", String.class, String.class);
            method.setAccessible(true);

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class, () ->
                    method.invoke(fileUploadService, SUID, "../../malicious"));

            assertInstanceOf(GeneralException.class, thrown.getCause());
            assertEquals(ResultEnum.PARAM_IS_INVALID,
                    ((GeneralException) thrown.getCause()).getResultEnum());
        }
    }
}
