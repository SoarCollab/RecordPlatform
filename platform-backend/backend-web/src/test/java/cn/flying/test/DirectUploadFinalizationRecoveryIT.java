package cn.flying.test;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.vo.file.DirectUploadCompletePartRequest;
import cn.flying.dao.vo.file.DirectUploadCompleteRequest;
import cn.flying.dao.vo.file.DirectUploadCompleteVO;
import cn.flying.dao.vo.file.DirectUploadPartRequest;
import cn.flying.dao.vo.file.DirectUploadSessionRequest;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CompleteDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CreateDirectMultipartUploadRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.CompleteDirectMultipartUploadResponse;
import cn.flying.platformapi.response.CreateDirectMultipartUploadResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.DirectMultipartUploadPartUrl;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.Container;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通过真实 MySQL 8、Redis 和生产 completeDirectUpload 验证直传最终化崩溃窗口恢复。
 */
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Direct upload durable finalization recovery integration tests")
class DirectUploadFinalizationRecoveryIT extends BaseIntegrationTest {

    private static final long TENANT_ID = 930_001L;
    private static final long USER_ID = 930_002L;
    private static final String HASH = "sha256:" + "a".repeat(64);
    private static final String CHECKSUM_ALGORITHM = "SHA-256";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_MANUAL = "finalization_manual_reconciliation_required";
    private static final String STAGE_CHAIN_ATTESTING = "CHAIN_ATTESTING";
    private static final String STAGE_CHAIN_ATTESTED = "CHAIN_ATTESTED";
    private static final String STAGE_PREPARE_STORED = "PREPARE_STORED";
    private static final String STAGE_FILE_STORED = "FILE_STORED";
    private static final String STAGE_MANIFEST_STORED = "MANIFEST_STORED";
    private static final String SESSION_KEY_PREFIX = "file:upload:session:";
    private static final String ACTIVE_SESSIONS_KEY = "file:upload:active_sessions";
    private static final String PAUSED_SESSIONS_KEY = "file:upload:paused_sessions";
    private static final String PAUSED_AT_KEY_PREFIX = "file:upload:paused_at:";
    private static final String ACTIVITY_AT_KEY_PREFIX = "file:upload:activity_at:";
    private static final String UPLOADED_CHUNKS_KEY_PREFIX = "file:upload:uploaded_chunks:";
    private static final long COMPLETED_TTL_SECONDS = 300L;
    private static final long MANUAL_TTL_SECONDS = 7L * 24 * 60 * 60;
    private static final String COUNT_SUCCESS_TRIGGER = "p2_it_count_file_success";
    private static final String COUNT_SUCCESS_TABLE = "p2_it_file_success_transition";
    private static final String CLAIM_FAILURE_TRIGGER = "p2_it_reject_initial_claim";
    private static final String CLAIMED_FAILURE_TRIGGER = "p2_it_reject_claimed_transition";
    private static final String SUCCESS_FAILURE_TRIGGER = "p2_it_reject_file_success";
    private static final String MANIFEST_FAILURE_TRIGGER = "p2_it_reject_manifest_insert";
    private static final String MANIFEST_DELAY_TRIGGER = "p2_it_delay_manifest_insert";

    @Resource
    private FileUploadService fileUploadService;

    @Resource
    private FileService fileService;

    @Resource
    private FileUploadRedisStateManager redisStateManager;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private StringRedisTemplate redisTemplate;

    private final List<String> createdClientIds = new ArrayList<>();

    /**
     * 重置真实持久化故障开关，并创建 SUCCESS 状态转换计数器。
     */
    @BeforeEach
    void prepareRecoveryScenario() {
        TenantContext.clear();
        restoreRedisSetPermission();
        dropFailureTriggers();
        cleanTenantRows();
        installSuccessTransitionCounter();
        createdClientIds.clear();
        reset(fileRemoteClient);
    }

