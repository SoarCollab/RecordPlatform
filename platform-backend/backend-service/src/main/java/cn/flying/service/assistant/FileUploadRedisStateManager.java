package cn.flying.service.assistant;

import cn.flying.common.util.CacheUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.vo.file.FileUploadState;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文件上传状态Redis管理器
 * 负责FileUploadState的Redis存储、获取和更新
 */
@Component
@Slf4j
public class FileUploadRedisStateManager {

    /**
     * 暂停索引原子转换结果，由服务层映射为稳定业务错误。
     */
    public enum PauseTransitionResult {
        PAUSED,
        SESSION_NOT_FOUND,
        TERMINAL
    }

    // 键前缀和过期时间配置
    private static final String ACTIVE_UPLOADS_KEY = "file:upload:active_sessions";
    private static final String FILE_SUID_MAPPING_KEY = "file:upload:filename_suid_mapping";
    private static final String PAUSED_SESSIONS_KEY = "file:upload:paused_sessions";
    private static final String PAUSED_AT_KEY_PREFIX = "file:upload:paused_at:";
    private static final String ACTIVITY_AT_KEY_PREFIX = "file:upload:activity_at:";
    private static final String SESSION_KEY_PREFIX = "file:upload:session:";
    private static final String SESSION_UPLOADED_CHUNKS_KEY_PREFIX = "file:upload:uploaded_chunks:";
    private static final String SESSION_PROCESSED_CHUNKS_KEY_PREFIX = "file:upload:processed_chunks:";
    private static final String SESSION_CHUNK_HASHES_KEY_PREFIX = "file:upload:chunk_hashes:";
    private static final String SESSION_KEYS_KEY_PREFIX = "file:upload:chunk_keys:";
    private static final String SESSION_STATE_LOCK_KEY_PREFIX = "distributed:lock:upload:state:";
    private static final long SESSION_STATE_LOCK_WAIT_SECONDS = 5L;
    private static final long STATE_TTL_VERIFICATION_TOLERANCE_SECONDS = 2L;
    private static final Pattern CHUNK_HASH_FIELD_PATTERN = Pattern.compile("^chunk_[0-9]+$");
    private static final Pattern CHUNK_HASH_VALUE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FINALIZATION_RECOVERY_PENDING = "finalization_recovery_pending";
    private static final String STATUS_FINALIZATION_MANUAL_REQUIRED =
            "finalization_manual_reconciliation_required";
    private static final String STATUS_CLEANUP_MANUAL_REQUIRED = "cleanup_manual_required";
    private static final List<String> PAUSE_FORBIDDEN_STATUSES = List.of(
            STATUS_COMPLETED,
            STATUS_FINALIZATION_MANUAL_REQUIRED,
            STATUS_CLEANUP_MANUAL_REQUIRED);
    private static final Map<String, Integer> DIRECT_FINALIZATION_STAGE_RANK = Map.of(
            "SESSION_CREATED", 0,
            "STORAGE_COMPLETED", 1,
            "PREPARE_ID_ALLOCATED", 2,
            "PREPARE_STORED", 3,
            "CHAIN_ATTESTING", 4,
            "CHAIN_ATTESTED", 5,
            "FILE_STORED", 6,
            "MANIFEST_STORED", 7
    );

    // 默认过期时间: 24小时
    private static final long DEFAULT_SESSION_EXPIRE_SECONDS = 24 * 60 * 60;

    @Resource
    private CacheUtils cacheUtils;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 保存新创建的上传状态
     */
    public void saveNewState(FileUploadState state,String SUID) {
        String sessionId = state.getClientId();
        withSessionStateLock(sessionId, () -> {
            String stateKey = getSessionKey(sessionId);
            FileUploadState existing = cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class);
            if (existing != null) {
                throw new IllegalStateException("上传会话ID已存在，拒绝覆盖: " + sessionId);
            }
            String fileClientKey = state.isDirectUpload()
                    ? null
                    : buildFileClientKey(state.getTenantId(), state.getFileName(), SUID);
            try {
                saveStateAndVerify(stateKey, state, DEFAULT_SESSION_EXPIRE_SECONDS);
                addSetMemberAndReconcile(ACTIVE_UPLOADS_KEY, sessionId);
                if (fileClientKey != null) {
                    createResumeMappingWithoutOverwrite(fileClientKey, sessionId);
                }
            } catch (RuntimeException creationError) {
                rollbackNewSessionIfSafe(
                        stateKey, state, sessionId, fileClientKey, creationError);
                throw creationError;
            }

            log.info("Redis: 保存新的上传状态 sessionId={}, fileClientKey={}", sessionId, fileClientKey);
            return null;
        });
    }

    /**
     * 根据会话ID获取上传状态
     */
    public FileUploadState getState(String sessionId) {
        String stateKey = getSessionKey(sessionId);
        FileUploadState state = cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class);

        if (state != null) {
            // 更新分片集合等动态数据
            populateStateCollections(state);
            populateLatestActivityTime(state);
        }

        return state;
    }

    /**
     * 更新会话状态
     */
    public void updateState(FileUploadState state) {
        if (state == null || state.getClientId() == null || state.getClientId().isBlank()) {
            throw new IllegalArgumentException("上传状态和会话ID不能为空");
        }
        String sessionId = state.getClientId();
        withSessionStateLock(sessionId, () -> {
            String stateKey = getSessionKey(sessionId);
            FileUploadState current = cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class);
            if (current == null) {
                clearSessionIndexes(sessionId);
                throw new IllegalStateException("上传会话已过期，拒绝旧快照重建状态: " + sessionId);
            }
            if (isProtectedTerminalStatus(current.getStatus())) {
                log.debug("忽略对上传终态的旧快照写入: sessionId={}, status={}",
                        sessionId, current.getStatus());
                return null;
            }

            FileUploadState merged = mergeMonotonicState(current, state);
            saveStateAndVerify(stateKey, merged, DEFAULT_SESSION_EXPIRE_SECONDS);

            String uploadedChunksKey = getUploadedChunksKey(sessionId);
            writeChunkSetAndVerify(
                    uploadedChunksKey,
                    merged.getUploadedChunks(),
                    DEFAULT_SESSION_EXPIRE_SECONDS);

            String processedChunksKey = getProcessedChunksKey(sessionId);
            writeChunkSetAndVerify(
                    processedChunksKey,
                    merged.getProcessedChunks(),
                    DEFAULT_SESSION_EXPIRE_SECONDS);

            String chunkHashesKey = getChunkHashesKey(sessionId);
            writeChunkHashesAndVerify(
                    chunkHashesKey, merged.getChunkHashes(), DEFAULT_SESSION_EXPIRE_SECONDS);

            String keysKey = getKeysKey(sessionId);
            Map<String, byte[]> keysMap = merged.getKeys().entrySet().stream()
                    .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue));
            writeChunkKeysAndVerify(keysKey, keysMap, DEFAULT_SESSION_EXPIRE_SECONDS);
            return null;
        });
    }

    /**
     * 添加处理完成的分片
     */
    public void addProcessedChunk(String sessionId, int chunkNumber) {
        withSessionStateLock(sessionId, () -> {
            FileUploadState state = requireMutableSessionState(sessionId);
            String processedChunksKey = getProcessedChunksKey(sessionId);
            String chunkValue = String.valueOf(chunkNumber);
            boolean alreadyPresent = state.getProcessedChunks().contains(chunkNumber)
                    || cacheUtils.setIsMember(processedChunksKey, chunkValue);
            if (!alreadyPresent) {
                addSetMemberAndReconcile(processedChunksKey, chunkValue);
            }
            try {
                cacheUtils.setExpireOrThrow(
                        processedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);
                verifyAuxiliaryTtl(processedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS);
                if (!cacheUtils.setIsMember(processedChunksKey, chunkValue)) {
                    throw new IllegalStateException("处理分片证据写入后校验失败: " + sessionId);
                }
                state.getProcessedChunks().add(chunkNumber);
                touchActivityAndUpdateSnapshot(sessionId, state);
            } catch (RuntimeException writeError) {
                throw writeError;
            }
            return null;
        });
    }

    /**
     * 原子性地添加上传分片和对应的哈希值
     * 使用 Lua 脚本确保操作原子性，避免高并发下的数据不一致
     *
     * @param sessionId   会话ID
     * @param chunkNumber 分片编号
     * @param hash        分片哈希值
     * @return 是否成功
     */
    public boolean addUploadedChunkWithHash(String sessionId, int chunkNumber, String hash) {
        if (hash == null || !CHUNK_HASH_VALUE_PATTERN.matcher(hash).matches()) {
            throw new IllegalArgumentException("分片哈希不是规范 SHA-256 Base64URL 值");
        }
        return withSessionStateLock(sessionId, () -> {
            FileUploadState state = requireMutableSessionState(sessionId);
            String uploadedChunksKey = getUploadedChunksKey(sessionId);
            String chunkHashesKey = getChunkHashesKey(sessionId);
            String chunkValue = String.valueOf(chunkNumber);
            String chunkKey = "chunk_" + chunkNumber;
            String serializedHash = JsonConverter.toJson(hash);
            if (serializedHash == null) {
                throw new IllegalStateException("分片哈希序列化失败: " + sessionId);
            }

            boolean alreadyUploaded = state.getUploadedChunks().contains(chunkNumber);
            String mainStateHash = state.getChunkHashes().get(chunkKey);
            if (alreadyUploaded && !Objects.equals(mainStateHash, hash)) {
                throw new IllegalStateException("同一分片重复上传哈希不一致: " + sessionId);
            }
            boolean auxiliaryMember = cacheUtils.setIsMember(uploadedChunksKey, chunkValue);
            String auxiliaryHash = cacheUtils.hashGetOrThrow(
                    chunkHashesKey, chunkKey, String.class);
            if (auxiliaryHash != null && !Objects.equals(auxiliaryHash, hash)) {
                throw new IllegalStateException("辅助分片哈希与主状态不一致: " + sessionId);
            }
            boolean auxiliaryMutated = !auxiliaryMember || auxiliaryHash == null;
            if (auxiliaryMutated) {
                writeUploadedEvidenceAndReconcile(
                        sessionId,
                        uploadedChunksKey,
                        chunkValue,
                        chunkHashesKey,
                        chunkKey,
                        serializedHash,
                        hash);
            }
            try {
                cacheUtils.setExpireOrThrow(
                        uploadedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);
                cacheUtils.setExpireOrThrow(
                        chunkHashesKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);
                verifyAuxiliaryTtl(uploadedChunksKey, DEFAULT_SESSION_EXPIRE_SECONDS);
                verifyAuxiliaryTtl(chunkHashesKey, DEFAULT_SESSION_EXPIRE_SECONDS);
                if (!cacheUtils.setIsMember(uploadedChunksKey, chunkValue)
                        || !Objects.equals(
                            hash, cacheUtils.hashGetOrThrow(
                                chunkHashesKey, chunkKey, String.class))) {
                    throw new IllegalStateException("上传分片与哈希写入后校验失败: " + sessionId);
                }
                state.getUploadedChunks().add(chunkNumber);
                state.getChunkHashes().put(chunkKey, hash);
                touchActivityAndUpdateSnapshot(sessionId, state);
                log.debug("原子添加分片和哈希成功: sessionId={}, chunk={}", sessionId, chunkNumber);
                return true;
            } catch (RuntimeException writeError) {
                throw writeError;
            }
        });
    }

    /**
     * 保存分片密钥
     */
    public void addChunkKey(String sessionId, int chunkNumber, byte[] keyData) {
        if (keyData == null || keyData.length == 0) {
            throw new IllegalArgumentException("分片密钥不能为空");
        }
        byte[] persisted = getOrCreateChunkKey(sessionId, chunkNumber, keyData);
        if (!Arrays.equals(keyData, persisted)) {
            throw new IllegalStateException(
                    "分片密钥已由不同稳定值占用: " + sessionId + ", chunk=" + chunkNumber);
        }
    }

    /**
     * 以 HSETNX 创建或复用稳定分片密钥，确保崩溃重试和并发任务不会覆盖既有密钥。
     */
    public byte[] getOrCreateChunkKey(String sessionId, int chunkNumber, byte[] candidateKey) {
        if (candidateKey == null || candidateKey.length == 0) {
            throw new IllegalArgumentException("候选分片密钥不能为空");
        }
        return withSessionStateLock(sessionId, () -> {
            FileUploadState state = requireMutableSessionState(sessionId);
            String keysKey = getKeysKey(sessionId);
            String chunkKey = String.valueOf(chunkNumber);
            byte[] mainStateKey = state.getKeys().get(chunkNumber);
            byte[] keyToPersist = mainStateKey == null ? candidateKey : mainStateKey;
            putChunkKeyIfAbsentAndReconcile(
                    sessionId, keysKey, chunkKey, keyToPersist);
            try {
                cacheUtils.setExpireOrThrow(
                        keysKey, DEFAULT_SESSION_EXPIRE_SECONDS, TimeUnit.SECONDS);
                verifyAuxiliaryTtl(keysKey, DEFAULT_SESSION_EXPIRE_SECONDS);
                byte[] persisted = cacheUtils.hashGetOrThrow(
                        keysKey, chunkKey, byte[].class);
                if (persisted == null || persisted.length == 0
                        || !Arrays.equals(keyToPersist, persisted)) {
                    throw new IllegalStateException(
                            "稳定分片密钥写入后校验失败: " + sessionId + ", chunk=" + chunkNumber);
                }
                state.getKeys().put(chunkNumber, persisted);
                saveStateAndVerify(
                        getSessionKey(sessionId), state, DEFAULT_SESSION_EXPIRE_SECONDS);
                return persisted;
            } catch (RuntimeException writeError) {
                throw writeError;
            }
        });
    }

    /**
     * 获取分片密钥
     */
    public Map<Integer, byte[]> getChunkKeys(String sessionId) {
        String keysKey = getKeysKey(sessionId);
        Map<Object, Object> allEntries = cacheUtils.hashGetAll(keysKey);
        FileUploadState mainState = cacheUtils.takeFormCacheOrThrow(
                getSessionKey(sessionId), FileUploadState.class);
        Map<Integer, byte[]> result = new HashMap<>();
        if (mainState != null && mainState.getKeys() != null) {
            mainState.getKeys().forEach((chunk, key) -> {
                validateChunkIndex(chunk, mainState.getTotalChunks(), "主状态分片密钥");
                result.put(chunk, key.clone());
            });
        }
        if (allEntries == null || allEntries.isEmpty()) {
            return result;
        }
        if (mainState == null) {
            throw new IllegalStateException("主状态缺失但仍存在辅助分片密钥: " + sessionId);
        }
        for (Object rawKey : allEntries.keySet()) {
            if (!(rawKey instanceof String chunkKey)) {
                throw new IllegalStateException("分片密钥字段名无效: " + sessionId);
            }
            int chunkNumber;
            try {
                chunkNumber = Integer.parseInt(chunkKey);
            } catch (NumberFormatException invalidIndex) {
                throw new IllegalStateException("分片密钥字段名无效: " + sessionId, invalidIndex);
            }
            validateChunkIndex(chunkNumber, mainState.getTotalChunks(), "辅助分片密钥");
            byte[] auxiliaryKey = cacheUtils.hashGetOrThrow(
                    keysKey, chunkKey, byte[].class);
            byte[] mainKey = result.get(chunkNumber);
            if (auxiliaryKey == null
                    || mainKey != null && !Arrays.equals(mainKey, auxiliaryKey)) {
                throw new IllegalStateException("主状态与辅助分片密钥不一致: " + sessionId);
            }
            result.put(chunkNumber, auxiliaryKey);
        }
        return result;
    }

    /**
     * 更新最后活动时间
     */
    public void updateLastActivityTime(String sessionId) {
        touchSessionActivity(sessionId, System.currentTimeMillis());
    }

    /**
     * 在 Redis 内原子校验主状态仍非完成/人工终态并加入暂停索引。
     *
     * <p>该脚本是暂停操作的线性化点。即使外层 Redisson watchdog 丢锁，终态 SET
     * 与本转换仍由 Redis 串行排序：终态先写时旧暂停被拒绝，暂停先写时终态随后负责移除索引。</p>
     *
     * @param sessionId 上传会话ID
     * @return 原子暂停转换结果
     */
    public PauseTransitionResult addPausedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("暂停会话ID不能为空");
        }
        long result = cacheUtils.atomicPauseSessionIfJsonStatusAllowed(
                getSessionKey(sessionId),
                PAUSED_SESSIONS_KEY,
                getPausedAtKey(sessionId),
                getActivityAtKey(sessionId),
                sessionId,
                PAUSE_FORBIDDEN_STATUSES,
                System.currentTimeMillis(),
                DEFAULT_SESSION_EXPIRE_SECONDS,
                List.of(
                        getUploadedChunksKey(sessionId),
                        getProcessedChunksKey(sessionId),
                        getChunkHashesKey(sessionId),
                        getKeysKey(sessionId)));
        return switch ((int) result) {
            case 1 -> PauseTransitionResult.PAUSED;
            case 0 -> PauseTransitionResult.TERMINAL;
            case -1 -> PauseTransitionResult.SESSION_NOT_FOUND;
            default -> throw new IllegalStateException("未知的暂停状态转换结果: " + result);
        };
    }

    /**
     * 移除暂停的会话
     */
    public boolean removePausedSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("恢复会话ID不能为空");
        }
        long result = cacheUtils.atomicResumeSessionIfJsonStatusAllowed(
                getSessionKey(sessionId),
                PAUSED_SESSIONS_KEY,
                getPausedAtKey(sessionId),
                getActivityAtKey(sessionId),
                sessionId,
                PAUSE_FORBIDDEN_STATUSES,
                System.currentTimeMillis(),
                DEFAULT_SESSION_EXPIRE_SECONDS,
                getSessionAuxiliaryKeys(sessionId));
        return switch ((int) result) {
            case 2 -> true;
            case 0, 1 -> false;
            case -1 -> {
                clearSessionIndexes(sessionId);
                throw new IllegalStateException("上传会话已不存在: " + sessionId);
            }
            default -> throw new IllegalStateException("未知的恢复状态转换结果: " + result);
        };
    }

    /**
     * 检查会话是否暂停
     */
    public boolean isSessionPaused(String sessionId) {
        return cacheUtils.setIsMember(PAUSED_SESSIONS_KEY, sessionId);
    }

    /**
     * 获取暂停会话的有效最后活动时间，兼容尚未写入 pause-at 的历史会话。
     *
     * @param state 上传会话主状态
     * @return 主状态活动时间与最近暂停时间中的较大值
     */
    public long getPauseAwareLastActivityTime(FileUploadState state) {
        if (state == null || state.getClientId() == null || state.getClientId().isBlank()) {
            throw new IllegalArgumentException("上传状态和会话ID不能为空");
        }
        Long activityAt = cacheUtils.takeFormCacheOrThrow(
                getActivityAtKey(state.getClientId()), Long.class);
        Long pausedAt = cacheUtils.takeFormCacheOrThrow(
                getPausedAtKey(state.getClientId()), Long.class);
        if ((activityAt != null && activityAt <= 0)
                || (pausedAt != null && pausedAt <= 0)) {
            throw new IllegalStateException("暂停时间证据无效: " + state.getClientId());
        }
        long effective = state.getLastActivityTime();
        if (activityAt != null) {
            effective = Math.max(effective, activityAt);
        }
        if (pausedAt != null) {
            effective = Math.max(effective, pausedAt);
        }
        return effective;
    }

    /**
     * 根据文件名和SUID获取会话ID
     */
    public String getSessionIdByFileClientKey(Long tenantId, String fileName, String SUID) {
        String fileClientKey = buildFileClientKey(tenantId, fileName, SUID);
        String sessionId = cacheUtils.hashGetOrThrow(
                FILE_SUID_MAPPING_KEY, fileClientKey, String.class);
        if (sessionId == null) {
            return null;
        }
        FileUploadState mappedState = cacheUtils.takeFormCacheOrThrow(
                getSessionKey(sessionId), FileUploadState.class);
        if (mappedState != null
                && !mappedState.isDirectUpload()
                && Objects.equals(mappedState.getTenantId(), tenantId)
                && Objects.equals(mappedState.getFileName(), fileName)
                && Objects.equals(mappedState.getSuid(), SUID)) {
            return sessionId;
        }
        deleteResumeMappingIfOwned(fileClientKey, sessionId);
        clearSessionIndexes(sessionId);
        return null;
    }

    /**
     * 标记上传会话为已完成，并设置 TTL 让其自动过期
     * 用于 completeUpload 成功后，让前端能够轮询到完成状态
     *
     * <p>注意：此方法应仅在所有分片处理完成后调用，此时不会有并发修改状态的操作，
     * 因此 read-modify-write 模式在此场景下是安全的。</p>
     *
     * @param sessionId  会话 ID
     * @param SUID       用户 SUID
     * @param ttlSeconds TTL 秒数（建议 300 秒 = 5 分钟）
     */
    public void markCompleted(String sessionId, String SUID, long ttlSeconds) {
        if (ttlSeconds <= 0) {
            throw new IllegalArgumentException("完成态 TTL 必须大于零");
        }
        withSessionStateLock(sessionId, () -> {
            String stateKey = getSessionKey(sessionId);
            FileUploadState state = cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class);
            if (state == null) {
                log.debug("尝试标记会话完成，但状态不存在: sessionId={}", sessionId);
                clearSessionIndexes(sessionId);
                return null;
            }

            state.setStatus(STATUS_COMPLETED);
            saveStateAndVerify(stateKey, state, ttlSeconds);
            String mappingSuid = SUID == null || SUID.isBlank() ? state.getSuid() : SUID;
            expireSessionCollections(sessionId, ttlSeconds);
            exitTerminalIndexesAfterVerifiedWrite(state, mappingSuid);

            log.info("Redis: 已标记会话为completed并设置TTL: sessionId={}, ttl={}s", sessionId, ttlSeconds);
            return null;
        });
    }

    /**
     * 以指定的有界 TTL 保留人工对账诊断，并一致收敛调度索引语义。
     *
     * @param state 需要保留的上传状态
     * @param status 明确的人工处理状态
     * @param ttlSeconds 诊断保留秒数
     */
    public void retainManualReconciliationState(
            FileUploadState state,
            String status,
            long ttlSeconds
    ) {
        if (state == null
                || state.getClientId() == null
                || state.getClientId().isBlank()
                || status == null
                || status.isBlank()
                || ttlSeconds <= 0) {
            throw new IllegalArgumentException("人工对账状态参数无效");
        }

        String sessionId = state.getClientId();
        withSessionStateLock(sessionId, () -> {
            String stateKey = getSessionKey(sessionId);
            FileUploadState current = cacheUtils.takeFormCacheOrThrow(stateKey, FileUploadState.class);
            if (current == null) {
                clearSessionIndexes(sessionId);
                throw new IllegalStateException("上传会话已过期，拒绝旧快照重建人工对账状态: " + sessionId);
            }
            if (STATUS_COMPLETED.equalsIgnoreCase(current.getStatus())) {
                log.warn("上传会话已完成，忽略迟到的人工对账状态: sessionId={}", sessionId);
                return null;
            }

            FileUploadState merged = mergeMonotonicState(current, state);
            merged.setStatus(status);
            saveStateAndVerify(stateKey, merged, ttlSeconds);
            expireSessionCollections(sessionId, ttlSeconds);
            exitTerminalIndexesAfterVerifiedWrite(merged, merged.getSuid());
            log.warn("Redis: 已保留人工对账诊断状态 sessionId={}, status={}, ttl={}s",
                    sessionId, status, ttlSeconds);
            return null;
        });
    }

    /**
     * 不依赖会话状态清理 active/paused 调度索引，供状态 TTL 先过期时幂等收敛孤儿成员。
     *
     * @param sessionId 上传会话ID
     */
    public void clearSessionIndexes(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        cacheUtils.setRemove(ACTIVE_UPLOADS_KEY, sessionId);
        cacheUtils.setRemove(PAUSED_SESSIONS_KEY, sessionId);
        cacheUtils.deleteCache(getPausedAtKey(sessionId));
        cacheUtils.deleteCache(getActivityAtKey(sessionId));
    }

    /**
     * 移除会话及相关数据
     */
    public void removeSession(String sessionId, String SUID) {
        withSessionStateLock(sessionId, () -> {
            FileUploadState state = cacheUtils.takeFormCacheOrThrow(
                    getSessionKey(sessionId), FileUploadState.class);
            if (state == null) {
                clearSessionIndexes(sessionId);
                log.info("Redis: 会话主状态已过期，仅清理调度索引并保留辅助证据 sessionId={}", sessionId);
                return null;
            }
            String fileClientKey = null;

            String mappingSuid = SUID;
            if ((mappingSuid == null || mappingSuid.isBlank())
                    && state.getSuid() != null) {
                mappingSuid = state.getSuid();
            }
            if (!state.isDirectUpload()
                    && state.getFileName() != null
                    && mappingSuid != null) {
                fileClientKey = buildFileClientKey(
                        state.getTenantId(), state.getFileName(), mappingSuid);
                deleteResumeMappingIfOwned(fileClientKey, sessionId);
            }

            cacheUtils.deleteCache(getUploadedChunksKey(sessionId));
            cacheUtils.deleteCache(getProcessedChunksKey(sessionId));
            cacheUtils.deleteCache(getChunkHashesKey(sessionId));
            cacheUtils.deleteCache(getKeysKey(sessionId));
            cacheUtils.deleteCache(getSessionKey(sessionId));
            clearSessionIndexes(sessionId);

            log.info("Redis: 已移除会话及相关数据 sessionId={}, fileClientKey={}", sessionId, fileClientKey);
            return null;
        });
    }

    /**
     * 为会话辅助集合设置与终态一致的有界 TTL。
     *
     * @param sessionId 上传会话ID
     * @param ttlSeconds 过期秒数
     */
    private void expireSessionCollections(String sessionId, long ttlSeconds) {
        for (String key : List.of(
                getUploadedChunksKey(sessionId),
                getProcessedChunksKey(sessionId),
                getChunkHashesKey(sessionId),
                getKeysKey(sessionId))) {
            if (!cacheUtils.hasKey(key)) {
                continue;
            }
            cacheUtils.setExpireOrThrow(key, ttlSeconds, TimeUnit.SECONDS);
            verifyAuxiliaryTtl(key, ttlSeconds);
        }
    }

    /**
     * 在主状态和全部辅助 TTL 已验证后退出映射与调度索引，active 始终最后删除。
     */
    private void exitTerminalIndexesAfterVerifiedWrite(FileUploadState state, String suid) {
        String sessionId = state.getClientId();
        if (!state.isDirectUpload() && state.getFileName() != null && suid != null) {
            String fileClientKey = buildFileClientKey(
                    state.getTenantId(), state.getFileName(), suid);
            deleteResumeMappingIfOwned(fileClientKey, sessionId);
        }
        cacheUtils.setRemove(PAUSED_SESSIONS_KEY, sessionId);
        if (cacheUtils.setIsMember(PAUSED_SESSIONS_KEY, sessionId)) {
            throw new IllegalStateException("暂停会话索引删除后校验失败: " + sessionId);
        }
        cacheUtils.deleteCache(getPausedAtKey(sessionId));
        cacheUtils.deleteCache(getActivityAtKey(sessionId));
        cacheUtils.setRemove(ACTIVE_UPLOADS_KEY, sessionId);
        if (cacheUtils.setIsMember(ACTIVE_UPLOADS_KEY, sessionId)) {
            throw new IllegalStateException("活跃会话索引删除后校验失败: " + sessionId);
        }
    }

    /**
     * 获取所有活跃会话ID
     */
    public Set<String> getAllActiveSessionIds() {
        Set<String> members = cacheUtils.setMembers(ACTIVE_UPLOADS_KEY);
        return members != null ? members : new HashSet<>();
    }

    /**
     * 集合写响应不确定时回读成员；已提交则继续，明确未提交或无法回读时失败关闭。
     */
    private void addSetMemberAndReconcile(String key, String value) {
        RuntimeException writeError = null;
        try {
            cacheUtils.setAddOrThrow(key, value);
        } catch (RuntimeException exception) {
            writeError = exception;
        }
        try {
            if (cacheUtils.setIsMember(key, value)) {
                return;
            }
        } catch (RuntimeException readError) {
            if (writeError != null) {
                readError.addSuppressed(writeError);
            }
            throw readError;
        }
        if (writeError != null) {
            throw writeError;
        }
        throw new IllegalStateException("缓存集合写入后校验失败: " + key);
    }

    /**
     * 新建文件恢复映射时只占用空字段；同名并行会话不会覆盖先创建的恢复入口。
     */
    private void createResumeMappingWithoutOverwrite(String fileClientKey, String sessionId) {
        RuntimeException writeError = null;
        try {
            cacheUtils.hashPutIfAbsentOrThrow(
                    FILE_SUID_MAPPING_KEY, fileClientKey, sessionId);
        } catch (RuntimeException exception) {
            writeError = exception;
        }
        try {
            String persistedSessionId = cacheUtils.hashGetOrThrow(
                    FILE_SUID_MAPPING_KEY, fileClientKey, String.class);
            if (Objects.equals(sessionId, persistedSessionId)) {
                return;
            }
            if (persistedSessionId != null) {
                log.info("同名上传恢复映射已由另一会话占用，保留当前独立会话: sessionId={}, mappedSessionId={}",
                        sessionId, persistedSessionId);
                return;
            }
        } catch (RuntimeException readError) {
            if (writeError != null) {
                readError.addSuppressed(writeError);
            }
            throw readError;
        }
        if (writeError != null) {
            throw writeError;
        }
        throw new IllegalStateException("文件会话映射写入后校验失败: " + sessionId);
    }

    /**
     * 条件删除映射写响应未知时严格回读；值已变化或已删除均视为当前会话释放成功。
     */
    private void deleteResumeMappingIfOwned(String fileClientKey, String sessionId) {
        RuntimeException deleteError = null;
        try {
            cacheUtils.hashDeleteIfValueMatchesOrThrow(
                    FILE_SUID_MAPPING_KEY, fileClientKey, sessionId);
        } catch (RuntimeException exception) {
            deleteError = exception;
        }
        try {
            String persistedSessionId = cacheUtils.hashGetOrThrow(
                    FILE_SUID_MAPPING_KEY, fileClientKey, String.class);
            if (!Objects.equals(sessionId, persistedSessionId)) {
                return;
            }
        } catch (RuntimeException readError) {
            if (deleteError != null) {
                readError.addSuppressed(deleteError);
            }
            throw readError;
        }
        if (deleteError != null) {
            throw deleteError;
        }
        throw new IllegalStateException("文件会话映射条件删除后仍属于当前会话: " + sessionId);
    }

    /**
     * 使用租户和长度前缀构造无歧义 legacy 文件恢复映射字段。
     */
    private String buildFileClientKey(Long tenantId, String fileName, String suid) {
        if (tenantId == null || fileName == null || suid == null) {
            throw new IllegalArgumentException("文件恢复映射身份不能为空");
        }
        return tenantId + ":" + fileName.length() + ":" + fileName
                + ":" + suid.length() + ":" + suid;
    }

    /**
     * Lua 响应不确定时回读 Set 与 Hash 的精确组合证据，禁止把部分提交当作未提交。
     */
    private void writeUploadedEvidenceAndReconcile(
            String sessionId,
            String uploadedChunksKey,
            String chunkValue,
            String chunkHashesKey,
            String chunkKey,
            String serializedHash,
            String expectedHash
    ) {
        RuntimeException writeError = null;
        try {
            if (!cacheUtils.atomicAddToSetAndHash(
                    uploadedChunksKey,
                    chunkValue,
                    chunkHashesKey,
                    chunkKey,
                    serializedHash)) {
                writeError = new IllegalStateException(
                        "原子分片证据写入返回失败: " + sessionId);
            }
        } catch (RuntimeException exception) {
            writeError = exception;
        }

        try {
            boolean member = cacheUtils.setIsMember(uploadedChunksKey, chunkValue);
            String persistedHash = cacheUtils.hashGetOrThrow(
                    chunkHashesKey, chunkKey, String.class);
            if (member && Objects.equals(expectedHash, persistedHash)) {
                return;
            }
            if (!member && persistedHash == null && writeError != null) {
                throw writeError;
            }
            throw new IllegalStateException(
                    "原子分片证据处于部分提交或冲突状态: " + sessionId,
                    writeError);
        } catch (RuntimeException readOrEvidenceError) {
            if (writeError != null && readOrEvidenceError != writeError) {
                readOrEvidenceError.addSuppressed(writeError);
            }
            throw readOrEvidenceError;
        }
    }

    /**
     * HSETNX 响应不确定时回读稳定密钥；精确一致可继续，缺失或冲突时失败关闭。
     */
    private void putChunkKeyIfAbsentAndReconcile(
            String sessionId,
            String keysKey,
            String chunkKey,
            byte[] expectedKey
    ) {
        RuntimeException writeError = null;
        try {
            cacheUtils.hashPutIfAbsentOrThrow(keysKey, chunkKey, expectedKey);
        } catch (RuntimeException exception) {
            writeError = exception;
        }
        try {
            byte[] persistedKey = cacheUtils.hashGetOrThrow(
                    keysKey, chunkKey, byte[].class);
            if (Arrays.equals(expectedKey, persistedKey)) {
                return;
            }
            if (persistedKey == null && writeError != null) {
                throw writeError;
            }
            throw new IllegalStateException(
                    "稳定分片密钥已由不同值占用: " + sessionId + ", chunk=" + chunkKey,
                    writeError);
        } catch (RuntimeException readError) {
            if (writeError != null && readError != writeError) {
                readError.addSuppressed(writeError);
            }
            throw readError;
        }
    }

    /**
     * 仅在全部关键回读均成功且确认属于本次失败创建时回滚，响应未知时保留恢复入口。
     */
    private void rollbackNewSessionIfSafe(
            String stateKey,
            FileUploadState expectedState,
            String sessionId,
            String fileClientKey,
            RuntimeException creationError
    ) {
        try {
            FileUploadState persisted = cacheUtils.takeFormCacheOrThrow(
                    stateKey, FileUploadState.class);
            if (!hasSamePersistedState(expectedState, persisted)) {
                return;
            }
            cacheUtils.setIsMember(ACTIVE_UPLOADS_KEY, sessionId);
            if (fileClientKey != null) {
                String mappedSessionId = cacheUtils.hashGetOrThrow(
                        FILE_SUID_MAPPING_KEY, fileClientKey, String.class);
                if (Objects.equals(mappedSessionId, sessionId)) {
                    deleteResumeMappingIfOwned(fileClientKey, sessionId);
                }
            }
            cacheUtils.setRemove(ACTIVE_UPLOADS_KEY, sessionId);
            cacheUtils.setRemove(PAUSED_SESSIONS_KEY, sessionId);
            cacheUtils.deleteCache(getPausedAtKey(sessionId));
            cacheUtils.deleteCache(getActivityAtKey(sessionId));
            cacheUtils.deleteCache(getUploadedChunksKey(sessionId));
            cacheUtils.deleteCache(getProcessedChunksKey(sessionId));
            cacheUtils.deleteCache(getChunkHashesKey(sessionId));
            cacheUtils.deleteCache(getKeysKey(sessionId));
            cacheUtils.deleteCache(stateKey);
        } catch (RuntimeException rollbackError) {
            creationError.addSuppressed(rollbackError);
            log.error("新上传会话创建失败且无法证明可安全回滚，已保留恢复证据: sessionId={}",
                    sessionId, rollbackError);
        }
    }

    /**
     * 在跨实例 session state 锁内重读完整会话并执行文件系统与辅助证据的组合操作。
     */
    public <T> T executeWithSessionStateLock(
            String sessionId,
            Function<FileUploadState, T> action
    ) {
        if (action == null) {
            throw new IllegalArgumentException("会话锁内操作不能为空");
        }
        return withSessionStateLock(sessionId, () -> {
            FileUploadState state = cacheUtils.takeFormCacheOrThrow(
                    getSessionKey(sessionId), FileUploadState.class);
            if (state == null) {
                clearSessionIndexes(sessionId);
                throw new IllegalStateException("上传会话已不存在: " + sessionId);
            }
            populateStateCollections(state);
            populateLatestActivityTime(state);
            return action.apply(state);
        });
    }

    /**
     * 在跨实例会话状态锁内执行一次 Redis 状态变更，避免整对象读改写互相覆盖。
     *
     * @param sessionId 上传会话ID
     * @param action 锁内状态变更
     * @return 状态变更结果
     */
    private <T> T withSessionStateLock(String sessionId, Supplier<T> action) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("上传会话ID不能为空");
        }
        RLock lock;
        try {
            lock = redissonClient.getLock(SESSION_STATE_LOCK_KEY_PREFIX + sessionId);
            if (lock == null || !lock.tryLock(SESSION_STATE_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("上传会话状态锁繁忙: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待上传会话状态锁被中断: " + sessionId, e);
        } catch (RuntimeException e) {
            if (e instanceof IllegalStateException) {
                throw e;
            }
            throw new IllegalStateException("获取上传会话状态锁失败: " + sessionId, e);
        }

        try {
            return action.get();
        } finally {
            try {
                lock.unlock();
            } catch (IllegalMonitorStateException e) {
                log.debug("上传会话状态锁已不属于当前线程: sessionId={}", sessionId);
            } catch (RuntimeException e) {
                log.warn("释放上传会话状态锁失败: sessionId={}", sessionId, e);
            }
        }
    }

    /**
     * 读取可继续写入辅助证据的主会话状态，缺失或保护终态时失败关闭。
     */
    private FileUploadState requireMutableSessionState(String sessionId) {
        FileUploadState state = cacheUtils.takeFormCacheOrThrow(
                getSessionKey(sessionId), FileUploadState.class);
        if (state == null) {
            clearSessionIndexes(sessionId);
            throw new IllegalStateException("上传会话已不存在: " + sessionId);
        }
        if (isProtectedTerminalStatus(state.getStatus())) {
            throw new IllegalStateException(
                    "上传会话已进入保护终态，拒绝新增分片证据: " + sessionId);
        }
        return state;
    }

    /**
     * 刷新独立活动证据而不重写主状态 JSON。
     */
    private void touchActivityAndUpdateSnapshot(String sessionId, FileUploadState state) {
        long activityAt = System.currentTimeMillis();
        touchSessionActivity(sessionId, activityAt);
        state.setLastActivityTime(Math.max(state.getLastActivityTime(), activityAt));
    }

    /**
     * 不重写主 JSON 地原子续期会话和已存在辅助证据，避免迟到活动覆盖终态。
     */
    private void touchSessionActivity(String sessionId, long activityAt) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("上传会话ID不能为空");
        }
        long result = cacheUtils.atomicTouchSessionIfJsonStatusAllowed(
                getSessionKey(sessionId),
                getActivityAtKey(sessionId),
                sessionId,
                PAUSE_FORBIDDEN_STATUSES,
                activityAt,
                DEFAULT_SESSION_EXPIRE_SECONDS,
                getSessionAuxiliaryKeys(sessionId));
        if (result == -1L) {
            clearSessionIndexes(sessionId);
            throw new IllegalStateException("上传会话已不存在: " + sessionId);
        }
        if (result == 0L) {
            log.debug("上传会话已进入终态，不刷新活动时间或缩短 TTL: sessionId={}", sessionId);
            return;
        }
        if (result != 1L) {
            throw new IllegalStateException("未知的活动时间续期结果: " + result);
        }
    }

    /**
     * 将独立活动时间证据合并到返回状态，不改变 Redis 主 JSON。
     */
    private void populateLatestActivityTime(FileUploadState state) {
        Long activityAt = cacheUtils.takeFormCacheOrThrow(
                getActivityAtKey(state.getClientId()), Long.class);
        if (activityAt == null) {
            return;
        }
        if (activityAt <= 0) {
            throw new IllegalStateException("活动时间证据无效: " + state.getClientId());
        }
        state.setLastActivityTime(Math.max(state.getLastActivityTime(), activityAt));
    }

    /**
     * 构建需要与会话主状态同步续期的辅助证据键。
     */
    private List<String> getSessionAuxiliaryKeys(String sessionId) {
        return List.of(
                getUploadedChunksKey(sessionId),
                getProcessedChunksKey(sessionId),
                getChunkHashesKey(sessionId),
                getKeysKey(sessionId));
    }

    /**
     * 校验辅助证据键的 TTL 已被刷新到请求值附近且不会成为永久键。
     */
    private void verifyAuxiliaryTtl(String key, long ttlSeconds) {
        long persistedTtl = cacheUtils.getExpireSecondsOrThrow(key);
        long minimumTtl = Math.max(1L, ttlSeconds - STATE_TTL_VERIFICATION_TOLERANCE_SECONDS);
        long maximumTtl = ttlSeconds + 1L;
        if (persistedTtl < minimumTtl || persistedTtl > maximumTtl) {
            throw new IllegalStateException("上传辅助证据 TTL 校验失败: " + key);
        }
    }

    /**
     * 严格写入分片集合、刷新 TTL 并逐项回读，禁止 Set/expire 静默丢写。
     */
    private void writeChunkSetAndVerify(
            String key,
            Set<Integer> chunks,
            long ttlSeconds
    ) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String[] values = chunks.stream()
                .map(String::valueOf)
                .toArray(String[]::new);
        cacheUtils.setAddOrThrow(key, values);
        cacheUtils.setExpireOrThrow(key, ttlSeconds, TimeUnit.SECONDS);
        verifyAuxiliaryTtl(key, ttlSeconds);
        for (String value : values) {
            if (!cacheUtils.setIsMember(key, value)) {
                throw new IllegalStateException("上传分片集合写入后校验失败: " + key);
            }
        }
    }

    /**
     * 仅占用空字段写入分片哈希并刷新 TTL，既有不同值必须失败关闭且不得被批量覆盖。
     */
    private void writeChunkHashesAndVerify(
            String key,
            Map<String, String> hashes,
            long ttlSeconds
    ) {
        if (hashes == null || hashes.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : hashes.entrySet()) {
            putHashFieldIfAbsentAndReconcile(
                    key, entry.getKey(), entry.getValue(), String.class);
        }
        cacheUtils.setExpireOrThrow(key, ttlSeconds, TimeUnit.SECONDS);
        verifyAuxiliaryTtl(key, ttlSeconds);
    }

    /**
     * 仅占用空字段写入分片密钥并刷新 TTL，既有不同字节必须失败关闭且不得被批量覆盖。
     */
    private void writeChunkKeysAndVerify(
            String key,
            Map<String, byte[]> keys,
            long ttlSeconds
    ) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        for (Map.Entry<String, byte[]> entry : keys.entrySet()) {
            putHashFieldIfAbsentAndReconcile(
                    key, entry.getKey(), entry.getValue(), byte[].class);
        }
        cacheUtils.setExpireOrThrow(key, ttlSeconds, TimeUnit.SECONDS);
        verifyAuxiliaryTtl(key, ttlSeconds);
    }

    /**
     * 使用 HSETNX 写入稳定辅助字段；响应异常或字段已存在时精确回读并只接受相同值。
     */
    private <T> void putHashFieldIfAbsentAndReconcile(
            String key,
            String field,
            T expected,
            Class<T> valueType
    ) {
        RuntimeException writeError = null;
        try {
            if (cacheUtils.hashPutIfAbsentOrThrow(key, field, expected)) {
                return;
            }
        } catch (RuntimeException exception) {
            writeError = exception;
        }

        T persisted;
        try {
            persisted = cacheUtils.hashGetOrThrow(key, field, valueType);
        } catch (RuntimeException readError) {
            if (writeError != null) {
                readError.addSuppressed(writeError);
            }
            throw readError;
        }
        boolean matches = expected instanceof byte[] expectedBytes
                && persisted instanceof byte[] persistedBytes
                ? Arrays.equals(expectedBytes, persistedBytes)
                : Objects.equals(expected, persisted);
        if (matches) {
            return;
        }
        if (writeError != null) {
            throw writeError;
        }
        throw new IllegalStateException("上传辅助哈希字段已存在不同稳定值: " + key + ":" + field);
    }

    /**
     * 使用非吞错 SET EX 写入会话状态，并回读关键字段与 TTL 后失败关闭。
     *
     * @param stateKey Redis 会话状态键
     * @param expected 期望持久化的状态
     * @param ttlSeconds 状态 TTL 秒数
     */
    private void saveStateAndVerify(String stateKey, FileUploadState expected, long ttlSeconds) {
        RuntimeException writeError = null;
        try {
            cacheUtils.saveToCacheOrThrow(stateKey, expected, ttlSeconds);
        } catch (RuntimeException exception) {
            writeError = exception;
        }

        FileUploadState persisted;
        try {
            persisted = cacheUtils.takeFormCacheOrThrow(
                    stateKey, FileUploadState.class);
        } catch (RuntimeException readError) {
            if (writeError != null) {
                readError.addSuppressed(writeError);
            }
            throw readError;
        }
        if (!hasSamePersistedState(expected, persisted)) {
            if (writeError != null) {
                throw writeError;
            }
            throw new IllegalStateException(
                    "上传会话状态写入后校验失败: " + expected.getClientId());
        }
        long persistedTtl;
        try {
            persistedTtl = cacheUtils.getExpireSecondsOrThrow(stateKey);
        } catch (RuntimeException ttlReadError) {
            if (writeError != null) {
                ttlReadError.addSuppressed(writeError);
            }
            throw ttlReadError;
        }
        long minimumTtl = Math.max(1L, ttlSeconds - STATE_TTL_VERIFICATION_TOLERANCE_SECONDS);
        long maximumTtl = ttlSeconds + 1L;
        if (persistedTtl < minimumTtl || persistedTtl > maximumTtl) {
            IllegalStateException ttlError = new IllegalStateException(
                    "上传会话状态 TTL 写入后校验失败: " + expected.getClientId());
            if (writeError != null) {
                ttlError.addSuppressed(writeError);
            }
            throw ttlError;
        }
        if (writeError != null) {
            log.warn("Redis SET EX 响应异常但严格回读确认已提交: sessionId={}",
                    expected.getClientId(), writeError);
        }
    }

    /**
     * 比较整对象状态中影响身份、进度与最终化恢复的全部字段。
     */
    private boolean hasSamePersistedState(FileUploadState expected, FileUploadState persisted) {
        return persisted != null
                && Objects.equals(expected.getTenantId(), persisted.getTenantId())
                && Objects.equals(expected.getUserId(), persisted.getUserId())
                && Objects.equals(expected.getClientId(), persisted.getClientId())
                && Objects.equals(expected.getTargetFileId(), persisted.getTargetFileId())
                && Objects.equals(expected.getFileName(), persisted.getFileName())
                && expected.getFileSize() == persisted.getFileSize()
                && Objects.equals(expected.getContentType(), persisted.getContentType())
                && expected.getChunkSize() == persisted.getChunkSize()
                && expected.getTotalChunks() == persisted.getTotalChunks()
                && expected.getStartTime() == persisted.getStartTime()
                && Objects.equals(expected.getUploadedChunks(), persisted.getUploadedChunks())
                && Objects.equals(expected.getProcessedChunks(), persisted.getProcessedChunks())
                && Objects.equals(expected.getChunkHashes(), persisted.getChunkHashes())
                && hasSameChunkKeys(expected.getKeys(), persisted.getKeys())
                && expected.getLastActivityTime() == persisted.getLastActivityTime()
                && expected.getLastProgressLogTime() == persisted.getLastProgressLogTime()
                && Objects.equals(expected.getStatus(), persisted.getStatus())
                && expected.isPrepareStored() == persisted.isPrepareStored()
                && expected.isDirectUpload() == persisted.isDirectUpload()
                && hasSameJsonValue(expected.getDirectUploadParts(), persisted.getDirectUploadParts())
                && hasSameJsonValue(expected.getDirectCompletedParts(), persisted.getDirectCompletedParts())
                && Objects.equals(expected.getPreparedFileId(), persisted.getPreparedFileId())
                && Objects.equals(expected.getDirectFinalizationStage(), persisted.getDirectFinalizationStage())
                && Objects.equals(expected.getDirectFileId(), persisted.getDirectFileId())
                && Objects.equals(expected.getDirectFileHash(), persisted.getDirectFileHash())
                && Objects.equals(expected.getContentHash(), persisted.getContentHash())
                && Objects.equals(expected.getDirectTransactionHash(), persisted.getDirectTransactionHash())
                && Objects.equals(expected.getDirectManifestHash(), persisted.getDirectManifestHash())
                && Objects.equals(expected.getSuid(), persisted.getSuid())
                && Objects.equals(expected.getUploadTempPath(), persisted.getUploadTempPath())
                && Objects.equals(expected.getProcessedTempPath(), persisted.getProcessedTempPath())
                && Objects.equals(
                    expected.getRecoverySchemaVersion(),
                    persisted.getRecoverySchemaVersion())
                && Objects.equals(
                    expected.getEncryptionRecoveryVersion(),
                    persisted.getEncryptionRecoveryVersion())
                && Objects.equals(
                    expected.getEncryptionFormatVersion(),
                    persisted.getEncryptionFormatVersion())
                && Objects.equals(
                    expected.getEncryptionAlgorithmSuite(),
                    persisted.getEncryptionAlgorithmSuite())
                && Arrays.equals(expected.getFileDataKey(), persisted.getFileDataKey())
                && Arrays.equals(expected.getFileNonce(), persisted.getFileNonce())
                && Objects.equals(expected.getFramePlainSize(), persisted.getFramePlainSize())
                && Objects.equals(expected.getKeyDerivation(), persisted.getKeyDerivation())
                && Objects.equals(expected.getNonceDerivation(), persisted.getNonceDerivation())
                && Objects.equals(expected.getAadSchema(), persisted.getAadSchema())
                && Objects.equals(expected.getTagSize(), persisted.getTagSize())
                && Objects.equals(expected.getManifestHash(), persisted.getManifestHash())
                && Objects.equals(expected.getCleanupRetryCount(), persisted.getCleanupRetryCount());
    }

    /**
     * 按分片编号比较密钥字节，避免 byte[] 的引用相等语义造成误判。
     */
    private boolean hasSameChunkKeys(Map<Integer, byte[]> expected, Map<Integer, byte[]> persisted) {
        if (expected == null || persisted == null || !expected.keySet().equals(persisted.keySet())) {
            return expected == null && persisted == null;
        }
        return expected.entrySet().stream()
                .allMatch(entry -> Arrays.equals(entry.getValue(), persisted.get(entry.getKey())));
    }

    /**
     * 通过稳定 JSON 表示比较没有值语义 equals 的嵌套状态列表。
     */
    private boolean hasSameJsonValue(Object expected, Object persisted) {
        String expectedJson = JsonConverter.toJson(expected);
        String persistedJson = JsonConverter.toJson(persisted);
        return expectedJson != null && expectedJson.equals(persistedJson);
    }

    /**
     * 合并同一会话的单调状态字段，保留较新的最终化检查点并吸收迟到的进度集合。
     *
     * @param current Redis 当前状态
     * @param update 调用方待写状态快照
     * @return 可安全覆盖 Redis 的合并状态
     */
    private FileUploadState mergeMonotonicState(FileUploadState current, FileUploadState update) {
        validateImmutableState(current, update);

        update.setTenantId(current.getTenantId() != null ? current.getTenantId() : update.getTenantId());
        update.setUserId(current.getUserId() != null ? current.getUserId() : update.getUserId());
        update.setClientId(current.getClientId());
        update.setTargetFileId(mergeStableValue(
                "targetFileId", current.getTargetFileId(), update.getTargetFileId()));
        update.setFileName(current.getFileName() != null ? current.getFileName() : update.getFileName());
        update.setFileSize(current.getFileSize());
        update.setContentType(current.getContentType() != null
                ? current.getContentType() : update.getContentType());
        update.setChunkSize(current.getChunkSize());
        update.setTotalChunks(current.getTotalChunks());
        update.setStartTime(current.getStartTime() > 0 ? current.getStartTime() : update.getStartTime());
        update.setDirectUpload(current.isDirectUpload());
        update.setRecoverySchemaVersion(mergeStableValue(
                "recoverySchemaVersion",
                current.getRecoverySchemaVersion(),
                update.getRecoverySchemaVersion()));
        update.setEncryptionRecoveryVersion(mergeStableValue(
                "encryptionRecoveryVersion",
                current.getEncryptionRecoveryVersion(),
                update.getEncryptionRecoveryVersion()));
        update.setEncryptionFormatVersion(mergeStableValue(
                "encryptionFormatVersion",
                current.getEncryptionFormatVersion(),
                update.getEncryptionFormatVersion()));
        update.setEncryptionAlgorithmSuite(mergeStableValue(
                "encryptionAlgorithmSuite",
                current.getEncryptionAlgorithmSuite(),
                update.getEncryptionAlgorithmSuite()));
        update.setFileDataKey(mergeStableBytes(
                "fileDataKey", current.getFileDataKey(), update.getFileDataKey()));
        update.setFileNonce(mergeStableBytes(
                "fileNonce", current.getFileNonce(), update.getFileNonce()));
        update.setFramePlainSize(mergeStableValue(
                "framePlainSize", current.getFramePlainSize(), update.getFramePlainSize()));
        update.setKeyDerivation(mergeStableValue(
                "keyDerivation", current.getKeyDerivation(), update.getKeyDerivation()));
        update.setNonceDerivation(mergeStableValue(
                "nonceDerivation", current.getNonceDerivation(), update.getNonceDerivation()));
        update.setAadSchema(mergeStableValue(
                "aadSchema", current.getAadSchema(), update.getAadSchema()));
        update.setTagSize(mergeStableValue("tagSize", current.getTagSize(), update.getTagSize()));
        update.setManifestHash(mergeStableValue(
                "manifestHash", current.getManifestHash(), update.getManifestHash()));
        update.setSuid(mergeStableValue("suid", current.getSuid(), update.getSuid()));
        update.setUploadTempPath(mergeStableValue(
                "uploadTempPath", current.getUploadTempPath(), update.getUploadTempPath()));
        update.setProcessedTempPath(mergeStableValue(
                "processedTempPath", current.getProcessedTempPath(), update.getProcessedTempPath()));

        update.setUploadedChunks(mergeSets(current.getUploadedChunks(), update.getUploadedChunks()));
        update.setProcessedChunks(mergeSets(current.getProcessedChunks(), update.getProcessedChunks()));
        update.setChunkHashes(mergeChunkHashes(
                current.getChunkHashes(), update.getChunkHashes()));
        update.setKeys(mergeChunkKeys(current.getKeys(), update.getKeys()));
        update.setLastActivityTime(Math.max(current.getLastActivityTime(), update.getLastActivityTime()));
        update.setLastProgressLogTime(Math.max(
                current.getLastProgressLogTime(), update.getLastProgressLogTime()));
        update.setCleanupRetryCount(Math.max(
                Objects.requireNonNullElse(current.getCleanupRetryCount(), 0),
                Objects.requireNonNullElse(update.getCleanupRetryCount(), 0)));
        update.setStatus(selectMonotonicStatus(current.getStatus(), update.getStatus()));

        update.setPrepareStored(current.isPrepareStored() || update.isPrepareStored());
        update.setPreparedFileId(mergeStableValue(
                "preparedFileId", current.getPreparedFileId(), update.getPreparedFileId()));
        update.setContentHash(mergeStableValue(
                "contentHash", current.getContentHash(), update.getContentHash()));
        update.setDirectFileId(mergeStableValue(
                "directFileId", current.getDirectFileId(), update.getDirectFileId()));
        update.setDirectFileHash(mergeStableValue(
                "directFileHash", current.getDirectFileHash(), update.getDirectFileHash()));
        update.setDirectTransactionHash(mergeStableValue(
                "directTransactionHash",
                current.getDirectTransactionHash(),
                update.getDirectTransactionHash()));
        update.setDirectManifestHash(mergeStableValue(
                "directManifestHash", current.getDirectManifestHash(), update.getDirectManifestHash()));
        update.setDirectFinalizationStage(selectLatestDirectStage(
                current.getDirectFinalizationStage(), update.getDirectFinalizationStage()));
        update.setDirectUploadParts(selectTrustedDirectPlan(
                current.getDirectUploadParts(), update.getDirectUploadParts()));
        update.setDirectCompletedParts(selectTrustedCompletedParts(
                current.getDirectCompletedParts(), update.getDirectCompletedParts()));
        return update;
    }

    /**
     * 校验两个状态快照属于同一不可变上传计划，阻断跨会话或跨租户串写。
     */
    private void validateImmutableState(FileUploadState current, FileUploadState update) {
        boolean tenantMismatch = current.getTenantId() != null
                && update.getTenantId() != null
                && !Objects.equals(current.getTenantId(), update.getTenantId());
        boolean targetMismatch = current.getTargetFileId() != null
                && update.getTargetFileId() != null
                && !Objects.equals(current.getTargetFileId(), update.getTargetFileId());
        if (!Objects.equals(current.getClientId(), update.getClientId())
                || (current.getUserId() != null && update.getUserId() != null
                    && !Objects.equals(current.getUserId(), update.getUserId()))
                || tenantMismatch
                || targetMismatch
                || !Objects.equals(
                    current.getRecoverySchemaVersion(),
                    update.getRecoverySchemaVersion())
                || !Objects.equals(
                    current.getEncryptionRecoveryVersion(),
                    update.getEncryptionRecoveryVersion())
                || !Objects.equals(
                    current.getEncryptionFormatVersion(),
                    update.getEncryptionFormatVersion())
                || !Objects.equals(
                    current.getEncryptionAlgorithmSuite(),
                    update.getEncryptionAlgorithmSuite())
                || !Arrays.equals(current.getFileDataKey(), update.getFileDataKey())
                || !Arrays.equals(current.getFileNonce(), update.getFileNonce())
                || !Objects.equals(current.getFramePlainSize(), update.getFramePlainSize())
                || !Objects.equals(current.getKeyDerivation(), update.getKeyDerivation())
                || !Objects.equals(current.getNonceDerivation(), update.getNonceDerivation())
                || !Objects.equals(current.getAadSchema(), update.getAadSchema())
                || !Objects.equals(current.getTagSize(), update.getTagSize())
                || (current.getFileName() != null && update.getFileName() != null
                    && !Objects.equals(current.getFileName(), update.getFileName()))
                || current.getFileSize() != update.getFileSize()
                || current.getChunkSize() != update.getChunkSize()
                || current.getTotalChunks() != update.getTotalChunks()
                || current.isDirectUpload() != update.isDirectUpload()) {
            throw new IllegalStateException("上传会话不可变计划发生变化: " + current.getClientId());
        }
    }

    /**
     * 合并只允许首次赋值的稳定字段，两个非空值不一致时失败关闭。
     */
    private <T> T mergeStableValue(String field, T current, T update) {
        if (current == null) {
            return update;
        }
        if (update == null) {
            return current;
        }
        if (!Objects.equals(current, update)) {
            throw new IllegalStateException("上传会话稳定字段发生变化: " + field);
        }
        return current;
    }

    /**
     * 合并只允许首次赋值的 byte[] 稳定字段，避免引用相等导致检查点漂移。
     */
    private byte[] mergeStableBytes(String field, byte[] current, byte[] update) {
        if (current == null) {
            return update == null ? null : update.clone();
        }
        if (update == null) {
            return current.clone();
        }
        if (!Arrays.equals(current, update)) {
            throw new IllegalStateException("上传会话稳定字段发生变化: " + field);
        }
        return current.clone();
    }

    /**
     * 合并进度集合，确保迟到快照只能增加证据而不能删除已确认分片。
     */
    private <T> Set<T> mergeSets(Set<T> current, Set<T> update) {
        Set<T> merged = new HashSet<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (update != null) {
            merged.addAll(update);
        }
        return merged;
    }

    /**
     * 单调合并分片哈希；同一分片的两个非空哈希不同表示证据冲突，必须失败关闭。
     */
    private Map<String, String> mergeChunkHashes(
            Map<String, String> current,
            Map<String, String> update
    ) {
        Map<String, String> merged = new HashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        if (update != null) {
            for (Map.Entry<String, String> entry : update.entrySet()) {
                String existing = merged.get(entry.getKey());
                if (existing != null && !Objects.equals(existing, entry.getValue())) {
                    throw new IllegalStateException(
                            "上传会话分片哈希证据发生冲突: " + entry.getKey());
                }
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * 单调合并分片密钥；同一分片的两个非空字节值不同表示证据冲突，必须失败关闭。
     */
    private Map<Integer, byte[]> mergeChunkKeys(
            Map<Integer, byte[]> current,
            Map<Integer, byte[]> update
    ) {
        Map<Integer, byte[]> merged = new HashMap<>();
        if (current != null) {
            merged.putAll(current);
        }
        if (update != null) {
            for (Map.Entry<Integer, byte[]> entry : update.entrySet()) {
                byte[] existing = merged.get(entry.getKey());
                if (existing != null && !Arrays.equals(existing, entry.getValue())) {
                    throw new IllegalStateException(
                            "上传会话分片密钥证据发生冲突: " + entry.getKey());
                }
                merged.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    /**
     * 选择不会倒退恢复或人工处理语义的会话状态。
     */
    private String selectMonotonicStatus(String current, String update) {
        return statusPriority(current) >= statusPriority(update) ? current : update;
    }

    /**
     * 计算状态单调优先级，最终态与恢复态不得被普通进度状态覆盖。
     */
    private int statusPriority(String status) {
        if (STATUS_FINALIZATION_MANUAL_REQUIRED.equals(status)
                || STATUS_CLEANUP_MANUAL_REQUIRED.equals(status)) {
            return 100;
        }
        if (STATUS_COMPLETED.equalsIgnoreCase(status)) {
            return 90;
        }
        if (STATUS_FINALIZATION_RECOVERY_PENDING.equals(status)) {
            return 50;
        }
        return 0;
    }

    /**
     * 判断状态是否带有专用 TTL，普通更新和活动时间刷新不得覆盖。
     */
    private boolean isProtectedTerminalStatus(String status) {
        return STATUS_COMPLETED.equalsIgnoreCase(status)
                || STATUS_FINALIZATION_MANUAL_REQUIRED.equals(status)
                || STATUS_CLEANUP_MANUAL_REQUIRED.equals(status);
    }

    /**
     * 按固定阶段序选择更靠后的直传最终化检查点。
     */
    private String selectLatestDirectStage(String current, String update) {
        if (current == null || current.isBlank()) {
            return update;
        }
        if (update == null || update.isBlank() || Objects.equals(current, update)) {
            return current;
        }
        Integer currentRank = DIRECT_FINALIZATION_STAGE_RANK.get(current);
        Integer updateRank = DIRECT_FINALIZATION_STAGE_RANK.get(update);
        if (currentRank == null || updateRank == null) {
            throw new IllegalStateException("未知的直传最终化阶段变化");
        }
        return currentRank >= updateRank ? current : update;
    }

    /**
     * 保留 Redis 中已经发布的直传计划，并拒绝非空计划被替换。
     */
    private List<FileUploadState.DirectUploadPartState> selectTrustedDirectPlan(
            List<FileUploadState.DirectUploadPartState> current,
            List<FileUploadState.DirectUploadPartState> update
    ) {
        if (current == null || current.isEmpty()) {
            return update == null ? new ArrayList<>() : new ArrayList<>(update);
        }
        if (update != null && !update.isEmpty()
                && !Objects.equals(JsonConverter.toJson(current), JsonConverter.toJson(update))) {
            throw new IllegalStateException("直传上传计划发生变化");
        }
        return new ArrayList<>(current);
    }

    /**
     * 保留已经验证的直传完成证据，并拒绝后续快照替换其内容。
     */
    private List<FileUploadState.DirectUploadCompletedPartState> selectTrustedCompletedParts(
            List<FileUploadState.DirectUploadCompletedPartState> current,
            List<FileUploadState.DirectUploadCompletedPartState> update
    ) {
        if (current == null || current.isEmpty()) {
            return update == null ? new ArrayList<>() : new ArrayList<>(update);
        }
        if (update != null && !update.isEmpty()
                && !Objects.equals(JsonConverter.toJson(current), JsonConverter.toJson(update))) {
            throw new IllegalStateException("直传完成证据发生变化");
        }
        return new ArrayList<>(current);
    }

    /**
     * 填充状态中的集合数据
     */
    private void populateStateCollections(FileUploadState state) {
        String sessionId = state.getClientId();

        // 获取已上传分片
        String uploadedChunksKey = getUploadedChunksKey(sessionId);
        Set<Integer> uploadedChunks = cacheUtils.getIntegerSet(uploadedChunksKey);
        uploadedChunks.forEach(chunk -> validateChunkIndex(
                chunk, state.getTotalChunks(), "辅助 uploaded 集合"));
        state.getUploadedChunks().addAll(uploadedChunks);

        // 获取已处理分片
        String processedChunksKey = getProcessedChunksKey(sessionId);
        Set<Integer> processedChunks = cacheUtils.getIntegerSet(processedChunksKey);
        processedChunks.forEach(chunk -> validateChunkIndex(
                chunk, state.getTotalChunks(), "辅助 processed 集合"));
        state.getProcessedChunks().addAll(processedChunks);

        // 获取分片哈希
        String chunkHashesKey = getChunkHashesKey(sessionId);
        Map<Object, Object> rawChunkHashes = cacheUtils.hashGetAll(chunkHashesKey);

        if (rawChunkHashes != null && !rawChunkHashes.isEmpty()) {
            Map<String, String> chunkHashes = new HashMap<>();
            for (Map.Entry<Object, Object> rawEntry : rawChunkHashes.entrySet()) {
                if (!(rawEntry.getKey() instanceof String hashKey)
                        || !(rawEntry.getValue() instanceof String rawValue)) {
                    throw new IllegalStateException(
                            "Redis 辅助分片哈希包含非法类型: " + sessionId);
                }
                if (!CHUNK_HASH_FIELD_PATTERN.matcher(hashKey).matches()) {
                    throw new IllegalStateException(
                            "Redis 辅助分片哈希编码损坏: " + hashKey);
                }
                int chunkNumber;
                try {
                    chunkNumber = Integer.parseInt(hashKey.substring("chunk_".length()));
                } catch (NumberFormatException invalidIndex) {
                    throw new IllegalStateException(
                            "Redis 辅助分片哈希索引无效: " + hashKey, invalidIndex);
                }
                validateChunkIndex(chunkNumber, state.getTotalChunks(), "辅助分片哈希");
                String parsedValue;
                try {
                    parsedValue = JsonConverter.parse(rawValue, String.class);
                } catch (RuntimeException parseError) {
                    throw new IllegalStateException(
                            "Redis 辅助分片哈希 JSON 损坏: " + hashKey, parseError);
                }
                if (parsedValue == null
                        || !CHUNK_HASH_VALUE_PATTERN.matcher(parsedValue).matches()) {
                    throw new IllegalStateException(
                            "Redis 辅助分片哈希反序列化结果无效: " + hashKey);
                }
                chunkHashes.put(hashKey, parsedValue);
            }
            for (Map.Entry<String, String> entry : chunkHashes.entrySet()) {
                String mainValue = state.getChunkHashes().get(entry.getKey());
                if (mainValue != null && !Objects.equals(mainValue, entry.getValue())) {
                    throw new IllegalStateException("主状态与辅助分片哈希不一致: " + sessionId);
                }
                state.getChunkHashes().put(entry.getKey(), entry.getValue());
            }
        }

        // 获取密钥
        Map<Integer, byte[]> keys = getChunkKeys(sessionId);
        for (Map.Entry<Integer, byte[]> entry : keys.entrySet()) {
            byte[] mainValue = state.getKeys().get(entry.getKey());
            if (mainValue != null && !Arrays.equals(mainValue, entry.getValue())) {
                throw new IllegalStateException("主状态与辅助分片密钥不一致: " + sessionId);
            }
            state.getKeys().put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * 校验所有辅助证据分片索引都落在当前不可变上传计划范围内。
     */
    private void validateChunkIndex(int chunkIndex, int totalChunks, String source) {
        if (chunkIndex < 0 || chunkIndex >= totalChunks) {
            throw new IllegalStateException(source + "索引越界: " + chunkIndex);
        }
    }

    // 键构建辅助方法

    private String getSessionKey(String sessionId) {
        return SESSION_KEY_PREFIX + sessionId;
    }

    private String getPausedAtKey(String sessionId) {
        return PAUSED_AT_KEY_PREFIX + sessionId;
    }

    private String getActivityAtKey(String sessionId) {
        return ACTIVITY_AT_KEY_PREFIX + sessionId;
    }

    private String getUploadedChunksKey(String sessionId) {
        return SESSION_UPLOADED_CHUNKS_KEY_PREFIX + sessionId;
    }

    private String getProcessedChunksKey(String sessionId) {
        return SESSION_PROCESSED_CHUNKS_KEY_PREFIX + sessionId;
    }

    private String getChunkHashesKey(String sessionId) {
        return SESSION_CHUNK_HASHES_KEY_PREFIX + sessionId;
    }

    private String getKeysKey(String sessionId) {
        return SESSION_KEYS_KEY_PREFIX + sessionId;
    }
}