    /**
     * 恢复 Redis 权限、清理测试会话、触发器与真实数据库记录。
     */
    @AfterEach
    void cleanRecoveryScenario() {
        restoreRedisSetPermission();
        dropFailureTriggers();
        for (String clientId : createdClientIds) {
            try {
                FileUploadState state = redisStateManager.getState(clientId);
                redisStateManager.removeSession(clientId, state == null ? null : state.getSuid());
            } catch (RuntimeException ignored) {
                redisTemplate.delete(SESSION_KEY_PREFIX + clientId);
                redisTemplate.delete(PAUSED_AT_KEY_PREFIX + clientId);
                redisTemplate.delete(ACTIVITY_AT_KEY_PREFIX + clientId);
                redisTemplate.opsForSet().remove(ACTIVE_SESSIONS_KEY, clientId);
                redisTemplate.opsForSet().remove(PAUSED_SESSIONS_KEY, clientId);
            }
        }
        cleanTenantRows();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + COUNT_SUCCESS_TRIGGER);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + COUNT_SUCCESS_TABLE);
        clearInvocations(fileRemoteClient);
        TenantContext.clear();
    }

    /**
     * 证明首次 CLAIMED 写入前的确定性数据库失败保留 NONE，解除故障后复用稳定 ID 完成。
     */
    @Test
    void shouldRetryBeforeInitialClaimWithoutAnyChainCall() {
        installInitialClaimFailureTrigger();
        DirectFixture fixture = startDirectFixture();

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);

        FileUploadState failedState = requireState(fixture.clientId());
        Long stablePreparedFileId = requirePreparedFileId(failedState);
        assertThat(finalizationPhase(stablePreparedFileId))
                .isEqualTo(FileService.FinalizationRecoveryPhase.NONE);
        assertThat(failedState.getDirectFinalizationStage()).isEqualTo(STAGE_PREPARE_STORED);
        assertThat(failedState.getStatus()).isNotEqualTo(STATUS_MANUAL);
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE_SESSIONS_KEY, fixture.clientId()))
                .isTrue();
        verify(fileRemoteClient, never()).storeFileOnChainOnce(any(StoreFileRequest.class));

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + CLAIM_FAILURE_TRIGGER);
        DirectUploadCompleteVO recovered = complete(fixture);

        assertCompleted(fixture, recovered, stablePreparedFileId);
        assertExternalWritesExactlyOnce();
    }

    /**
     * 证明 CLAIMED 到 ATTESTING 的确定性数据库失败不会调用链，解除故障后同一稳定 ID 可完成。
     */
    @Test
    void shouldRetryClaimedCheckpointBeforeAnyChainCall() {
        installClaimedTransitionFailureTrigger();
        DirectFixture fixture = startDirectFixture();

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);

        FileUploadState failedState = requireState(fixture.clientId());
        Long stablePreparedFileId = requirePreparedFileId(failedState);
        assertThat(finalizationPhase(stablePreparedFileId))
                .isEqualTo(FileService.FinalizationRecoveryPhase.CLAIMED);
        assertThat(failedState.getStatus()).isNotEqualTo(STATUS_MANUAL);
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE_SESSIONS_KEY, fixture.clientId()))
                .isTrue();
        verify(fileRemoteClient, never()).storeFileOnChainOnce(any(StoreFileRequest.class));

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + CLAIMED_FAILURE_TRIGGER);
        DirectUploadCompleteVO recovered = complete(fixture);

        assertCompleted(fixture, recovered, stablePreparedFileId);
        assertExternalWritesExactlyOnce();
    }

    /**
     * 证明链调用结果未知时保留 ATTESTING 和七天人工入口，任何重入都不得再次上链。
     */
    @Test
    void shouldRetainAttestingForManualReconciliationWithoutReplayingChain() {
        DirectFixture fixture = startDirectFixture();
        when(fileRemoteClient.storeFileOnChainOnce(any(StoreFileRequest.class)))
                .thenThrow(new IllegalStateException("simulated uncertain chain response"));

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);

        FileUploadState failedState = requireState(fixture.clientId());
        Long stablePreparedFileId = requirePreparedFileId(failedState);
        assertThat(finalizationPhase(stablePreparedFileId))
                .isEqualTo(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING);
        assertThat(failedState.getDirectFinalizationStage()).isEqualTo(STAGE_CHAIN_ATTESTING);
        assertThat(failedState.getStatus()).isEqualTo(STATUS_MANUAL);
        assertTtlNear(fixture.clientId(), MANUAL_TTL_SECONDS, 10L);
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE_SESSIONS_KEY, fixture.clientId()))
                .isFalse();

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);
        verify(fileRemoteClient, times(1)).storeFileOnChainOnce(any(StoreFileRequest.class));
        verify(fileRemoteClient, never()).storeFileChunk(any(byte[].class), anyString());
        assertThat(countFiles(FileUploadStatus.SUCCESS.getCode())).isZero();
        assertThat(countActiveManifests(stablePreparedFileId)).isZero();
        assertThat(countSagaRows(stablePreparedFileId)).isZero();
    }

    /**
     * 证明 ATTESTED 后 DB SUCCESS 写失败时，重入只消费 durable 链结果且不再次上链。
     */
    @Test
    void shouldResumeAttestedCheckpointWithoutRepeatingChain() {
        installSuccessWriteFailureTrigger();
        DirectFixture fixture = startDirectFixture();

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);

        FileUploadState failedState = requireState(fixture.clientId());
        Long stablePreparedFileId = requirePreparedFileId(failedState);
        assertThat(finalizationPhase(stablePreparedFileId))
                .isEqualTo(FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED);
        assertThat(failedState.getDirectFinalizationStage()).isEqualTo(STAGE_CHAIN_ATTESTED);
        assertThat(countFiles(FileUploadStatus.PREPARE.getCode())).isEqualTo(1);
        assertThat(countSuccessTransitions(stablePreparedFileId)).isZero();
        verify(fileRemoteClient, times(1)).storeFileOnChainOnce(any(StoreFileRequest.class));

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + SUCCESS_FAILURE_TRIGGER);
        DirectUploadCompleteVO recovered = complete(fixture);

        assertCompleted(fixture, recovered, stablePreparedFileId);
        assertExternalWritesExactlyOnce();
    }

    /**
     * 证明 DB SUCCESS 后 manifest 与 Redis SET 分别失败仍可连续重入并只发布一个 manifest。
     */
    @Test
    void shouldRecoverDbSuccessAfterManifestAndRedisFailures() {
        installManifestInsertFailureTrigger();
        DirectFixture fixture = startDirectFixture();

        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);

        FileUploadState manifestFailureState = requireState(fixture.clientId());
        Long stablePreparedFileId = requirePreparedFileId(manifestFailureState);
        assertThat(finalizationPhase(stablePreparedFileId))
                .isEqualTo(FileService.FinalizationRecoveryPhase.SUCCESS);
        assertThat(manifestFailureState.getDirectFinalizationStage()).isEqualTo(STAGE_FILE_STORED);
        assertThat(countActiveManifests(stablePreparedFileId)).isZero();
        assertThat(countSuccessTransitions(stablePreparedFileId)).isEqualTo(1);

        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MANIFEST_FAILURE_TRIGGER);
        installManifestInsertDelayTrigger();
        CompletableFuture<Container.ExecResult> redisFailureWatcher =
                denyRedisSetAfterNextFileStoredCheckpoint(fixture.clientId());
        assertThatThrownBy(() -> complete(fixture))
                .isInstanceOf(RuntimeException.class);
        awaitRedisSetFailure(redisFailureWatcher);
        assertThat(requireState(fixture.clientId()).getDirectFinalizationStage())
                .isEqualTo(STAGE_FILE_STORED);
        assertThat(countActiveManifests(stablePreparedFileId)).isEqualTo(1);
        assertThat(countManifestRows(stablePreparedFileId)).isEqualTo(1);

        restoreRedisSetPermission();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MANIFEST_DELAY_TRIGGER);
        DirectUploadCompleteVO recovered = complete(fixture);

        assertCompleted(fixture, recovered, stablePreparedFileId);
        assertThat(countManifestRows(stablePreparedFileId)).isEqualTo(1);
        assertExternalWritesExactlyOnce();
    }

    /**
     * 通过真实 Redis Lua 证明暂停续期与终态栅栏原子生效，损坏对象必须失败关闭。
     */
    @Test
    void shouldFenceLatePauseAfterTerminalStateWithRealRedis() {
        FileUploadState activeState = createRedisOnlyDirectState("active-pause-fence");
        String activeStateKey = SESSION_KEY_PREFIX + activeState.getClientId();
        String activePausedAtKey = PAUSED_AT_KEY_PREFIX + activeState.getClientId();
        String activeActivityAtKey = ACTIVITY_AT_KEY_PREFIX + activeState.getClientId();
        String activeUploadedChunksKey = UPLOADED_CHUNKS_KEY_PREFIX + activeState.getClientId();
        String originalStatePayload = redisTemplate.opsForValue().get(activeStateKey);
        redisTemplate.expire(activeStateKey, 60, TimeUnit.SECONDS);
        redisTemplate.opsForSet().add(activeUploadedChunksKey, "0");
        redisTemplate.expire(activeUploadedChunksKey, 60, TimeUnit.SECONDS);

        assertThat(redisStateManager.addPausedSession(activeState.getClientId()))
                .isEqualTo(FileUploadRedisStateManager.PauseTransitionResult.PAUSED);
        assertThat(redisTemplate.opsForSet()
                .isMember(PAUSED_SESSIONS_KEY, activeState.getClientId()))
                .isTrue();
        assertThat(redisTemplate.opsForValue().get(activePausedAtKey)).isNotBlank();
        assertThat(redisTemplate.opsForValue().get(activeActivityAtKey)).isNotBlank();
        assertThat(redisTemplate.getExpire(activeStateKey, TimeUnit.SECONDS)).isGreaterThan(86_300L);
        assertThat(redisTemplate.getExpire(activeUploadedChunksKey, TimeUnit.SECONDS))
                .isGreaterThan(86_300L);
        assertThat(redisTemplate.opsForValue().get(activeStateKey)).isEqualTo(originalStatePayload);

        redisStateManager.markCompleted(
                activeState.getClientId(), activeState.getSuid(), COMPLETED_TTL_SECONDS);
        assertThat(redisTemplate.opsForSet()
                .isMember(PAUSED_SESSIONS_KEY, activeState.getClientId()))
                .isFalse();
        assertThat(redisTemplate.hasKey(activePausedAtKey)).isFalse();
        assertThat(redisTemplate.hasKey(activeActivityAtKey)).isFalse();

        FileUploadState resumeRaceState = createRedisOnlyDirectState("resume-pause-pair");
        String resumeRacePausedAtKey = PAUSED_AT_KEY_PREFIX + resumeRaceState.getClientId();
        assertThat(redisStateManager.addPausedSession(resumeRaceState.getClientId()))
                .isEqualTo(FileUploadRedisStateManager.PauseTransitionResult.PAUSED);
        assertThat(redisStateManager.removePausedSession(resumeRaceState.getClientId())).isTrue();
        assertThat(redisTemplate.opsForSet()
                .isMember(PAUSED_SESSIONS_KEY, resumeRaceState.getClientId()))
                .isFalse();
        assertThat(redisTemplate.hasKey(resumeRacePausedAtKey)).isFalse();
        assertThat(redisStateManager.addPausedSession(resumeRaceState.getClientId()))
                .isEqualTo(FileUploadRedisStateManager.PauseTransitionResult.PAUSED);
        assertThat(redisTemplate.opsForSet()
                .isMember(PAUSED_SESSIONS_KEY, resumeRaceState.getClientId()))
                .isTrue();
        assertThat(redisTemplate.hasKey(resumeRacePausedAtKey)).isTrue();

        FileUploadState completedState = createRedisOnlyDirectState("completed-fence");
        redisStateManager.markCompleted(
                completedState.getClientId(), completedState.getSuid(), COMPLETED_TTL_SECONDS);
        redisStateManager.updateLastActivityTime(completedState.getClientId());

        FileUploadState manualState = createRedisOnlyDirectState("manual-fence");
        redisStateManager.retainManualReconciliationState(
                manualState, STATUS_MANUAL, MANUAL_TTL_SECONDS);

        assertThat(redisStateManager.addPausedSession(completedState.getClientId()))
                .isEqualTo(FileUploadRedisStateManager.PauseTransitionResult.TERMINAL);
        assertThat(redisStateManager.addPausedSession(manualState.getClientId()))
                .isEqualTo(FileUploadRedisStateManager.PauseTransitionResult.TERMINAL);
        assertThat(redisTemplate.opsForSet()
                .isMember(PAUSED_SESSIONS_KEY, completedState.getClientId()))
                .isFalse();
        assertThat(requireState(completedState.getClientId()).getStatus()).isEqualTo(STATUS_COMPLETED);
        assertThat(redisTemplate.getExpire(
                SESSION_KEY_PREFIX + completedState.getClientId(), TimeUnit.SECONDS))
                .isLessThanOrEqualTo(COMPLETED_TTL_SECONDS);
        assertThat(redisTemplate.opsForSet()
                .isMember(PAUSED_SESSIONS_KEY, manualState.getClientId()))
                .isFalse();

        List<String> damagedSessionIds = List.of(
                "damaged-array-" + UUID.randomUUID(),
                "damaged-empty-" + UUID.randomUUID(),
                "damaged-null-status-" + UUID.randomUUID(),
                "damaged-number-status-" + UUID.randomUUID(),
                "damaged-client-mismatch-" + UUID.randomUUID());
        List<String> damagedPayloads = List.of(
                "[]",
                "{}",
                "{\"clientId\":\"" + damagedSessionIds.get(2) + "\",\"status\":null}",
                "{\"clientId\":\"" + damagedSessionIds.get(3) + "\",\"status\":1}",
                "{\"clientId\":\"different-session\",\"status\":\"uploading\"}");
        for (int index = 0; index < damagedSessionIds.size(); index++) {
            String damagedSessionId = damagedSessionIds.get(index);
            createdClientIds.add(damagedSessionId);
            redisTemplate.opsForValue().set(
                    SESSION_KEY_PREFIX + damagedSessionId, damagedPayloads.get(index));

            assertThatThrownBy(() -> redisStateManager.addPausedSession(damagedSessionId))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JSON 主状态损坏");
            assertThat(redisTemplate.opsForSet()
                    .isMember(PAUSED_SESSIONS_KEY, damagedSessionId))
                    .isFalse();
            assertThat(redisTemplate.hasKey(PAUSED_AT_KEY_PREFIX + damagedSessionId)).isFalse();
        }
    }

    /**
     * 创建仅用于 Redis 状态迁移验证的直传会话。
     *
     * @param prefix 会话标识前缀
     * @return 已持久化到真实 Redis 的上传状态
     */
    private FileUploadState createRedisOnlyDirectState(String prefix) {
        String clientId = prefix + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        FileUploadState state = new FileUploadState(
                USER_ID,
                prefix + ".pdf",
                4L,
                "application/pdf",
                clientId,
                4,
                1);
        state.setTenantId(TENANT_ID);
        state.setSuid("encoded-user");
        state.setDirectUpload(true);

        redisStateManager.saveNewState(state, state.getSuid());
        createdClientIds.add(clientId);
        return state;
    }

    /**
     * 创建真实 Redis 直传会话，并为 storage 与 blockchain 外部边界准备确定性响应。
     *
     * @return 本轮直传请求与预期证据
     */
    private DirectFixture startDirectFixture() {
        String clientId = "p2it-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String transactionHash = "tx-" + clientId;
        String chainFileHash = "chain-file-" + clientId;
        String stagingEtag = "staging-etag-" + clientId;
        String finalEtag = "final-etag-" + clientId;
        long size = 4L;

        DirectUploadPartRequest part = new DirectUploadPartRequest();
        part.setIndex(0);
        part.setSize(size);
        part.setPlainHash(HASH);
        part.setCipherHash(HASH);
        part.setChecksumAlgorithm(CHECKSUM_ALGORITHM);

        DirectUploadSessionRequest sessionRequest = new DirectUploadSessionRequest();
        sessionRequest.setClientId(clientId);
        sessionRequest.setFileName("recovery.pdf");
        sessionRequest.setFileSize(size);
        sessionRequest.setContentType("application/pdf");
        sessionRequest.setChunkSize(Math.toIntExact(size));
        sessionRequest.setTotalChunks(1);
        sessionRequest.setParts(List.of(part));

        DirectUploadCompletePartRequest completePart = new DirectUploadCompletePartRequest();
        completePart.setIndex(0);
        completePart.setETag(stagingEtag);
        DirectUploadCompleteRequest completeRequest = new DirectUploadCompleteRequest();
        completeRequest.setParts(List.of(completePart));

        String storagePath = "storage/tenant/" + TENANT_ID + "/chunk/" + HASH;
        CreateDirectMultipartUploadResponse createResponse = new CreateDirectMultipartUploadResponse(
                clientId,
                List.of(new DirectMultipartUploadPartUrl(
                        0,
                        "https://object.example/" + clientId + "/part-0",
                        Instant.now().plusSeconds(3600).getEpochSecond(),
                        storagePath,
                        "tenant/" + TENANT_ID + "/staging/direct-upload/" + clientId + "/part-0",
                        "tenant/" + TENANT_ID + "/" + HASH,
                        "node-a",
                        size)));
        CompleteDirectMultipartUploadResponse completeResponse = new CompleteDirectMultipartUploadResponse(
                clientId,
                HASH,
                List.of(new DirectMultipartCompletedPartVO(
                        0,
                        storagePath,
                        size,
                        finalEtag,
                        HASH,
                        HASH,
                        CHECKSUM_ALGORITHM)));

        when(fileRemoteClient.createDirectMultipartUpload(any(CreateDirectMultipartUploadRequest.class)))
                .thenReturn(Result.success(createResponse));
        when(fileRemoteClient.completeDirectMultipartUpload(any(CompleteDirectMultipartUploadRequest.class)))
                .thenReturn(Result.success(completeResponse));
        when(fileRemoteClient.storeFileOnChainOnce(any(StoreFileRequest.class)))
                .thenReturn(Result.success(new StoreFileResponse(transactionHash, chainFileHash)));

        createdClientIds.add(clientId);
        asTenant(() -> fileUploadService.startDirectUpload(USER_ID, sessionRequest));
        assertThat(requireState(clientId).getDirectFinalizationStage()).isEqualTo("SESSION_CREATED");
        return new DirectFixture(
                clientId,
                completeRequest,
                transactionHash,
                chainFileHash);
    }

    /**
     * 在真实租户上下文中调用生产直传完成入口。
     *
     * @param fixture 直传测试证据
     * @return 生产完成响应
     */
    private DirectUploadCompleteVO complete(DirectFixture fixture) {
        return asTenant(() -> fileUploadService.completeDirectUpload(
                USER_ID, fixture.clientId(), fixture.completeRequest()));
    }

    /**
     * 验证完成后的稳定主键、单一 SUCCESS、manifest、Redis TTL 和零 Saga 副作用。
     */
    private void assertCompleted(
            DirectFixture fixture,
            DirectUploadCompleteVO completed,
            Long stablePreparedFileId
    ) {
        assertThat(completed.getClientId()).isEqualTo(fixture.clientId());
        assertThat(completed.getFileId()).isEqualTo(IdUtils.toExternalId(stablePreparedFileId));
        assertThat(completed.getFileHash()).isEqualTo(fixture.chainFileHash());
        assertThat(completed.getTransactionHash()).isEqualTo(fixture.transactionHash());
        assertThat(completed.getManifestHash()).isNotBlank();
        assertThat(completed.getStatus()).isEqualTo(STATUS_COMPLETED);

        Map<String, Object> fileRow = jdbcTemplate.queryForMap(
                """
                SELECT id, status, file_hash, transaction_hash, content_hash
                FROM file
                WHERE tenant_id = ? AND uid = ?
                """,
                TENANT_ID,
                USER_ID);
        assertThat(((Number) fileRow.get("id")).longValue()).isEqualTo(stablePreparedFileId);
        assertThat(((Number) fileRow.get("status")).intValue())
                .isEqualTo(FileUploadStatus.SUCCESS.getCode());
        assertThat(fileRow.get("file_hash")).isEqualTo(fixture.chainFileHash());
        assertThat(fileRow.get("transaction_hash")).isEqualTo(fixture.transactionHash());
        assertThat(fileRow.get("content_hash")).isEqualTo(HASH);
        assertThat(finalizationPhase(stablePreparedFileId))
                .isEqualTo(FileService.FinalizationRecoveryPhase.SUCCESS);
        assertThat(countFiles(FileUploadStatus.SUCCESS.getCode())).isEqualTo(1);
        assertThat(countSuccessTransitions(stablePreparedFileId)).isEqualTo(1);
        assertThat(countActiveManifests(stablePreparedFileId)).isEqualTo(1);
        assertThat(countManifestItems(stablePreparedFileId)).isEqualTo(1);
        assertThat(countSagaRows(stablePreparedFileId)).isZero();

        FileUploadState completedState = requireState(fixture.clientId());
        assertThat(completedState.getPreparedFileId()).isEqualTo(stablePreparedFileId);
        assertThat(completedState.getDirectFileId()).isEqualTo(stablePreparedFileId);
        assertThat(completedState.getDirectFinalizationStage()).isEqualTo(STAGE_MANIFEST_STORED);
        assertThat(completedState.getStatus()).isEqualTo(STATUS_COMPLETED);
        assertThat(completedState.getDirectManifestHash()).isEqualTo(completed.getManifestHash());
        assertTtlNear(fixture.clientId(), COMPLETED_TTL_SECONDS, 5L);
        assertThat(redisTemplate.opsForSet().isMember(ACTIVE_SESSIONS_KEY, fixture.clientId()))
                .isFalse();
    }

    /**
     * 验证 storage、chain 各只调用一次，legacy Saga 字节上传从未被触发。
     */
    private void assertExternalWritesExactlyOnce() {
        verify(fileRemoteClient, times(1))
                .createDirectMultipartUpload(any(CreateDirectMultipartUploadRequest.class));
        verify(fileRemoteClient, times(1))
                .completeDirectMultipartUpload(any(CompleteDirectMultipartUploadRequest.class));
        verify(fileRemoteClient, times(1)).storeFileOnChainOnce(any(StoreFileRequest.class));
        verify(fileRemoteClient, never()).storeFileChunk(any(byte[].class), anyString());
    }

    /**
     * 在隔离的真实租户上下文中执行带返回值操作，并验证 ThreadLocal 已恢复。
     */
    private <T> T asTenant(Supplier<T> action) {
        assertThat(TenantContext.isSet()).isFalse();
        T result = TenantContext.callWithTenant(TENANT_ID, action);
        assertThat(TenantContext.isSet()).isFalse();
        return result;
    }

    /**
     * 读取真实 Redis 中的上传状态并在缺失时立即失败。
     */
    private FileUploadState requireState(String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        assertThat(state).as("Redis upload state for %s", clientId).isNotNull();
        return state;
    }

    /**
     * 读取已分配的稳定 PREPARE 主键并验证其为正数。
     */
    private Long requirePreparedFileId(FileUploadState state) {
        assertThat(state.getPreparedFileId()).isPositive();
        return state.getPreparedFileId();
    }

    /**
     * 通过生产 FileService 只读解析真实 DB 最终化阶段。
     */
    private FileService.FinalizationRecoveryPhase finalizationPhase(Long fileId) {
        return asTenant(() -> fileService.getFinalizationRecoveryPhase(USER_ID, fileId));
    }

    /**
     * 断言真实 Redis 主状态 TTL 接近期望值且没有被缩短为无界或即时过期。
     */
    private void assertTtlNear(String clientId, long expectedSeconds, long toleranceSeconds) {
        Long ttl = redisTemplate.getExpire(SESSION_KEY_PREFIX + clientId, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(expectedSeconds - toleranceSeconds, expectedSeconds + 1L);
    }

    /**
     * 查询本租户指定状态的文件行数。
     */
    private int countFiles(int status) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file WHERE tenant_id = ? AND uid = ? AND status = ?",
                Integer.class,
                TENANT_ID,
                USER_ID,
                status);
        return Objects.requireNonNullElse(count, 0);
    }

    /**
     * 查询测试触发器记录的 PREPARE 到 SUCCESS 真实转换次数。
     */
    private int countSuccessTransitions(Long fileId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + COUNT_SUCCESS_TABLE + " WHERE file_id = ?",
                Integer.class,
                fileId);
        return Objects.requireNonNullElse(count, 0);
    }

    /**
     * 查询当前文件的 active manifest 数量。
     */
    private int countActiveManifests(Long fileId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM file_chunk_manifest
                WHERE tenant_id = ? AND file_id = ? AND status = 'ACTIVE' AND deleted = 0
                """,
                Integer.class,
                TENANT_ID,
                fileId);
        return Objects.requireNonNullElse(count, 0);
    }

    /**
     * 查询当前文件的全部 manifest 行数，用于证明 Redis 检查点失败后的重入没有新建替代行。
     */
    private int countManifestRows(Long fileId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_chunk_manifest WHERE tenant_id = ? AND file_id = ?",
                Integer.class,
                TENANT_ID,
                fileId);
        return Objects.requireNonNullElse(count, 0);
    }

    /**
     * 查询当前文件 active manifest 的真实分片行数。
     */
    private int countManifestItems(Long fileId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM file_chunk_manifest_item item
                JOIN file_chunk_manifest manifest ON manifest.id = item.manifest_id
                WHERE manifest.tenant_id = ?
                  AND manifest.file_id = ?
                  AND manifest.status = 'ACTIVE'
                  AND manifest.deleted = 0
                  AND item.deleted = 0
                """,
                Integer.class,
                TENANT_ID,
                fileId);
        return Objects.requireNonNullElse(count, 0);
    }

    /**
     * 查询直传稳定文件是否错误进入 legacy Saga 表。
     */
    private int countSagaRows(Long fileId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM file_saga WHERE tenant_id = ? AND file_id = ?",
                Integer.class,
                TENANT_ID,
                fileId);
        return Objects.requireNonNullElse(count, 0);
    }

    /**
     * 安装只记录实际状态从非 SUCCESS 变为 SUCCESS 的测试审计触发器。
     */
    private void installSuccessTransitionCounter() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + COUNT_SUCCESS_TRIGGER);
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + COUNT_SUCCESS_TABLE);
        jdbcTemplate.execute("CREATE TABLE " + COUNT_SUCCESS_TABLE
                + " (file_id BIGINT NOT NULL, recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        jdbcTemplate.execute("""
                CREATE TRIGGER p2_it_count_file_success
                AFTER UPDATE ON file
                FOR EACH ROW
                BEGIN
                    IF NEW.status = 1 AND OLD.status <> 1 THEN
                        INSERT INTO p2_it_file_success_transition(file_id) VALUES (NEW.id);
                    END IF;
                END
                """);
    }

    /**
     * 安装 clean PREPARE 到首次 CLAIMED 写入失败触发器。
     */
    private void installInitialClaimFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + CLAIM_FAILURE_TRIGGER);
        jdbcTemplate.execute("""
                CREATE TRIGGER p2_it_reject_initial_claim
                BEFORE UPDATE ON file
                FOR EACH ROW
                BEGIN
                    IF JSON_EXTRACT(OLD.file_param, '$._finalizationClaim') IS NULL
                       AND JSON_UNQUOTE(JSON_EXTRACT(NEW.file_param, '$._finalizationClaim.phase')) = 'CLAIMED' THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = 'simulated initial CLAIMED persistence failure';
                    END IF;
                END
                """);
    }

    /**
     * 安装 CLAIMED 到 CHAIN_ATTESTING 精确转换失败触发器。
     */
    private void installClaimedTransitionFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + CLAIMED_FAILURE_TRIGGER);
        jdbcTemplate.execute("""
                CREATE TRIGGER p2_it_reject_claimed_transition
                BEFORE UPDATE ON file
                FOR EACH ROW
                BEGIN
                    IF JSON_UNQUOTE(JSON_EXTRACT(OLD.file_param, '$._finalizationClaim.phase')) = 'CLAIMED'
                       AND JSON_UNQUOTE(JSON_EXTRACT(NEW.file_param, '$._finalizationClaim.phase')) = 'CHAIN_ATTESTING' THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = 'simulated CLAIMED transition persistence failure';
                    END IF;
                END
                """);
    }

    /**
     * 安装 ATTESTED 到 SUCCESS 数据库写失败触发器。
     */
    private void installSuccessWriteFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + SUCCESS_FAILURE_TRIGGER);
        jdbcTemplate.execute("""
                CREATE TRIGGER p2_it_reject_file_success
                BEFORE UPDATE ON file
                FOR EACH ROW
                BEGIN
                    IF NEW.status = 1 AND OLD.status <> 1 THEN
                        SIGNAL SQLSTATE '45000'
                            SET MESSAGE_TEXT = 'simulated SUCCESS persistence failure';
                    END IF;
                END
                """);
    }

    /**
     * 安装真实 manifest INSERT 失败触发器。
     */
    private void installManifestInsertFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MANIFEST_FAILURE_TRIGGER);
        jdbcTemplate.execute("""
                CREATE TRIGGER p2_it_reject_manifest_insert
                BEFORE INSERT ON file_chunk_manifest
                FOR EACH ROW
                SIGNAL SQLSTATE '45000'
                    SET MESSAGE_TEXT = 'simulated manifest persistence failure'
                """);
    }

    /**
     * 在 manifest INSERT 内短暂停顿，确保真实 Redis ACL 能在提交后检查点写入前切换。
     */
    private void installManifestInsertDelayTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MANIFEST_DELAY_TRIGGER);
        jdbcTemplate.execute("""
                CREATE TRIGGER p2_it_delay_manifest_insert
                BEFORE INSERT ON file_chunk_manifest
                FOR EACH ROW
                SET @p2_it_manifest_delay = SLEEP(1)
                """);
    }

    /**
     * 删除每个故障窗口使用的临时数据库触发器。
     */
    private void dropFailureTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + CLAIM_FAILURE_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + CLAIMED_FAILURE_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + SUCCESS_FAILURE_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MANIFEST_FAILURE_TRIGGER);
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS " + MANIFEST_DELAY_TRIGGER);
    }

    /**
     * 通过 Redis ACL 禁止真实 SET 命令，精确制造关键状态写失败。
     */
    private void denyRedisSetPermission() {
        executeRedisAcl("-set");
    }

    /**
     * 监听该会话下一次 FILE_STORED 实写，随后用真实 ACL 拒绝 manifest 后续检查点 SET。
     */
    private CompletableFuture<Container.ExecResult> denyRedisSetAfterNextFileStoredCheckpoint(
            String clientId
    ) {
        String monitorPattern = SESSION_KEY_PREFIX + clientId + ".*" + STAGE_FILE_STORED;
        String monitorCommand = "timeout 15 redis-cli MONITOR"
                + " | grep -m 1 '" + monitorPattern + "' >/dev/null"
                + " && redis-cli ACL SETUSER default -set";
        CompletableFuture<Container.ExecResult> watcher = CompletableFuture.supplyAsync(() -> {
            try {
                return redis.execInContainer("sh", "-c", monitorCommand);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new CompletionException(exception);
            } catch (Exception exception) {
                throw new CompletionException(exception);
            }
        });
        awaitRedisMonitorReady();
        return watcher;
    }

    /**
     * 等待 Redis MONITOR 客户端真正订阅，避免遗漏目标 FILE_STORED 写入。
     */
    private void awaitRedisMonitorReady() {
        for (int attempt = 0; attempt < 100; attempt++) {
            try {
                Container.ExecResult result = redis.execInContainer("redis-cli", "CLIENT", "LIST");
                if (result.getExitCode() == 0 && result.getStdout().contains("cmd=monitor")) {
                    return;
                }
                Thread.sleep(25L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Redis MONITOR 时被中断", exception);
            } catch (Exception exception) {
                throw new IllegalStateException("无法确认 Redis MONITOR 状态", exception);
            }
        }
        throw new IllegalStateException("Redis MONITOR 未在期限内就绪");
    }

    /**
     * 等待 ACL 故障注入完成并校验 Redis 明确返回 OK。
     */
    private void awaitRedisSetFailure(CompletableFuture<Container.ExecResult> watcher) {
        try {
            Container.ExecResult result = watcher.get(10, TimeUnit.SECONDS);
            assertThat(result.getExitCode()).isZero();
            assertThat(result.getStdout()).contains("OK");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 Redis SET 故障注入时被中断", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Redis SET 故障注入未完成", exception);
        }
    }

    /**
     * 恢复 Redis SET 权限，保证后续重入和清理使用同一真实实例。
     */
    private void restoreRedisSetPermission() {
        if (!redis.isRunning()) {
            return;
        }
        executeRedisAcl("+set");
    }

    /**
     * 在 Testcontainers Redis 内执行 ACL 变更并校验命令成功。
     */
    private void executeRedisAcl(String permission) {
        try {
            var result = redis.execInContainer(
                    "redis-cli", "ACL", "SETUSER", "default", permission);
            if (result.getExitCode() != 0 || !result.getStdout().contains("OK")) {
                throw new IllegalStateException(
                        "Redis ACL change failed: " + result.getStdout() + result.getStderr());
            }
        } catch (Exception exception) {
            throw new IllegalStateException("无法切换 Redis SET 权限: " + permission, exception);
        }
    }

    /**
     * 按外键依赖顺序清理当前测试租户写入的真实持久化数据。
     */
    private void cleanTenantRows() {
        jdbcTemplate.update("DELETE FROM file_chunk_manifest_item WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_chunk_manifest WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_key_envelope WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file_saga WHERE tenant_id = ?", TENANT_ID);
        jdbcTemplate.update("DELETE FROM file WHERE tenant_id = ?", TENANT_ID);
    }

    /**
     * 单次直传的稳定外部响应与完成请求。
     */
    private record DirectFixture(
            String clientId,
            DirectUploadCompleteRequest completeRequest,
            String transactionHash,
            String chainFileHash
    ) {
    }

}
