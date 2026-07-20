package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.event.FileStorageEvent;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.exception.RetryableException;
import cn.flying.common.lock.DistributedLock;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.UidEncoder;
import cn.flying.dao.vo.file.*;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import cn.flying.service.QuotaService;
import cn.flying.service.assistant.FileUploadRedisStateManager;
import cn.flying.service.encryption.*;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.platformapi.request.AbortDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CompleteDirectMultipartUploadRequest;
import cn.flying.platformapi.request.CreateDirectMultipartUploadRequest;
import cn.flying.platformapi.request.DirectMultipartCompletedPart;
import cn.flying.platformapi.request.DirectMultipartUploadPartRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.CompleteDirectMultipartUploadResponse;
import cn.flying.platformapi.response.CreateDirectMultipartUploadResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import cn.flying.platformapi.response.DirectMultipartUploadPartUrl;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.URI;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * @program: RecordPlatform
 * @description: 文件上传服务 (包含业务逻辑、状态管理、文件操作等)
 * @author flyingcoding
 * @create: 2025-03-31 11:22
 * <p>
 * 注意：此类不使用类级别 @Transactional，因为：
 *  flyingcoding
 *  flyingcoding
 *  flyingcoding
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class FileUploadServiceImpl implements FileUploadService {

    // --- 目录常量 ---
    private static final String UPLOAD_BASE_DIR = "uploads"; // 原始分片存储基础目录
    private static final String PROCESSED_BASE_DIR = "processed"; // 加密后分片存储基础目录
    // --- 文件处理常量 ---
    private static final int BUFFER_SIZE = 8 * 1024 * 1024; // 8MB I/O 缓冲区大小
    private static final long MAX_FILE_SIZE_BYTES = 4096 * 1024 * 1024L; // 4GB 最大文件大小限制
    private static final int MAX_CHUNK_SIZE_BYTES = 80 * 1024 * 1024; // 80MB 最大分片大小 (Dubbo载荷限制100MB，预留安全边际)
    private static final int MAX_TOTAL_CHUNKS = 10_000;
    private static final int PROGRESS_UPDATE_INTERVAL_MS = 1000; // 进度日志更新间隔（毫秒）
    private static final Pattern SAFE_FILENAME_PATTERN = Pattern.compile("^[\\p{IsHan}a-zA-Z0-9\\u4e00-\\u9fa5._\\-\\s,;!@#$%&()+=]+$");
    private static final Pattern DIRECT_SHA256_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern DIRECT_STORAGE_NODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final String HASH_ALGORITHM = "SHA-256"; // 哈希算法
    private static final String CONTENT_HASH_PREFIX = "sha256:";
    private static final String HASH_SEPARATOR = "\n--HASH--\n"; // 哈希值前的分隔符
    private static final String KEY_SEPARATOR = "\n--NEXT_KEY--\n"; // 下一个密钥前的分隔符
    private static final int NEXT_KEY_TAIL_SCAN_BYTES = 512;
    private static final String UPLOAD_FINALIZATION_LOCK_KEY_PREFIX =
            "distributed:lock:upload:finalize:session:";
    private static final String PREPARED_FILE_FINALIZATION_LOCK_KEY_PREFIX =
            "distributed:lock:upload:finalize:prepared-file:";
    private static final String CHUNK_PROCESSING_LOCK_KEY_PREFIX =
            "distributed:lock:upload:process:chunk:";
    private static final long ASYNC_FINALIZATION_LOCK_WAIT_SECONDS = 5L;
    private static final long PREPARED_FILE_FINALIZATION_LOCK_WAIT_SECONDS = 5L;
    private static final String QUOTA_COMPLETE_LOCK_KEY_PREFIX = "distributed:lock:upload:quota:complete:tenant:";
    private static final long QUOTA_COMPLETE_LOCK_WAIT_SECONDS = 5L;
    private static final int MAX_CLEANUP_RETRIES = 3;
    private static final int CURRENT_RECOVERY_SCHEMA_VERSION = 1;
    private static final String UPLOAD_SESSION_STATUS_CLEANUP_MANUAL_REQUIRED =
            "cleanup_manual_required";
    private static final String UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING =
            "finalization_recovery_pending";
    private static final String UPLOAD_SESSION_STATUS_FINALIZATION_MANUAL_REQUIRED =
            "finalization_manual_reconciliation_required";
    private static final long MANUAL_RECONCILIATION_TTL_SECONDS = 7 * 24 * 60 * 60L;
    private static final String UPLOAD_SESSION_STATUS_COMPLETED = "completed";
    private static final String DIRECT_STAGE_SESSION_CREATED = "SESSION_CREATED";
    private static final String DIRECT_STAGE_STORAGE_COMPLETED = "STORAGE_COMPLETED";
    private static final String DIRECT_STAGE_PREPARE_ID_ALLOCATED = "PREPARE_ID_ALLOCATED";
    private static final String DIRECT_STAGE_PREPARE_STORED = "PREPARE_STORED";
    private static final String DIRECT_STAGE_CHAIN_ATTESTING = "CHAIN_ATTESTING";
    private static final String DIRECT_STAGE_CHAIN_ATTESTED = "CHAIN_ATTESTED";
    private static final String DIRECT_STAGE_FILE_STORED = "FILE_STORED";
    private static final String DIRECT_STAGE_MANIFEST_STORED = "MANIFEST_STORED";
    // --- 允许的文件类型 ---
    private static final Set<String> ALLOWED_FILE_EXTENSIONS = Set.of(
            "jpg", "jpeg", "png", "gif", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar", "7z"
    );
    private static final Map<String, String> ALLOWED_MIME_TYPES = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"), Map.entry("image/png", "png"), Map.entry("image/gif", "gif"),
            Map.entry("application/pdf", "pdf"), Map.entry("application/msword", "doc"),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
            Map.entry("application/vnd.ms-excel", "xls"),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
            Map.entry("application/vnd.ms-powerpoint", "ppt"),
            Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
            Map.entry("text/plain", "txt"), Map.entry("application/zip", "zip"),
            Map.entry("application/x-rar-compressed", "rar"), Map.entry("application/x-7z-compressed", "7z")
    );
    // --- 线程池配置 ---
    @Qualifier("fileProcessTaskExecutor")
    private final Executor fileProcessingExecutor;
    private final ApplicationEventPublisher eventPublisher;
    // Redis状态管理器
    private final FileUploadRedisStateManager redisStateManager;
    // 加密策略工厂
    private final EncryptionStrategyFactory encryptionStrategyFactory;
    private final FileService fileService;
    private final QuotaService quotaService;
    private final RedissonClient redissonClient;
    private final FileRemoteClient fileRemoteClient;
    private final ChunkManifestService chunkManifestService;

    @PostConstruct
    public void initialize() {
        try {
            Files.createDirectories(Paths.get(UPLOAD_BASE_DIR));
            Files.createDirectories(Paths.get(PROCESSED_BASE_DIR));
            log.info("The basic upload and processing directory has been ensured to exist");
        } catch (IOException e) {
            log.error("初始化基础目录失败", e);
            // 初始化失败是严重问题，可以抛出运行时异常阻止应用启动
            throw new RuntimeException("创建基础目录失败", e);
        }
        // 定时清理任务由 Spring @Scheduled 驱动（避免重复调度）
    }

    /**
     * 定时任务清理过期的上传会话
     * 改为每6小时执行一次，提高清理效率
     */
    @Scheduled(fixedDelay = 6 * 60 * 60 * 1000, initialDelay = 60 * 60 * 1000) // 每6小时执行一次（启动后1小时执行）
    @DistributedLock(key = "upload:session:cleanup", leaseTime = 3600)
    public void cleanupExpiredUploadSessions() {
        long now = System.currentTimeMillis();
        long timeoutMillis = 12 * 60 * 60 * 1000L; // 12 小时
        long pausedTimeoutMillis = 24 * 60 * 60 * 1000L; // 暂停会话24小时超时

        log.info("-------------开始执行定时清理任务，查找超过 {} 小时未活动的上传会话----------------",
                TimeUnit.MILLISECONDS.toHours(timeoutMillis));

        Set<String> activeSessionIds = redisStateManager.getAllActiveSessionIds();
        List<ScheduledCleanupCandidate> expiredSessions = new ArrayList<>();
        List<ScheduledCleanupCandidate> pausedExpiredSessions = new ArrayList<>();

        for (String clientId : activeSessionIds) {
            FileUploadState state = redisStateManager.getState(clientId);
            if (state != null) {
                boolean isPaused = redisStateManager.isSessionPaused(clientId);
                long effectiveLastActivityTime = isPaused
                        ? redisStateManager.getPauseAwareLastActivityTime(state)
                        : state.getLastActivityTime();
                long inactiveDuration = now - effectiveLastActivityTime;

                // 暂停会话使用更长的超时时间
                long currentTimeoutMillis = isPaused ? pausedTimeoutMillis : timeoutMillis;

                if (inactiveDuration >= currentTimeoutMillis) {
                    if (isPaused) {
                        pausedExpiredSessions.add(new ScheduledCleanupCandidate(
                                clientId, now - pausedTimeoutMillis, true));
                        log.warn("发现过期暂停会话: 客户端ID={}, 文件名={}, 上次活动时间={}, 暂停时长={}小时",
                                clientId, state.getFileName(),
                                Instant.ofEpochMilli(effectiveLastActivityTime),
                                TimeUnit.MILLISECONDS.toHours(inactiveDuration));
                    } else {
                        expiredSessions.add(new ScheduledCleanupCandidate(
                                clientId, now - timeoutMillis, false));
                        log.warn("发现过期活跃会话: 客户端ID={}, 文件名={}, 上次活动时间={}, 未活动时长={}小时",
                                clientId, state.getFileName(),
                                Instant.ofEpochMilli(effectiveLastActivityTime),
                                TimeUnit.MILLISECONDS.toHours(inactiveDuration));
                    }
                }
            } else {
                // 处理状态丢失但仍在活跃集合中的会话
                expiredSessions.add(new ScheduledCleanupCandidate(
                        clientId, now - timeoutMillis, false));
                log.warn("发现状态丢失的会话ID: {}", clientId);
            }
        }

        // 使用通用的SUID，避免依赖MDC
        int cleanedCount = 0;
        for (ScheduledCleanupCandidate candidate : expiredSessions) {
            if (cleanupUploadSessionInternal("", candidate.clientId(), candidate)) {
                cleanedCount++;
            }
        }
        for (ScheduledCleanupCandidate candidate : pausedExpiredSessions) {
            if (cleanupUploadSessionInternal("", candidate.clientId(), candidate)) {
                cleanedCount++;
            }
        }

        if (cleanedCount > 0) {
            log.info("定时清理完成，共清理 {} 个过期上传会话（活跃: {}, 暂停: {}）。",
                    cleanedCount, expiredSessions.size(), pausedExpiredSessions.size());
        } else {
            log.info("定时清理完成，未发现需要清理的过期上传会话。");
        }
    }

    // === Service 方法实现 ===

    /**
     * 记录定时扫描时采用的截止时间和暂停分类，供 finalizer 锁内重新判定。
     */
    private record ScheduledCleanupCandidate(String clientId, long cutoffMillis, boolean paused) {
    }

    /**
     * 表示稳定数据库目标在清理前的收敛结果。
     */
    private enum CleanupDatabaseDecision {
        ALLOW_DELETE,
        RECOVERED,
        RETAIN
    }

    /**
     * 内部方法清理指定 clientId 的上传状态和相关文件目录
     * 支持处理SUID为空的情况（用于定时清理任务）
     * <p>
     * 修复竞态条件：先同步清理文件，再删除 Redis 状态
     * 这样即使系统崩溃，定时清理任务仍可通过 Redis 状态找到残留文件
     */
    private boolean cleanupUploadSessionInternal(String SUID, String clientId) {
        return cleanupUploadSessionInternal(SUID, clientId, null);
    }

    /**
     * 在会话 finalizer 锁内重新读取状态并执行显式或定时清理。
     */
    private boolean cleanupUploadSessionInternal(
            String suid,
            String clientId,
            ScheduledCleanupCandidate scheduledCandidate
    ) {
        FileUploadState expectedState = redisStateManager.getState(clientId);
        if (expectedState == null) {
            redisStateManager.clearSessionIndexes(clientId);
            log.warn("清理会话 {} 时主状态已不存在，仅收敛孤儿调度索引并保留辅助证据自然过期。", clientId);
            return false;
        }

        RLock finalizationLock;
        try {
            finalizationLock = acquireUploadFinalizationLock(clientId);
        } catch (RetryableException e) {
            log.info("上传会话 {} 正在最终化，跳过本轮清理。", clientId);
            return false;
        }
        try {
            return redisStateManager.executeWithSessionStateLock(clientId, latestState -> {
                if (!hasSameUploadPlan(expectedState, latestState, clientId)) {
                    log.warn("上传会话 {} 清理时上传计划发生变化，保留最新状态。", clientId);
                    return false;
                }
                if (!hasCurrentRecoverySchema(latestState)) {
                    retainFinalizationForManualReconciliation(
                            latestState,
                            "上传会话恢复协议版本缺失或不受支持");
                    return false;
                }
                if (!isScheduledCleanupStillEligible(latestState, scheduledCandidate)) {
                    log.info("上传会话 {} 在获得清理锁前已恢复活动或暂停分类变化，跳过清理。", clientId);
                    return false;
                }
                if (isUploadSessionCompleted(latestState)) {
                    convergeCompletedSessionState(latestState);
                    return false;
                }
                if (isManualReconciliationState(latestState)) {
                    redisStateManager.clearSessionIndexes(clientId);
                    return false;
                }

                log.info("--------------------开始清理会话 {} 的相关文件-----------------", clientId);
                int retryCount = latestState.getCleanupRetryCount() == null
                        ? 0
                        : latestState.getCleanupRetryCount();
                if (latestState.isDirectUpload()) {
                    return cleanupDirectUploadSessionWithFinalizationLock(
                            clientId, latestState, retryCount);
                }
                return cleanupLegacyUploadSessionWithFinalizationLock(
                        suid, clientId, latestState, retryCount);
            });
        } catch (IllegalStateException missingOrInvalidState) {
            clearCleanupIndexesOnlyWhenMainStateMissing(clientId);
            log.warn("上传会话 {} 清理时状态已消失或不再可安全清理。", clientId, missingOrInvalidState);
            return false;
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * 锁内状态读取失败后仅在严格回读确认主状态缺失时清理调度索引；损坏或 Redis 错误保留现场。
     */
    private void clearCleanupIndexesOnlyWhenMainStateMissing(String clientId) {
        try {
            if (redisStateManager.getState(clientId) == null) {
                redisStateManager.clearSessionIndexes(clientId);
            }
        } catch (RuntimeException stateReadError) {
            log.warn("清理会话 {} 时无法确认主状态是否缺失，保留全部 Redis 证据。",
                    clientId, stateReadError);
        }
    }

    /**
     * 锁内复核定时清理候选的活动时间和暂停分类，显式用户清理不受截止时间约束。
     */
    private boolean isScheduledCleanupStillEligible(
            FileUploadState state,
            ScheduledCleanupCandidate candidate
    ) {
        if (candidate == null) {
            return true;
        }
        boolean paused = redisStateManager.isSessionPaused(state.getClientId());
        long effectiveLastActivityTime = paused
                ? redisStateManager.getPauseAwareLastActivityTime(state)
                : state.getLastActivityTime();
        return paused == candidate.paused()
                && effectiveLastActivityTime <= candidate.cutoffMillis();
    }

    /**
     * 在已持有会话 finalizer 锁时清理普通上传，只有 DB 和文件系统都安全收敛才删除 Redis。
     */
    private boolean cleanupLegacyUploadSessionWithFinalizationLock(
            String suid,
            String clientId,
            FileUploadState state,
            int retryCount
    ) {
        if (retryCount >= MAX_CLEANUP_RETRIES) {
            retainCleanupForManualReconciliation(state, "普通上传清理已达到重试上限");
            return false;
        }

        CleanupDatabaseDecision databaseDecision = reconcileStableTargetBeforeCleanup(state);
        if (databaseDecision == CleanupDatabaseDecision.RECOVERED
                || databaseDecision == CleanupDatabaseDecision.RETAIN) {
            return false;
        }

        String persistedSuid = state.getSuid();
        String actualSuid = CommonUtils.isNotEmpty(persistedSuid) ? persistedSuid : suid;
        try {
            if (state.getUploadTempPath() != null && state.getProcessedTempPath() != null) {
                cleanupDirectory(Paths.get(state.getUploadTempPath()));
                cleanupDirectory(Paths.get(state.getProcessedTempPath()));
            } else {
                if (CommonUtils.isEmpty(actualSuid)) {
                    throw new IOException("会话缺少可验证的临时目录路径和 SUID");
                }
                cleanupDirectory(getUploadSessionDir(actualSuid, clientId));
                cleanupDirectory(getProcessedSessionDir(actualSuid, clientId));
            }
        } catch (IOException | RuntimeException cleanupError) {
            recordCleanupFailure(state, retryCount, cleanupError);
            return false;
        }

        redisStateManager.removeSession(clientId, actualSuid == null ? "" : actualSuid);
        log.info("普通上传会话 {} 文件与 Redis 状态清理完成。", clientId);
        return true;
    }

    /**
     * 在已持有会话 finalizer 锁时清理或恢复直传会话。
     */
    private boolean cleanupDirectUploadSessionWithFinalizationLock(
            String clientId,
            FileUploadState state,
            int retryCount
    ) {
        if (state.isPrepareStored()
                && (state.getPreparedFileId() == null || state.getPreparedFileId() <= 0)) {
            retainFinalizationForManualReconciliation(
                    state,
                    "旧直传会话已记录 PREPARE 但缺少稳定 preparedFileId");
            return false;
        }
        if (hasDirectFinalizationCheckpoint(state)) {
            return resumeExpiredDirectFinalization(clientId, state);
        }

        CleanupDatabaseDecision databaseDecision = reconcileStableTargetBeforeCleanup(state);
        if (databaseDecision == CleanupDatabaseDecision.RECOVERED
                || databaseDecision == CleanupDatabaseDecision.RETAIN) {
            return false;
        }

        try {
            abortDirectUploadStorage(clientId, state);
            redisStateManager.removeSession(clientId, state.getSuid());
            log.info("直传会话 {} staging 清理完成。", clientId);
            return true;
        } catch (Exception cleanupError) {
            recordCleanupFailure(state, retryCount, cleanupError);
            return false;
        }
    }

    /**
     * 判断直传会话是否已退出自动调度并进入人工清理或人工最终化状态。
     *
     * @param state 直传会话状态
     * @return 已进入人工状态时返回 true
     */
    private boolean isManualReconciliationState(FileUploadState state) {
        return state != null
                && (UPLOAD_SESSION_STATUS_CLEANUP_MANUAL_REQUIRED.equals(state.getStatus())
                    || UPLOAD_SESSION_STATUS_FINALIZATION_MANUAL_REQUIRED.equals(state.getStatus()));
    }

    /**
     * 在删除任何上传证据前收敛稳定 DB 目标，防止遗留 PREPARE 或覆盖不可逆 claim。
     */
    private CleanupDatabaseDecision reconcileStableTargetBeforeCleanup(FileUploadState state) {
        if (!hasCurrentRecoverySchema(state)) {
            retainFinalizationForManualReconciliation(
                    state,
                    "上传会话恢复协议版本缺失或不受支持");
            return CleanupDatabaseDecision.RETAIN;
        }
        if (state.isPrepareStored()
                && (state.getPreparedFileId() == null || state.getPreparedFileId() <= 0)) {
            retainFinalizationForManualReconciliation(
                    state,
                    "旧上传会话已记录 PREPARE 但缺少稳定 preparedFileId");
            return CleanupDatabaseDecision.RETAIN;
        }

        Long stableTargetId = state.getPreparedFileId() != null
                ? state.getPreparedFileId()
                : state.getTargetFileId();
        if (stableTargetId == null) {
            return CleanupDatabaseDecision.ALLOW_DELETE;
        }
        Long userId = state.getUserId();
        Long tenantId = state.getTenantId();
        if (stableTargetId <= 0 || userId == null || userId <= 0 || tenantId == null || tenantId < 0) {
            retainFinalizationForManualReconciliation(state, "稳定 DB 目标身份无效");
            return CleanupDatabaseDecision.RETAIN;
        }

        return TenantContext.callWithTenantIsolation(
                tenantId,
                () -> reconcileStableTargetBeforeCleanup(state, userId, stableTargetId));
    }

    /**
     * 在租户上下文中读取最新 DB 目标并按 SUCCESS、PREPARE claim 或 FAIL 状态收敛。
     */
    private CleanupDatabaseDecision reconcileStableTargetBeforeCleanup(
            FileUploadState state,
            Long userId,
            Long stableTargetId
    ) {
        cn.flying.dao.dto.File persistedFile = fileService.getById(stableTargetId);
        if (persistedFile == null || !Objects.equals(persistedFile.getUid(), userId)) {
            retainFinalizationForManualReconciliation(state, "稳定 DB 目标不存在或不属于当前用户");
            return CleanupDatabaseDecision.RETAIN;
        }
        if (Objects.equals(persistedFile.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            if (!state.isDirectUpload()
                    && state.isPrepareStored()
                    && Objects.equals(state.getPreparedFileId(), stableTargetId)
                    && recoverLegacySuccessFinalization(userId, state.getSuid(), state)) {
                return CleanupDatabaseDecision.RECOVERED;
            }
            retainFinalizationForManualReconciliation(state, "DB SUCCESS 缺少可严格绑定的自动恢复证据");
            return CleanupDatabaseDecision.RETAIN;
        }
        if (!Objects.equals(persistedFile.getStatus(), FileUploadStatus.PREPARE.getCode())
                && !Objects.equals(persistedFile.getStatus(), FileUploadStatus.FAIL.getCode())) {
            retainFinalizationForManualReconciliation(state, "稳定 DB 目标状态不可自动清理");
            return CleanupDatabaseDecision.RETAIN;
        }
        if (Objects.equals(persistedFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            FileService.FinalizationRecoveryPhase phase =
                    fileService.getFinalizationRecoveryPhase(userId, stableTargetId);
            if (phase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING) {
                retainFinalizationForManualReconciliation(
                        state,
                        "DB 最终化 claim 的链结果仍不确定");
                return CleanupDatabaseDecision.RETAIN;
            }
            if (phase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED) {
                if (!state.isDirectUpload()
                        && recoverLegacyAttestedFinalization(userId, state)) {
                    return CleanupDatabaseDecision.RECOVERED;
                }
                state.setStatus(UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING);
                redisStateManager.updateState(state);
                return CleanupDatabaseDecision.RETAIN;
            }
        }
        if (fileService.markFileUploadFailed(userId, stableTargetId)) {
            return CleanupDatabaseDecision.ALLOW_DELETE;
        }

        cn.flying.dao.dto.File latestFile = fileService.getById(stableTargetId);
        if (!state.isDirectUpload()
                && latestFile != null
                && Objects.equals(latestFile.getStatus(), FileUploadStatus.SUCCESS.getCode())
                && state.isPrepareStored()
                && Objects.equals(state.getPreparedFileId(), stableTargetId)
                && recoverLegacySuccessFinalization(userId, state.getSuid(), state)) {
            return CleanupDatabaseDecision.RECOVERED;
        }
        retainFinalizationForManualReconciliation(state, "DB 目标未能安全进入 FAIL");
        return CleanupDatabaseDecision.RETAIN;
    }

    /**
     * 直接消费同 owner 的 durable ATTESTED claim 到 SUCCESS，不重放 Saga、链 RPC 或事件。
     */
    private boolean recoverLegacyAttestedFinalization(
            Long userId,
            FileUploadState state
    ) {
        if (state == null
                || !state.isPrepareStored()
                || state.getPreparedFileId() == null) {
            return false;
        }
        List<File> processedFiles = collectProcessedFiles(
                state.getSuid(), state.getClientId());
        List<String> cipherHashes = collectCipherFileHashes(state, processedFiles);
        if (processedFiles == null || cipherHashes == null) {
            state.setStatus(UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING);
            redisStateManager.updateState(state);
            return false;
        }
        try {
            cn.flying.dao.dto.File recovered = fileService.storeFile(
                    userId,
                    state.getPreparedFileId(),
                    state.getFileName(),
                    processedFiles,
                    cipherHashes,
                    generateFileParam(state),
                    state.getClientId());
            if (recovered == null
                    || !Objects.equals(
                        recovered.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
                return false;
            }
            return recoverLegacySuccessFinalization(userId, state.getSuid(), state);
        } catch (RuntimeException recoveryError) {
            state.setStatus(UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING);
            redisStateManager.updateState(state);
            log.error("消费 legacy ATTESTED claim 到 SUCCESS 失败，将保留 active/state 重试: clientId={}",
                    state.getClientId(), recoveryError);
            return false;
        }
    }

    /**
     * 累加文件或对象存储清理失败次数，达到上限后保留七天人工诊断。
     */
    private void recordCleanupFailure(
            FileUploadState state,
            int retryCount,
            Exception cleanupError
    ) {
        int persistedRetryCount = state.getCleanupRetryCount() == null
                ? retryCount
                : state.getCleanupRetryCount();
        int nextRetryCount = Math.min(MAX_CLEANUP_RETRIES, persistedRetryCount + 1);
        state.setCleanupRetryCount(nextRetryCount);
        if (nextRetryCount >= MAX_CLEANUP_RETRIES) {
            retainCleanupForManualReconciliation(state, cleanupError.getMessage());
            return;
        }
        redisStateManager.updateState(state);
        log.error("上传会话 {} 清理失败，将保留 Redis 状态以便重试: attempt={}/{}, reason={}",
                state.getClientId(), nextRetryCount, MAX_CLEANUP_RETRIES, cleanupError.getMessage());
    }

    /**
     * 将达到重试上限的清理状态转为有界人工诊断，禁止强删唯一恢复入口。
     */
    private void retainCleanupForManualReconciliation(FileUploadState state, String reason) {
        redisStateManager.retainManualReconciliationState(
                state,
                UPLOAD_SESSION_STATUS_CLEANUP_MANUAL_REQUIRED,
                MANUAL_RECONCILIATION_TTL_SECONDS);
        log.error("上传会话清理已转人工处理: clientId={}, reason={}", state.getClientId(), reason);
    }

    /**
     * 将不可自动绑定或恢复的最终化状态保留为七天人工诊断。
     */
    private void retainFinalizationForManualReconciliation(FileUploadState state, String reason) {
        redisStateManager.retainManualReconciliationState(
                state,
                UPLOAD_SESSION_STATUS_FINALIZATION_MANUAL_REQUIRED,
                MANUAL_RECONCILIATION_TTL_SECONDS);
        log.error("上传最终化已转人工对账: clientId={}, preparedFileId={}, reason={}",
                state.getClientId(), state.getPreparedFileId(), reason);
    }

    /**
     * 在当前会话 finalizer 锁内恢复过期直传检查点，避免重复加锁或永久跳过已完成 storage 的会话。
     *
     * @param clientId 上传会话ID
     * @param state 最新可信会话状态
     * @return 最终化已恢复完成时返回 true
     */
    private boolean resumeExpiredDirectFinalization(String clientId, FileUploadState state) {
        try {
            Long userId = state.getUserId();
            Long tenantId = state.getTenantId();
            if (userId == null || userId <= 0 || tenantId == null || tenantId < 0) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传恢复身份无效");
            }
            DirectUploadCompleteRequest recoveryRequest = buildDirectFinalizationRecoveryRequest(state);
            TenantContext.callWithTenantIsolation(
                    tenantId,
                    () -> completeDirectUploadWithFinalizationLock(
                            userId,
                            clientId,
                            recoveryRequest,
                            state));
            log.info("过期直传会话已从可信检查点恢复完成: clientId={}, stage={}",
                    clientId, state.getDirectFinalizationStage());
            return true;
        } catch (RuntimeException recoveryError) {
            FileUploadState latestFailureState = redisStateManager.getState(clientId);
            if (latestFailureState == null) {
                redisStateManager.clearSessionIndexes(clientId);
                log.error("直传恢复失败后主状态已不存在，仅退出调度索引并保留辅助证据: clientId={}",
                        clientId, recoveryError);
                return false;
            }
            if (!hasSameUploadPlan(state, latestFailureState, clientId)) {
                log.error("直传恢复失败后上传计划已变化，禁止用旧快照覆盖最新状态: clientId={}",
                        clientId, recoveryError);
                return false;
            }
            recordDirectFinalizationRecoveryFailure(latestFailureState, recoveryError);
            return false;
        }
    }

    /**
     * 使用已持久化并验证过的完成分片 ETag 构造内部恢复请求，不采信定时任务外部输入。
     *
     * @param state 直传会话状态
     * @return 可重入 complete 的可信请求
     */
    private DirectUploadCompleteRequest buildDirectFinalizationRecoveryRequest(FileUploadState state) {
        List<FileUploadState.DirectUploadCompletedPartState> completedParts = state.getDirectCompletedParts();
        if (completedParts == null
                || completedParts.isEmpty()
                || completedParts.size() != state.getTotalChunks()
                || completedParts.size() > MAX_TOTAL_CHUNKS) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传恢复检查点缺少完整分片证据");
        }

        boolean[] seen = new boolean[state.getTotalChunks()];
        List<DirectUploadCompletePartRequest> recoveryParts = new ArrayList<>(completedParts.size());
        for (FileUploadState.DirectUploadCompletedPartState completedPart : completedParts) {
            if (completedPart == null
                    || completedPart.getIndex() < 0
                    || completedPart.getIndex() >= state.getTotalChunks()
                    || seen[completedPart.getIndex()]
                    || !isSafeDirectUploadEtag(completedPart.getETag())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传恢复分片证据无效");
            }
            seen[completedPart.getIndex()] = true;
            DirectUploadCompletePartRequest recoveryPart = new DirectUploadCompletePartRequest();
            recoveryPart.setIndex(completedPart.getIndex());
            recoveryPart.setETag(completedPart.getETag());
            recoveryParts.add(recoveryPart);
        }
        recoveryParts.sort(Comparator.comparingInt(DirectUploadCompletePartRequest::getIndex));

        DirectUploadCompleteRequest recoveryRequest = new DirectUploadCompleteRequest();
        recoveryRequest.setParts(recoveryParts);
        return recoveryRequest;
    }

    /**
     * 累加直传最终化恢复失败次数；达到上限后退出调度索引并保留有限期诊断。
     *
     * @param state 直传会话状态
     * @param recoveryError 本轮恢复异常
     */
    private void recordDirectFinalizationRecoveryFailure(
            FileUploadState state,
            RuntimeException recoveryError
    ) {
        if (isUploadSessionCompleted(state)) {
            convergeCompletedSessionState(state);
            log.warn("直传恢复已写入完成态但收尾返回异常，保持完成态: clientId={}",
                    state.getClientId(), recoveryError);
            return;
        }
        if (isManualReconciliationState(state)) {
            redisStateManager.clearSessionIndexes(state.getClientId());
            log.warn("直传恢复已进入人工对账终态，禁止失败记录回写为可重试状态: clientId={}",
                    state.getClientId(), recoveryError);
            return;
        }
        if (DIRECT_STAGE_CHAIN_ATTESTING.equals(state.getDirectFinalizationStage())) {
            FileService.FinalizationRecoveryPhase durablePhase =
                    fileService.getFinalizationRecoveryPhase(
                            state.getUserId(), state.getPreparedFileId());
            if (durablePhase == FileService.FinalizationRecoveryPhase.NONE
                    || durablePhase == FileService.FinalizationRecoveryPhase.CLAIMED) {
                retainDirectPreChainFinalizationForRetry(state, durablePhase);
                return;
            }
            if (durablePhase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED
                    || durablePhase == FileService.FinalizationRecoveryPhase.SUCCESS) {
                state.setStatus(UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING);
                redisStateManager.updateState(state);
                return;
            }
            retainDirectFinalizationForManualReconciliation(state,
                    durablePhase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING
                            ? "链存证调用结果不确定，禁止自动重放"
                            : "直传 durable claim 无法安全分类");
            return;
        }

        int currentRetryCount = state.getCleanupRetryCount() == null
                ? 0
                : Math.max(0, state.getCleanupRetryCount());
        int nextRetryCount = Math.min(MAX_CLEANUP_RETRIES, currentRetryCount + 1);
        state.setCleanupRetryCount(nextRetryCount);
        if (nextRetryCount >= MAX_CLEANUP_RETRIES) {
            retainDirectFinalizationForManualReconciliation(state, recoveryError.getMessage());
            return;
        }

        state.setStatus(UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING);
        redisStateManager.updateState(state);
        log.error("直传最终化检查点恢复失败，将在后续清理周期重试: clientId={}, attempt={}/{}",
                state.getClientId(), nextRetryCount, MAX_CLEANUP_RETRIES, recoveryError);
    }

    /**
     * durable NONE/CLAIMED 证明链 RPC 尚未开始；清除旧 Redis 伪边界并保留同 owner 会话安全重试。
     *
     * @param state 当前直传会话状态
     * @param durablePhase DB 中确认的最终化阶段
     */
    private void retainDirectPreChainFinalizationForRetry(
            FileUploadState state,
            FileService.FinalizationRecoveryPhase durablePhase
    ) {
        state.setDirectFinalizationStage(DIRECT_STAGE_PREPARE_STORED);
        state.setStatus(UPLOAD_SESSION_STATUS_FINALIZATION_RECOVERY_PENDING);
        redisStateManager.updateState(state);
        log.warn("直传 durable claim 尚未进入链调用，保留同会话安全重试: clientId={}, preparedFileId={}, phase={}",
                state.getClientId(), state.getPreparedFileId(), durablePhase);
    }

    /**
     * 将不可安全自动恢复的直传会话转入人工对账，并保留有界诊断 TTL。
     *
     * @param state 直传会话状态
     * @param reason 转人工原因
     */
    private void retainDirectFinalizationForManualReconciliation(
            FileUploadState state,
            String reason
    ) {
        retainFinalizationForManualReconciliation(state, reason);
    }

    // === 路径构建辅助方法 ===
    private Path getUploadSessionDir(String SUID, String clientId) {
        Path base = Paths.get(UPLOAD_BASE_DIR).toAbsolutePath().normalize();
        Path resolved = base.resolve(SUID).resolve(clientId).normalize();
        if (!resolved.startsWith(base)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        return resolved;
    }

    /**
     * 递归删除目录及其内容
     */
    private void cleanupDirectory(Path dirPath) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(dirPath)) {
            paths = walk.sorted(Comparator.reverseOrder()).toList();
        } catch (NoSuchFileException e) {
            log.debug("尝试清理目录，但目录不存在: {}", dirPath);
            return;
        }

        for (Path path : paths) {
            try {
                Files.delete(path);
            } catch (NoSuchFileException e) {
                log.debug("清理路径已被并发删除: {}", path);
            }
        }
        log.info("已清理目录: {}", dirPath);
    }

    private Path getProcessedSessionDir(String SUID, String clientId) {
        Path base = Paths.get(PROCESSED_BASE_DIR).toAbsolutePath().normalize();
        Path resolved = base.resolve(SUID).resolve(clientId).normalize();
        if (!resolved.startsWith(base)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        return resolved;
    }

    /**
     * 尝试删除路径，记录错误但不抛出
     */
    private void tryDelete(Path path) {
        try {
            Files.delete(path);
        } catch (NoSuchFileException e) {
            // Ignore
        } catch (DirectoryNotEmptyException e) {
            log.warn("无法删除非空目录 (可能存在并发访问或延迟): {}", path);
        } catch (IOException e) {
            log.error("删除路径失败: {}", path, e);
        }
    }

    /**
     * 处理开始上传请求
     *
     * @throws GeneralException 输入验证失败
     * @throws GeneralException IO 操作失败
     */
    @Override
    public StartUploadVO startUpload(Long userId, String fileName, long fileSize, String contentType,
                                     String clientId, int chunkSize, int totalChunks) {
        return startUpload(userId, fileName, fileSize, contentType, clientId, chunkSize, totalChunks, null);
    }

    /**
     * 处理开始上传请求（支持绑定目标文件ID）。
     *
     * @param userId 用户ID
     * @param fileName 文件名
     * @param fileSize 文件大小
     * @param contentType 文件类型
     * @param clientId 客户端ID
     * @param chunkSize 分片大小
     * @param totalChunks 分片总数
     * @param targetFileId 目标文件ID（版本上传场景可选）
     * @return 上传会话信息
     */
    @Override
    public StartUploadVO startUpload(Long userId, String fileName, long fileSize, String contentType,
                                     String clientId, int chunkSize, int totalChunks, Long targetFileId) {

        //获取加密后的uid，防止数据泄漏
        String uidStr = String.valueOf(userId);
        String SUID = UidEncoder.encodeUid(uidStr);
        Long tenantId = TenantContext.getTenantId();
        String fileClientKey = fileName + "_" + SUID;

        // --- 输入验证 ---
        if (!isValidFileName(fileName)) {
            throw new GeneralException("文件名包含非法字符");
        }
        if (fileSize <= 0) {
            throw new GeneralException("文件大小必须大于0");
        }
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException("文件大小超过限制 (" + (MAX_FILE_SIZE_BYTES / 1024 / 1024 / 1024) + "GB)");
        }
        if (chunkSize <= 0 || chunkSize > MAX_CHUNK_SIZE_BYTES) {
            throw new GeneralException("分片大小必须在 1B 到 " + (MAX_CHUNK_SIZE_BYTES / 1024 / 1024) + "MB 之间");
        }
        validateUploadPlan(fileSize, chunkSize, totalChunks);
        if (!isFileTypeAllowed(fileName, contentType)) {
            throw new GeneralException("不支持的文件类型");
        }
        validateUploadTargetFile(userId, fileName, fileSize, targetFileId);

        boolean hasProvidedClientId = !CommonUtils.isBlank(clientId);

        // --- 显式 clientId：优先按 clientId 幂等恢复 ---
        if (hasProvidedClientId) {
            FileUploadState existingByClientId = redisStateManager.getState(clientId);
            if (existingByClientId != null) {
                validateUploadOwnership(userId, existingByClientId, clientId);
                rejectUnsupportedRecoverySchema(existingByClientId);
                if (!Objects.equals(existingByClientId.getFileName(), fileName) || existingByClientId.getFileSize() != fileSize) {
                    throw new GeneralException("客户端ID与上传会话不匹配");
                }
                if (!isSessionTargetMatched(existingByClientId, targetFileId)) {
                    throw new GeneralException(ResultEnum.PARAM_ERROR, "客户端ID与上传目标不匹配");
                }

                log.info("发现可恢复的上传会话(显式 clientId): 客户端ID={}, 文件客户端键={}", clientId, fileClientKey);
                redisStateManager.removePausedSession(clientId);
                redisStateManager.updateLastActivityTime(clientId);
                return createResumeDto(existingByClientId);
            }
        } else {
            // --- 未提供 clientId：按 fileName + SUID 自动恢复 ---
            String existClientId = redisStateManager.getSessionIdByFileClientKey(
                    tenantId, fileName, SUID);
            if (existClientId != null) {
                FileUploadState existingState = redisStateManager.getState(existClientId);
                if (existingState != null && existingState.getFileSize() == fileSize) {
                    try {
                        validateUploadOwnership(userId, existingState, existClientId);
                    } catch (GeneralException ex) {
                        log.warn("发现旧会话但所有权不匹配，忽略恢复: 旧客户端ID={}, 文件客户端键={}, tenantId={}", existClientId, fileClientKey, tenantId);
                        existingState = null;
                    }
                }
                if (existingState != null && existingState.getFileSize() == fileSize
                        && isSessionTargetMatched(existingState, targetFileId)) {
                    rejectUnsupportedRecoverySchema(existingState);
                    log.info("发现可恢复的上传会话: 客户端ID={}, 文件客户端键={}", existClientId, fileClientKey);
                    redisStateManager.removePausedSession(existClientId); // 恢复会话（如果之前暂停了）
                    redisStateManager.updateLastActivityTime(existClientId);
                    // 返回恢复成功的 DTO
                    return createResumeDto(existingState);
                } else {
                    if (existingState != null && existingState.getFileSize() == fileSize
                            && !isSessionTargetMatched(existingState, targetFileId)) {
                        log.info("发现旧会话但目标文件不匹配，跳过恢复: 旧客户端ID={}, 文件客户端键={}", existClientId, fileClientKey);
                    } else {
                        log.warn("发现旧会话但无法恢复 (状态丢失或文件大小不匹配): 旧客户端ID={}, 文件客户端键={}", existClientId, fileClientKey);
                    }
                    if (existingState != null && existingState.getFileSize() != fileSize) {
                        cleanupUploadSessionInternal(SUID, existClientId); // 主动清理旧状态
                    }
                }
            }

            // 如果未提供，则生成一个新的客户端ID（随机生成，作为客户端凭证）
            clientId = UidEncoder.encodeCid(SUID);
        }

        // 仅在创建新上传会话时执行配额检查；版本续传（绑定 targetFileId）已完成预占位，不重复计费。
        checkQuotaForNewUploadSession(tenantId, userId, fileSize, targetFileId);

        log.info("处理上传开始请求: 文件名={}, 文件大小={}, 内容类型={}, 用户SUID={}, 客户端ID={}",
                fileName, fileSize, contentType, SUID, clientId);

        // --- 创建新会话 ---
        try {
            FileUploadState newState = new FileUploadState(
                    userId, fileName, fileSize, contentType, clientId, chunkSize, totalChunks, targetFileId
            );
            newState.setTenantId(tenantId);
            newState.setRecoverySchemaVersion(CURRENT_RECOVERY_SCHEMA_VERSION);

            // 确保客户端和会话的目录存在
            Path uploadDir = getUploadSessionDir(SUID, clientId);
            Path processedDir = getProcessedSessionDir(SUID, clientId);
            Files.createDirectories(uploadDir);
            Files.createDirectories(processedDir);

            // Persist SUID and paths for cleanup
            newState.setSuid(SUID);
            newState.setUploadTempPath(uploadDir.toString());
            newState.setProcessedTempPath(processedDir.toString());

            redisStateManager.saveNewState(newState, SUID);

            log.info("创建新的上传会话: 客户端ID={}, 文件客户端键={}", SUID, fileClientKey);
            // 返回创建成功的 DTO
            return createNewSessionDto(newState);

        } catch (IOException e) {
            log.error("创建上传会话或目录失败: 文件客户端键={}", fileClientKey, e);
            // 包装成自定义异常，方便 Controller 统一处理
            throw new GeneralException("创建上传会话失败: " + e.getMessage());
        }
    }

    /**
     * Creates a direct-upload session whose chunk bytes are sent to object storage by the frontend.
     *
     * @param userId current user ID
     * @param request direct-upload request
     * @return direct-upload session metadata with presigned URLs
     */
    @Override
    public DirectUploadSessionVO startDirectUpload(Long userId, DirectUploadSessionRequest request) {
        if (request == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }

        Long targetFileId = decodeOptionalFileId(request.getFileId());
        validateDirectUploadRequest(userId, request, targetFileId);

        String suid = UidEncoder.encodeUid(String.valueOf(userId));
        String clientId = CommonUtils.isBlank(request.getClientId())
                ? UidEncoder.encodeCid(suid)
                : request.getClientId();
        RLock finalizationLock = acquireUploadFinalizationLock(clientId);
        try {
            FileUploadState existingState = redisStateManager.getState(clientId);
            if (existingState != null) {
                validateUploadOwnership(userId, existingState, clientId);
                rejectUnsupportedRecoverySchema(existingState);
                if (!existingState.isDirectUpload()) {
                    throw new GeneralException(ResultEnum.PARAM_ERROR, "客户端ID已被普通上传会话占用");
                }
                rejectManualDirectUploadResume(existingState);
                validateDirectUploadResumeRequest(existingState, request, targetFileId);
                redisStateManager.updateLastActivityTime(clientId);
                return toDirectUploadSessionVO(existingState, true);
            }

            Long tenantId = TenantContext.getTenantId();
            checkQuotaForNewUploadSession(tenantId, userId, request.getFileSize(), targetFileId);

            CreateDirectMultipartUploadResponse storageResponse = ResultUtils.getData(
                    fileRemoteClient.createDirectMultipartUpload(toStorageCreateRequest(clientId, request))
            );
            List<FileUploadState.DirectUploadPartState> directUploadParts =
                    validateAndBuildDirectUploadPartStates(clientId, tenantId, request, storageResponse);

            FileUploadState state = new FileUploadState(
                    userId,
                    request.getFileName(),
                    request.getFileSize(),
                    request.getContentType(),
                    clientId,
                    request.getChunkSize(),
                    request.getTotalChunks(),
                    targetFileId
            );
            state.setTenantId(tenantId);
            state.setSuid(suid);
            state.setRecoverySchemaVersion(CURRENT_RECOVERY_SCHEMA_VERSION);
            state.setDirectUpload(true);
            state.setDirectUploadParts(directUploadParts);
            state.setDirectFinalizationStage(DIRECT_STAGE_SESSION_CREATED);

            redisStateManager.saveNewState(state, suid);
            redisStateManager.updateState(state);
            return toDirectUploadSessionVO(state, false);
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * Completes a direct-upload session by validating storage metadata, registering the file, and saving the manifest.
     *
     * @param userId current user ID
     * @param clientId upload session ID
     * @param request completion metadata from the frontend
     * @return completion result
     */
    @Override
    public DirectUploadCompleteVO completeDirectUpload(Long userId, String clientId, DirectUploadCompleteRequest request) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }
        validateUploadOwnership(userId, state, clientId);
        rejectUnsupportedRecoverySchema(state);
        if (!state.isDirectUpload()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "该会话不是直传上传会话");
        }
        rejectManualDirectUploadResume(state);
        rejectUnboundPreparedCheckpoint(state);
        if (isUploadSessionCompleted(state)) {
            convergeCompletedSessionState(state);
            return new DirectUploadCompleteVO(
                    clientId,
                    IdUtils.toExternalId(state.getDirectFileId()),
                    state.getDirectFileHash(),
                    state.getDirectTransactionHash(),
                    state.getDirectManifestHash(),
                    UPLOAD_SESSION_STATUS_COMPLETED
            );
        }
        RLock finalizationLock = acquireUploadFinalizationLock(clientId);
        try {
            return completeDirectUploadWithFinalizationLock(userId, clientId, request, state);
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * 在调用方已持有会话 finalizer 锁时完成直传最终化，供请求与定时恢复复用且避免锁重入。
     *
     * @param userId 上传用户ID
     * @param clientId 上传会话ID
     * @param request 完成分片证据
     * @param expectedState 加锁前的可信会话快照
     * @return 直传完成结果
     */
    private DirectUploadCompleteVO completeDirectUploadWithFinalizationLock(
            Long userId,
            String clientId,
            DirectUploadCompleteRequest request,
            FileUploadState expectedState
    ) {
        FileUploadState state = requireLatestUploadState(userId, clientId, expectedState);
        if (!state.isDirectUpload()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "该会话不是直传上传会话");
        }
        rejectManualDirectUploadResume(state);
        if (isUploadSessionCompleted(state)) {
            convergeCompletedSessionState(state);
            return new DirectUploadCompleteVO(
                    clientId,
                    IdUtils.toExternalId(state.getDirectFileId()),
                    state.getDirectFileHash(),
                    state.getDirectTransactionHash(),
                    state.getDirectManifestHash(),
                    UPLOAD_SESSION_STATUS_COMPLETED
            );
        }
        if (request == null || CommonUtils.isEmpty(request.getParts())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "分片完成元数据不能为空");
        }

        Map<Integer, DirectUploadCompletePartRequest> completedPartMap = indexCompletedParts(request);
        List<DirectMultipartCompletedPart> storageParts = buildStorageCompleteParts(state, completedPartMap);
        CompleteDirectMultipartUploadResponse storageResponse = resolveDirectStorageCompletion(
                userId, clientId, state, completedPartMap, storageParts);
        DirectPreparedFinalizationReservation reservation =
                reserveDirectQuotaPrepareAndAcquireFileLock(userId, state);
        state = reservation.state();
        Long preparedFileId = reservation.preparedFileId();
        RLock preparedFileLock = reservation.preparedFileLock();
        try {
            state = requireLatestPreparedCompletionState(userId, clientId, state, preparedFileId);
            String fileParam = generateDirectUploadFileParam(state);
            cn.flying.dao.dto.File storedFile = resolveDirectStoredFile(
                    userId, state, storageResponse.parts(), fileParam);
            ChunkManifestView manifest = resolveDirectManifest(
                    userId, state, storedFile, storageResponse.parts());

            state.setDirectManifestHash(manifest.manifestHash());
            state.setDirectFinalizationStage(DIRECT_STAGE_MANIFEST_STORED);
            redisStateManager.updateState(state);
            redisStateManager.markCompleted(clientId, state.getSuid(), 300);

            return new DirectUploadCompleteVO(
                    clientId,
                    IdUtils.toExternalId(storedFile.getId()),
                    storedFile.getFileHash(),
                    storedFile.getTransactionHash(),
                    manifest.manifestHash(),
                    UPLOAD_SESSION_STATUS_COMPLETED
            );
        } finally {
            releasePreparedFileFinalizationLock(preparedFileLock, preparedFileId);
        }
    }

    /**
     * Aborts a direct-upload session and removes storage staging objects best-effort.
     *
     * @param userId current user ID
     * @param clientId upload session ID
     * @return true when local session cleanup completed
     */
    @Override
    public boolean abortDirectUpload(Long userId, String clientId) {
        FileUploadState expectedState = redisStateManager.getState(clientId);
        if (expectedState == null) {
            return false;
        }
        validateUploadOwnership(userId, expectedState, clientId);
        if (!expectedState.isDirectUpload()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "该会话不是直传上传会话");
        }

        RLock finalizationLock = acquireUploadFinalizationLock(clientId);
        try {
            FileUploadState latestState = redisStateManager.getState(clientId);
            if (latestState == null) {
                return false;
            }
            validateUploadOwnership(userId, latestState, clientId);
            rejectUnsupportedRecoverySchema(latestState);
            if (!latestState.isDirectUpload()) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "该会话不是直传上传会话");
            }
            if (!hasSameUploadPlan(expectedState, latestState, clientId)) {
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "上传会话状态在取消期间发生变化");
            }
            if (isUploadSessionCompleted(latestState)) {
                convergeCompletedSessionState(latestState);
                return false;
            }
            if (hasDirectFinalizationCheckpoint(latestState)) {
                throw new RetryableException(
                        ResultEnum.SERVICE_UNAVAILABLE,
                        Map.of("reason", "direct upload finalization must be resumed before abort"));
            }
            if (reconcileStableTargetBeforeCleanup(latestState)
                    != CleanupDatabaseDecision.ALLOW_DELETE) {
                return false;
            }
            abortDirectUploadStorage(clientId, latestState);
            redisStateManager.removeSession(clientId, latestState.getSuid());
            return true;
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * Aborts session-scoped direct-upload staging objects and verifies the remote result.
     */
    private void abortDirectUploadStorage(String clientId, FileUploadState state) {
        Long tenantId = state == null ? null : state.getTenantId();
        if (tenantId == null || tenantId < 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传会话 tenantId 无效，禁止清理 staging");
        }
        Boolean aborted = TenantContext.callWithTenantIsolation(
                tenantId,
                () -> ResultUtils.getData(fileRemoteClient.abortDirectMultipartUpload(
                        new AbortDirectMultipartUploadRequest(clientId, buildStorageAbortParts(state))
                )));
        if (!Boolean.TRUE.equals(aborted)) {
            throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "直传 staging 清理失败");
        }
    }

    /**
     * Decodes an optional externally exposed file ID from the direct-upload request.
     */
    private Long decodeOptionalFileId(String fileId) {
        if (CommonUtils.isEmpty(fileId)) {
            return null;
        }
        Long targetFileId = IdUtils.fromExternalId(fileId);
        if (targetFileId == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "fileId 无效");
        }
        return targetFileId;
    }

    /**
     * Validates direct-upload metadata before asking object storage for presigned URLs.
     */
    private void validateDirectUploadRequest(Long userId, DirectUploadSessionRequest request, Long targetFileId) {
        if (!isValidFileName(request.getFileName())) {
            throw new GeneralException("文件名包含非法字符");
        }
        if (request.getFileSize() == null
                || request.getFileSize() <= 0
                || request.getFileSize() > MAX_FILE_SIZE_BYTES) {
            throw new GeneralException(ResultEnum.FILE_MAX_SIZE_OVERFLOW);
        }
        if (request.getChunkSize() == null
                || request.getChunkSize() <= 0
                || request.getChunkSize() > MAX_CHUNK_SIZE_BYTES) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分片大小超出允许范围");
        }
        if (request.getTotalChunks() == null) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分片总数不能为空");
        }
        validateUploadPlan(request.getFileSize(), request.getChunkSize(), request.getTotalChunks());
        if (!isFileTypeAllowed(request.getFileName(), request.getContentType())) {
            throw new GeneralException(ResultEnum.FILE_ACCEPT_NOT_SUPPORT);
        }
        if (CommonUtils.isEmpty(request.getParts()) || request.getParts().size() != request.getTotalChunks()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片元数据数量与上传计划不匹配");
        }
        validateDirectUploadParts(request);
        validateUploadTargetFile(userId, request.getFileName(), request.getFileSize(), targetFileId);
    }

    /**
     * Validates chunk indexes, sizes, and hash metadata for direct upload.
     */
    private void validateDirectUploadParts(DirectUploadSessionRequest request) {
        boolean[] seen = new boolean[request.getTotalChunks()];
        long sizeSum = 0L;
        for (DirectUploadPartRequest part : request.getParts()) {
            if (part == null || part.getIndex() == null
                    || part.getIndex() < 0 || part.getIndex() >= request.getTotalChunks()) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片索引无效");
            }
            if (part.getSize() == null) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片大小不能为空");
            }
            if (seen[part.getIndex()]) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片索引重复");
            }
            seen[part.getIndex()] = true;
            long expectedSize = expectedDirectChunkSize(request.getFileSize(), request.getChunkSize(), part.getIndex(), request.getTotalChunks());
            if (part.getSize() != expectedSize) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片大小与上传计划不匹配");
            }
            if (CommonUtils.isEmpty(part.getPlainHash()) || CommonUtils.isEmpty(part.getCipherHash())) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片哈希不能为空");
            }
            if (part.getPlainHash().length() > 71 || part.getCipherHash().length() > 71) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片哈希长度超出限制");
            }
            String plainHash = normalizeDirectUploadHash(part.getPlainHash());
            String cipherHash = normalizeDirectUploadHash(part.getCipherHash());
            if (!DIRECT_SHA256_PATTERN.matcher(plainHash).matches()
                    || !DIRECT_SHA256_PATTERN.matcher(cipherHash).matches()) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片哈希必须是规范 SHA-256 摘要");
            }
            String checksumAlgorithm = part.getChecksumAlgorithm();
            if (checksumAlgorithm != null && !checksumAlgorithm.isBlank()
                    && (checksumAlgorithm.length() > 16 || !HASH_ALGORITHM.equalsIgnoreCase(checksumAlgorithm.trim()))) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片校验算法仅支持 SHA-256");
            }
            if (!plainHash.equals(cipherHash)) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "未加密直传分片的明文哈希与密文哈希必须一致");
            }
            sizeSum = Math.addExact(sizeSum, part.getSize());
        }
        if (sizeSum != request.getFileSize()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "直传分片总大小与文件大小不匹配");
        }
    }

    /**
     * Validates that an existing direct-upload session is being resumed with the original upload plan.
     */
    private void validateDirectUploadResumeRequest(FileUploadState state,
                                                   DirectUploadSessionRequest request,
                                                   Long targetFileId) {
        if (!Objects.equals(state.getFileName(), request.getFileName())
                || state.getFileSize() != request.getFileSize()
                || !Objects.equals(state.getContentType(), request.getContentType())
                || state.getChunkSize() != request.getChunkSize()
                || state.getTotalChunks() != request.getTotalChunks()
                || !Objects.equals(state.getTargetFileId(), targetFileId)
                || state.getDirectUploadParts() == null
                || state.getDirectUploadParts().size() != request.getTotalChunks()) {
            throw directUploadResumeMismatch();
        }

        Map<Integer, DirectUploadPartRequest> declaredParts = new HashMap<>();
        for (DirectUploadPartRequest part : request.getParts()) {
            declaredParts.put(part.getIndex(), part);
        }
        for (FileUploadState.DirectUploadPartState persistedPart : state.getDirectUploadParts()) {
            DirectUploadPartRequest declaredPart = declaredParts.get(persistedPart.getIndex());
            if (declaredPart == null
                    || persistedPart.getSize() != declaredPart.getSize()
                    || !Objects.equals(
                            normalizeDirectUploadHash(persistedPart.getPlainHash()),
                            normalizeDirectUploadHash(declaredPart.getPlainHash()))
                    || !Objects.equals(
                            normalizeDirectUploadHash(persistedPart.getCipherHash()),
                            normalizeDirectUploadHash(declaredPart.getCipherHash()))
                    || !Objects.equals(
                            normalizeChecksumAlgorithm(persistedPart.getChecksumAlgorithm()),
                            normalizeChecksumAlgorithm(declaredPart.getChecksumAlgorithm()))) {
                throw directUploadResumeMismatch();
            }
        }
    }

    /**
     * Builds the strict resume mismatch error used by all direct-upload resume metadata checks.
     */
    private GeneralException directUploadResumeMismatch() {
        return new GeneralException(ResultEnum.PARAM_ERROR, "客户端ID与直传上传会话不匹配");
    }

    /**
     * Normalizes direct-upload hash declarations before comparing equivalent clear/cipher hashes.
     */
    private String normalizeDirectUploadHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Calculates the expected direct-upload chunk size for a zero-based chunk index.
     */
    private long expectedDirectChunkSize(long fileSize, int chunkSize, int index, int totalChunks) {
        if (index == totalChunks - 1) {
            return fileSize - ((long) chunkSize * index);
        }
        return chunkSize;
    }

    /**
     * Converts the REST request into the storage RPC request.
     */
    private CreateDirectMultipartUploadRequest toStorageCreateRequest(String clientId, DirectUploadSessionRequest request) {
        List<DirectMultipartUploadPartRequest> parts = request.getParts().stream()
                .sorted(Comparator.comparingInt(DirectUploadPartRequest::getIndex))
                .map(part -> new DirectMultipartUploadPartRequest(
                        part.getIndex(),
                        normalizeDirectUploadHash(part.getCipherHash()),
                        part.getSize(),
                        request.getContentType(),
                        normalizeDirectUploadHash(part.getPlainHash()),
                        normalizeDirectUploadHash(part.getCipherHash()),
                        normalizeChecksumAlgorithm(part.getChecksumAlgorithm())
                ))
                .toList();
        return new CreateDirectMultipartUploadRequest(
                clientId,
                request.getFileName(),
                request.getFileSize(),
                request.getChunkSize(),
                request.getContentType(),
                parts
        );
    }

    /**
     * 完整校验 storage 创建响应后构造 Redis 直传计划，任何字段漂移都禁止写入会话。
     */
    private List<FileUploadState.DirectUploadPartState> validateAndBuildDirectUploadPartStates(
            String clientId,
            Long tenantId,
            DirectUploadSessionRequest request,
            CreateDirectMultipartUploadResponse response) {
        if (response == null
                || tenantId == null
                || !Objects.equals(clientId, response.sessionId())
                || response.parts() == null
                || response.parts().size() != request.getTotalChunks()
                || response.parts().size() > MAX_TOTAL_CHUNKS) {
            throw invalidDirectStorageCreateResponse();
        }

        Map<Integer, DirectUploadPartRequest> declaredParts = new HashMap<>();
        for (DirectUploadPartRequest part : request.getParts()) {
            declaredParts.put(part.getIndex(), part);
        }

        long nowEpochSeconds = Instant.now().getEpochSecond();
        Set<Integer> returnedIndexes = new HashSet<>();
        Set<String> returnedStagingObjects = new HashSet<>();
        Set<String> returnedUploadUrls = new HashSet<>();
        List<FileUploadState.DirectUploadPartState> states = new ArrayList<>(response.parts().size());
        for (DirectMultipartUploadPartUrl storagePart : response.parts()) {
            if (storagePart == null
                    || storagePart.partIndex() < 0
                    || storagePart.partIndex() >= request.getTotalChunks()
                    || !returnedIndexes.add(storagePart.partIndex())) {
                throw invalidDirectStorageCreateResponse();
            }
            DirectUploadPartRequest declaredPart = declaredParts.get(storagePart.partIndex());
            String cipherHash = declaredPart == null
                    ? ""
                    : normalizeDirectUploadHash(declaredPart.getCipherHash());
            String expectedStoragePath = "storage/tenant/" + tenantId + "/chunk/" + cipherHash;
            String expectedStagingObject = "tenant/" + tenantId + "/staging/direct-upload/"
                    + clientId + "/part-" + storagePart.partIndex();
            String expectedFinalObject = "tenant/" + tenantId + "/" + cipherHash;
            if (declaredPart == null
                    || storagePart.size() != declaredPart.getSize()
                    || !Objects.equals(expectedStoragePath, storagePart.storagePath())
                    || !Objects.equals(expectedStagingObject, storagePart.stagingObjectName())
                    || !Objects.equals(expectedFinalObject, storagePart.finalObjectName())
                    || !isSafeDirectStorageNode(storagePart.nodeName())
                    || !isSafeDirectUploadUrl(storagePart.uploadUrl())
                    || storagePart.expiresAtEpochSeconds() <= nowEpochSeconds
                    || storagePart.expiresAtEpochSeconds() > nowEpochSeconds + TimeUnit.DAYS.toSeconds(7)
                    || !returnedStagingObjects.add(storagePart.stagingObjectName())
                    || !returnedUploadUrls.add(storagePart.uploadUrl())) {
                throw invalidDirectStorageCreateResponse();
            }
            states.add(new FileUploadState.DirectUploadPartState(
                    storagePart.partIndex(),
                    storagePart.size(),
                    normalizeDirectUploadHash(declaredPart.getPlainHash()),
                    cipherHash,
                    normalizeChecksumAlgorithm(declaredPart.getChecksumAlgorithm()),
                    storagePart.uploadUrl(),
                    storagePart.expiresAtEpochSeconds(),
                    storagePart.storagePath(),
                    storagePart.stagingObjectName(),
                    storagePart.finalObjectName(),
                    storagePart.nodeName()
            ));
        }
        states.sort(Comparator.comparingInt(FileUploadState.DirectUploadPartState::getIndex));
        for (int index = 0; index < states.size(); index++) {
            if (states.get(index).getIndex() != index) {
                throw invalidDirectStorageCreateResponse();
            }
        }
        return states;
    }

    /**
     * 校验预签名 URL 仅使用无凭据、无 fragment 的 HTTP(S) 绝对地址。
     */
    private boolean isSafeDirectUploadUrl(String uploadUrl) {
        if (CommonUtils.isEmpty(uploadUrl) || uploadUrl.length() > 4096) {
            return false;
        }
        try {
            URI uri = URI.create(uploadUrl);
            return uri.isAbsolute()
                    && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && CommonUtils.isNotEmpty(uri.getHost())
                    && uri.getUserInfo() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 校验 storage 节点名是有界安全标识，禁止路径或控制字符进入 Redis 可信计划。
     */
    private boolean isSafeDirectStorageNode(String nodeName) {
        return nodeName != null && DIRECT_STORAGE_NODE_PATTERN.matcher(nodeName).matches();
    }

    /**
     * 构造统一的 storage 创建响应错误，保证调用方不会保存部分可信状态。
     */
    private GeneralException invalidDirectStorageCreateResponse() {
        return new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "对象存储直传创建响应与上传计划不一致");
    }

    /**
     * Converts Redis-persisted state into the public direct-upload session response.
     */
    private DirectUploadSessionVO toDirectUploadSessionVO(FileUploadState state, boolean resumed) {
        List<DirectUploadPartUrlVO> parts = state.getDirectUploadParts().stream()
                .sorted(Comparator.comparingInt(FileUploadState.DirectUploadPartState::getIndex))
                .map(part -> new DirectUploadPartUrlVO(
                        part.getIndex(),
                        part.getSize(),
                        part.getUploadUrl(),
                        part.getExpiresAtEpochSeconds(),
                        part.getStoragePath(),
                        part.getPlainHash(),
                        part.getCipherHash()
                ))
                .toList();
        return new DirectUploadSessionVO(
                state.getClientId(),
                state.getChunkSize(),
                state.getTotalChunks(),
                resumed,
                ChunkManifestCanonicalizer.SCHEMA_ID,
                parts
        );
    }

    /**
     * Indexes completion parts and rejects duplicates.
     */
    private Map<Integer, DirectUploadCompletePartRequest> indexCompletedParts(DirectUploadCompleteRequest request) {
        if (request.getParts().size() > MAX_TOTAL_CHUNKS) {
            throw new GeneralException(ResultEnum.PARAM_ERROR,
                    "直传完成分片数量不能超过 " + MAX_TOTAL_CHUNKS);
        }
        Map<Integer, DirectUploadCompletePartRequest> result = new HashMap<>();
        for (DirectUploadCompletePartRequest part : request.getParts()) {
            if (part == null) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传完成分片不能为空");
            }
            String eTag = part.getETag();
            if (!isSafeDirectUploadEtag(eTag)) {
                throw new GeneralException(ResultEnum.PARAM_ERROR,
                        "直传完成分片 ETag 必须是 1 到 255 个可见 ASCII 字符");
            }
            if (part.getIndex() == null || part.getIndex() < 0
                    || result.put(part.getIndex(), part) != null) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传完成分片索引重复或为空");
            }
        }
        return result;
    }

    /**
     * Builds storage completion metadata from trusted Redis state plus frontend ETags.
     */
    private List<DirectMultipartCompletedPart> buildStorageCompleteParts(
            FileUploadState state,
            Map<Integer, DirectUploadCompletePartRequest> completedPartMap) {
        if (state.getDirectUploadParts() == null
                || state.getDirectUploadParts().size() > MAX_TOTAL_CHUNKS
                || state.getDirectUploadParts().size() != state.getTotalChunks()
                || completedPartMap.size() != state.getTotalChunks()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "直传完成分片数量与上传计划不匹配");
        }

        List<DirectMultipartCompletedPart> parts = new ArrayList<>(state.getDirectUploadParts().size());
        for (FileUploadState.DirectUploadPartState part : state.getDirectUploadParts()) {
            DirectUploadCompletePartRequest completedPart = completedPartMap.get(part.getIndex());
            if (completedPart == null) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "直传完成缺少分片: " + part.getIndex());
            }
            parts.add(toStorageCompletedPart(part, completedPart.getETag()));
        }
        parts.sort(Comparator.comparingInt(DirectMultipartCompletedPart::partIndex));
        return parts;
    }

    /**
     * 复用已校验的 storage 检查点，或首次调用 storage 并在任何上链动作前持久化完整证据。
     */
    private CompleteDirectMultipartUploadResponse resolveDirectStorageCompletion(
            Long userId,
            String clientId,
            FileUploadState state,
            Map<Integer, DirectUploadCompletePartRequest> completedPartMap,
            List<DirectMultipartCompletedPart> storageParts
    ) {
        CompleteDirectMultipartUploadResponse candidate;
        if (state.getDirectCompletedParts() != null && !state.getDirectCompletedParts().isEmpty()) {
            candidate = restoreDirectStorageCompletion(clientId, state);
        } else {
            recheckQuotaBeforeFinalProcessing(userId, state);
            candidate = ResultUtils.getData(fileRemoteClient.completeDirectMultipartUpload(
                    new CompleteDirectMultipartUploadRequest(clientId, storageParts)));
        }

        CompleteDirectMultipartUploadResponse validated = validateDirectStorageCompletion(
                clientId, state, completedPartMap, candidate);
        if (state.getDirectCompletedParts() == null || state.getDirectCompletedParts().isEmpty()) {
            state.setContentHash(validated.contentHash());
            state.setDirectCompletedParts(toDirectCompletedPartStates(validated.parts()));
            state.setDirectFinalizationStage(DIRECT_STAGE_STORAGE_COMPLETED);
            redisStateManager.updateState(state);
        }
        return validated;
    }

    /**
     * 从 Redis 检查点重建 storage 完成响应，重试不再依赖远端重复返回相同元数据。
     */
    private CompleteDirectMultipartUploadResponse restoreDirectStorageCompletion(
            String clientId,
            FileUploadState state
    ) {
        List<DirectMultipartCompletedPartVO> parts = state.getDirectCompletedParts().stream()
                .map(part -> new DirectMultipartCompletedPartVO(
                        part.getIndex(),
                        part.getStoragePath(),
                        part.getSize(),
                        part.getETag(),
                        part.getPlainHash(),
                        part.getCipherHash(),
                        part.getChecksumAlgorithm()))
                .toList();
        return new CompleteDirectMultipartUploadResponse(clientId, state.getContentHash(), parts);
    }

    /**
     * 将 storage 完成响应逐字段绑定到 Redis 上传计划，并分别校验 staging 与 final ETag。
     */
    private CompleteDirectMultipartUploadResponse validateDirectStorageCompletion(
            String clientId,
            FileUploadState state,
            Map<Integer, DirectUploadCompletePartRequest> completedPartMap,
            CompleteDirectMultipartUploadResponse response
    ) {
        if (response == null
                || !Objects.equals(clientId, response.sessionId())
                || response.parts() == null
                || response.parts().size() != state.getTotalChunks()
                || response.parts().size() > MAX_TOTAL_CHUNKS) {
            throw invalidDirectStorageCompletion();
        }

        String contentHash;
        try {
            contentHash = requireContentHash(response.contentHash());
        } catch (GeneralException exception) {
            throw invalidDirectStorageCompletion();
        }
        if (CommonUtils.isNotEmpty(state.getContentHash())
                && !Objects.equals(requireContentHash(state.getContentHash()), contentHash)) {
            throw invalidDirectStorageCompletion();
        }

        Map<Integer, FileUploadState.DirectUploadPartState> trustedPlan = indexTrustedDirectParts(state);
        Map<Integer, DirectMultipartCompletedPartVO> returnedParts = new HashMap<>();
        long returnedSize = 0L;
        for (DirectMultipartCompletedPartVO returnedPart : response.parts()) {
            if (returnedPart == null
                    || returnedPart.partIndex() < 0
                    || returnedPart.partIndex() >= state.getTotalChunks()
                    || returnedParts.put(returnedPart.partIndex(), returnedPart) != null) {
                throw invalidDirectStorageCompletion();
            }
            FileUploadState.DirectUploadPartState trustedPart = trustedPlan.get(returnedPart.partIndex());
            DirectUploadCompletePartRequest declaredPart = completedPartMap.get(returnedPart.partIndex());
            if (!matchesDirectStoragePart(trustedPart, declaredPart, returnedPart)) {
                throw invalidDirectStorageCompletion();
            }
            try {
                returnedSize = Math.addExact(returnedSize, returnedPart.size());
            } catch (ArithmeticException e) {
                throw invalidDirectStorageCompletion();
            }
        }
        if (returnedSize != state.getFileSize() || returnedParts.size() != trustedPlan.size()) {
            throw invalidDirectStorageCompletion();
        }

        if (state.getTotalChunks() == 1) {
            String singlePartHash = normalizeDirectUploadHash(
                    trustedPlan.get(0) == null ? null : trustedPlan.get(0).getPlainHash());
            if (singlePartHash.matches("^sha256:[0-9a-f]{64}$")
                    && !Objects.equals(singlePartHash, contentHash)) {
                throw invalidDirectStorageCompletion();
            }
        }

        List<DirectMultipartCompletedPartVO> orderedParts = returnedParts.values().stream()
                .sorted(Comparator.comparingInt(DirectMultipartCompletedPartVO::partIndex))
                .toList();
        return new CompleteDirectMultipartUploadResponse(clientId, contentHash, orderedParts);
    }

    /**
     * 为可信 Redis 直传计划建立唯一索引，并拒绝缺片、重复或越界状态。
     */
    private Map<Integer, FileUploadState.DirectUploadPartState> indexTrustedDirectParts(FileUploadState state) {
        if (state.getDirectUploadParts() == null
                || state.getDirectUploadParts().size() != state.getTotalChunks()
                || state.getDirectUploadParts().size() > MAX_TOTAL_CHUNKS) {
            throw invalidDirectStorageCompletion();
        }
        Map<Integer, FileUploadState.DirectUploadPartState> indexed = new HashMap<>();
        for (FileUploadState.DirectUploadPartState part : state.getDirectUploadParts()) {
            if (part == null
                    || part.getIndex() < 0
                    || part.getIndex() >= state.getTotalChunks()
                    || indexed.put(part.getIndex(), part) != null) {
                throw invalidDirectStorageCompletion();
            }
        }
        return indexed;
    }

    /**
     * 核对单个 storage 完成分片的索引外全部可信字段。
     * 前端 ETag 只证明 staging 上传并原样传给 storage；provider 完成后返回的 final ETag 独立校验并进入检查点。
     */
    private boolean matchesDirectStoragePart(
            FileUploadState.DirectUploadPartState trustedPart,
            DirectUploadCompletePartRequest declaredPart,
            DirectMultipartCompletedPartVO returnedPart
    ) {
        return trustedPart != null
                && declaredPart != null
                && returnedPart.size() == trustedPart.getSize()
                && Objects.equals(returnedPart.storagePath(), trustedPart.getStoragePath())
                && isCanonicalDirectUploadHash(returnedPart.plainHash())
                && isCanonicalDirectUploadHash(trustedPart.getPlainHash())
                && Objects.equals(
                        normalizeDirectUploadHash(returnedPart.plainHash()),
                        normalizeDirectUploadHash(trustedPart.getPlainHash()))
                && isCanonicalDirectUploadHash(returnedPart.cipherHash())
                && isCanonicalDirectUploadHash(trustedPart.getCipherHash())
                && Objects.equals(
                        normalizeDirectUploadHash(returnedPart.cipherHash()),
                        normalizeDirectUploadHash(trustedPart.getCipherHash()))
                && isSupportedDirectChecksum(returnedPart.checksumAlgorithm())
                && isSupportedDirectChecksum(trustedPart.getChecksumAlgorithm())
                && normalizeChecksumAlgorithm(returnedPart.checksumAlgorithm())
                        .equalsIgnoreCase(normalizeChecksumAlgorithm(trustedPart.getChecksumAlgorithm()))
                && isSafeDirectUploadEtag(declaredPart.getETag())
                && isSafeDirectUploadEtag(returnedPart.eTag());
    }

    /**
     * 校验直传摘要是有界规范 SHA-256，避免异常 RPC 响应放大字符串处理成本。
     */
    private boolean isCanonicalDirectUploadHash(String value) {
        return value != null
                && value.length() <= 71
                && DIRECT_SHA256_PATTERN.matcher(normalizeDirectUploadHash(value)).matches();
    }

    /**
     * 校验直传 checksum 是有界 SHA-256 标识，拒绝异常 RPC 或旧状态中的算法漂移。
     */
    private boolean isSupportedDirectChecksum(String value) {
        return value != null
                && value.length() <= 16
                && HASH_ALGORITHM.equalsIgnoreCase(value.trim());
    }

    /**
     * 将已校验的 storage 响应复制为 Redis 可序列化检查点。
     */
    private List<FileUploadState.DirectUploadCompletedPartState> toDirectCompletedPartStates(
            List<DirectMultipartCompletedPartVO> completedParts
    ) {
        return completedParts.stream()
                .map(part -> new FileUploadState.DirectUploadCompletedPartState(
                        part.partIndex(),
                        part.storagePath(),
                        part.size(),
                        part.eTag(),
                        part.plainHash(),
                        part.cipherHash(),
                        normalizeChecksumAlgorithm(part.checksumAlgorithm())))
                .toList();
    }

    /**
     * 构造统一的 storage 完成证据错误，确保调用方不会继续上链或发布 manifest。
     */
    private GeneralException invalidDirectStorageCompletion() {
        return new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "对象存储直传完成证据与上传计划不一致");
    }

    /**
     * 校验 ETag 仅由有界可见 ASCII 字符组成，阻断空白、控制字符和日志换行。
     */
    private boolean isSafeDirectUploadEtag(String eTag) {
        if (eTag == null || eTag.length() < 1 || eTag.length() > 255) {
            return false;
        }
        for (int index = 0; index < eTag.length(); index++) {
            char value = eTag.charAt(index);
            if (value < 0x21 || value > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds storage abort metadata from trusted Redis state.
     */
    private List<DirectMultipartCompletedPart> buildStorageAbortParts(FileUploadState state) {
        return state.getDirectUploadParts().stream()
                .map(part -> toStorageCompletedPart(part, null))
                .toList();
    }

    /**
     * Converts one direct-upload part state into storage completion/abort metadata.
     */
    private DirectMultipartCompletedPart toStorageCompletedPart(FileUploadState.DirectUploadPartState part, String eTag) {
        return new DirectMultipartCompletedPart(
                part.getIndex(),
                part.getStagingObjectName(),
                part.getFinalObjectName(),
                part.getNodeName(),
                part.getStoragePath(),
                part.getSize(),
                eTag,
                part.getPlainHash(),
                part.getCipherHash(),
                normalizeChecksumAlgorithm(part.getChecksumAlgorithm())
        );
    }

    /**
     * Builds a manifest draft from completed storage metadata and final file record.
     */
    private ChunkManifestDraft buildChunkManifestDraft(
            cn.flying.dao.dto.File storedFile,
            FileUploadState state,
            List<DirectMultipartCompletedPartVO> completedParts) {
        List<ChunkManifestChunk> chunks = completedParts.stream()
                .sorted(Comparator.comparingInt(DirectMultipartCompletedPartVO::partIndex))
                .map(part -> new ChunkManifestChunk(
                        part.partIndex(),
                        part.plainHash(),
                        part.cipherHash(),
                        part.size(),
                        part.storagePath(),
                        "S3",
                        part.eTag(),
                        normalizeChecksumAlgorithm(part.checksumAlgorithm())
                ))
                .toList();
        return new ChunkManifestDraft(
                ChunkManifestCanonicalizer.SCHEMA_ID,
                storedFile.getFileHash(),
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                state.getChunkSize(),
                state.getFileSize(),
                null,
                "NONE",
                "S3",
                chunks
        );
    }

    /**
     * Generates direct-upload file parameters for download/decrypt metadata.
     */
    private String generateDirectUploadFileParam(FileUploadState state) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileName", state.getFileName());
        params.put("fileSize", state.getFileSize());
        params.put("contentType", state.getContentType());
        params.put("uploadTime", state.getStartTime());
        params.put("chunkCount", state.getTotalChunks());
        params.put("uploadMode", "DIRECT_MULTIPART");
        params.put("encryptionAlgorithm", "NONE");
        params.put("contentHash", requireContentHash(state.getContentHash()));
        return JsonConverter.toJson(params);
    }

    /**
     * Applies the default checksum algorithm used by the P2-3 manifest contract.
     */
    private String normalizeChecksumAlgorithm(String checksumAlgorithm) {
        if (CommonUtils.isEmpty(checksumAlgorithm)) {
            return ChunkManifestCanonicalizer.HASH_ALGORITHM;
        }
        String normalized = checksumAlgorithm.trim();
        return HASH_ALGORITHM.equalsIgnoreCase(normalized) ? HASH_ALGORITHM : normalized;
    }

    /**
     * 校验版本上传场景中绑定的目标文件是否合法。
     *
     * @param userId 用户ID
     * @param fileName 上传文件名
     * @param fileSize 上传文件大小
     * @param targetFileId 目标文件ID
     */
    private void validateUploadTargetFile(Long userId, String fileName, long fileSize, Long targetFileId) {
        if (targetFileId == null) {
            return;
        }
        cn.flying.dao.dto.File targetFile = fileService.getById(targetFileId);
        if (targetFile == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        if (!Objects.equals(targetFile.getUid(), userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
        if (!Objects.equals(targetFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            throw new GeneralException(ResultEnum.VERSION_SOURCE_INVALID, "目标版本状态不允许上传");
        }
        if (!Objects.equals(targetFile.getFileName(), fileName)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "上传文件名与目标版本不一致");
        }

        Long targetFileSize = targetFile.getFileSize();
        if (fileSize <= 0
                || targetFileSize == null
                || targetFileSize <= 0
                || targetFileSize.longValue() != fileSize) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "上传文件大小与目标版本不一致");
        }
    }

    /**
     * 校验客户端声明的上传分片计划与文件大小一致。
     *
     * @param fileSize 文件总字节数
     * @param chunkSize 分片字节数
     * @param totalChunks 分片总数
     */
    private void validateUploadPlan(long fileSize, int chunkSize, int totalChunks) {
        if (totalChunks <= 0 || totalChunks > MAX_TOTAL_CHUNKS) {
            throw new GeneralException("分片总数必须在 1 到 " + MAX_TOTAL_CHUNKS + " 之间");
        }
        int expectedChunks = (int) ((fileSize + chunkSize - 1) / chunkSize);
        if (totalChunks != expectedChunks) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分片总数与文件大小不匹配");
        }
    }

    /**
     * 校验上传分片的实际大小必须符合创建会话时确定的分片计划。
     *
     * @param state 上传会话状态
     * @param chunkNumber 分片序号
     * @param actualSize 当前上传分片字节数
     */
    private void validateUploadedChunkSize(FileUploadState state, int chunkNumber, long actualSize) {
        long expectedSize = expectedChunkSize(state, chunkNumber);
        if (expectedSize <= 0) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "分片计划无效");
        }
        if (actualSize != expectedSize) {
            throw new GeneralException(ResultEnum.PARAM_ERROR,
                    "分片大小与上传计划不匹配: 序号=" + chunkNumber
                            + ", 期望=" + expectedSize + ", 实际=" + actualSize);
        }
    }

    /**
     * 根据上传计划计算指定分片应有的字节数。
     *
     * @param state 上传会话状态
     * @param chunkNumber 分片序号
     * @return 该分片应有字节数
     */
    private long expectedChunkSize(FileUploadState state, int chunkNumber) {
        if (chunkNumber == state.getTotalChunks() - 1) {
            return state.getFileSize() - ((long) state.getChunkSize() * chunkNumber);
        }
        return state.getChunkSize();
    }

    /**
     * 判断上传会话绑定的目标文件是否与当前请求一致。
     *
     * @param state 上传会话状态
     * @param targetFileId 请求中目标文件ID
     * @return 是否一致
     */
    private boolean isSessionTargetMatched(FileUploadState state, Long targetFileId) {
        return Objects.equals(state.getTargetFileId(), targetFileId);
    }

    /**
     * 将上传目标文件回写为失败状态。
     *
     * @param userId 用户ID
     * @param state 上传会话状态
     * @return DB 目标已安全进入 FAIL、允许删除会话恢复入口时返回 true
     */
    private boolean markUploadTargetFailed(Long userId, FileUploadState state) {
        if (state.getPreparedFileId() != null) {
            return fileService.markFileUploadFailed(userId, state.getPreparedFileId());
        }
        if (state.getTargetFileId() != null) {
            return fileService.markFileUploadFailed(userId, state.getTargetFileId());
        }
        // 没有稳定主键时禁止按文件名批量回写，避免污染同名历史版本。
        return false;
    }

    /**
     * DB 已安全进入 FAIL 后严格删除普通上传两类临时目录，最后才移除 Redis 恢复入口。
     */
    private void cleanupFailedLegacyFinalization(
            Long userId,
            FileUploadState state,
            String suid
    ) {
        if (!markUploadTargetFailed(userId, state)) {
            return;
        }
        try {
            cleanupLegacyTemporaryFilesStrict(state);
        } catch (IOException | RuntimeException cleanupError) {
            log.warn("普通上传失败态已收敛但临时目录清理失败，保留 active/state 重试: clientId={}",
                    state.getClientId(), cleanupError);
            throw new RetryableException(ResultEnum.SERVICE_UNAVAILABLE, cleanupError);
        }
        redisStateManager.removeSession(state.getClientId(), suid);
    }

    /**
     * 使用会话持久化的规范路径严格清理原始与密文临时目录，目录不存在按幂等成功处理。
     */
    private void cleanupLegacyTemporaryFilesStrict(FileUploadState state) throws IOException {
        if (state == null
                || CommonUtils.isEmpty(state.getUploadTempPath())
                || CommonUtils.isEmpty(state.getProcessedTempPath())) {
            throw new IOException("上传会话缺少可验证的临时目录路径");
        }
        cleanupDirectory(Paths.get(state.getUploadTempPath()));
        cleanupDirectory(Paths.get(state.getProcessedTempPath()));
    }

    /**
     * 处理分片上传
     *
     * @throws GeneralException 会话不存在
     * @throws GeneralException 上传已暂停
     * @throws GeneralException 输入验证失败
     * @throws GeneralException IO 操作失败
     * @throws GeneralException 安全相关操作失败 (如哈希)
     */
    public void uploadChunk(Long userId, String clientId, int chunkNumber, MultipartFile file) {
        RLock finalizationLock = acquireUploadFinalizationLock(clientId);
        try {
            uploadChunkWithFinalizationLock(userId, clientId, chunkNumber, file);
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * 在会话 finalizer 锁内重读状态、落盘原始分片并提交 Redis 上传证据。
     */
    private void uploadChunkWithFinalizationLock(
            Long userId,
            String clientId,
            int chunkNumber,
            MultipartFile file
    ) {
        //获取加密后的uid，防止数据泄漏
        String SUID = UidEncoder.encodeUid(String.valueOf(userId));

        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);
        rejectUnsupportedRecoverySchema(state);

        if (redisStateManager.isSessionPaused(clientId)) {
            throw new GeneralException("上传已暂停，请先恢复上传: 客户端ID=" + clientId);
        }

        redisStateManager.updateLastActivityTime(clientId); // 更新活动时间

        if (!isValidChunkNumber(chunkNumber, state.getTotalChunks())) {
            throw new GeneralException("无效的分片序号: 序号=" + chunkNumber + ", 总数=" + state.getTotalChunks());
        }
        if (file.isEmpty()) {
            throw new GeneralException("上传的分片不能为空: 客户端ID=" + clientId + ", 序号=" + chunkNumber);
        }
        validateUploadedChunkSize(state, chunkNumber, file.getSize());

        // --- 优化：边保存边计算哈希 ---
        Path chunkPath = getChunkUploadPath(SUID, clientId, chunkNumber);
        Path rawTaskPath = chunkPath.resolveSibling(
                chunkPath.getFileName() + "." + UUID.randomUUID() + ".uploading");
        String calculatedHashBase64;
        boolean rawPublished = false;
        boolean uploadedEvidenceCommitted = false;

        try {
            if (state.getUploadedChunks().contains(chunkNumber)
                    || state.getProcessedChunks().contains(chunkNumber)) {
                String persistedHash = state.getChunkHashes().get("chunk_" + chunkNumber);
                String incomingHash = calculateMultipartHashBase64(file);
                if (CommonUtils.isEmpty(persistedHash)
                        || !Objects.equals(persistedHash, incomingHash)) {
                    throw new GeneralException(
                            ResultEnum.FILE_UPLOAD_ERROR,
                            "同一分片重复上传内容与既有哈希不一致");
                }
                if (state.getProcessedChunks().contains(chunkNumber)) {
                    validateProcessedChunkEvidence(
                            state,
                            chunkNumber,
                            getChunkProcessedPath(SUID, clientId, chunkNumber));
                    log.info("分片 {} 已处理且证据完整，幂等返回: 客户端ID={}", chunkNumber, clientId);
                    return;
                }
                if (!Files.isRegularFile(chunkPath)) {
                    throw new GeneralException(
                            ResultEnum.FILE_UPLOAD_ERROR,
                            "已上传分片缺少可恢复的原始文件或哈希证据");
                }
                processChunkImmediately(SUID, state, chunkNumber, chunkPath, persistedHash);
                log.info("分片 {} 已上传但尚未处理，已幂等排队处理: 客户端ID={}", chunkNumber, clientId);
                return;
            }

            log.debug("开始保存分片: 路径={}", chunkPath);
            Files.createDirectories(chunkPath.getParent()); // 确保目录存在

            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            long bytesWritten;
            try (InputStream inputStream = file.getInputStream();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest);
                 FileChannel rawChannel = FileChannel.open(
                         rawTaskPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream outputStream = Channels.newOutputStream(rawChannel)) {

                bytesWritten = digestInputStream.transferTo(outputStream);
                outputStream.flush();
                rawChannel.force(true);
            }

            if (bytesWritten != file.getSize()) {
                log.warn("写入字节数 ({}) 与文件大小 ({}) 不符: 分片={}, 客户端ID={}",
                        bytesWritten, file.getSize(), chunkNumber, clientId);
                // 可以考虑是否需要抛异常
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR);
            }

            byte[] hashBytes = digest.digest();
            calculatedHashBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);

            Files.move(
                    rawTaskPath,
                    chunkPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            rawPublished = true;

            log.info("分片 {} 保存成功: 客户端ID={}, 大小={}, 哈希={}", chunkNumber, clientId, bytesWritten, calculatedHashBase64);

            // 使用原子操作同时更新已上传分片集合和分片哈希，确保高并发下的数据一致性
            if (!redisStateManager.addUploadedChunkWithHash(clientId, chunkNumber, calculatedHashBase64)) {
                log.error("Redis 原子更新失败: 客户端ID={}, 分片={}", clientId, chunkNumber);
                throw new GeneralException("更新上传状态失败");
            }
            uploadedEvidenceCommitted = true;

            // --- 触发异步处理 ---
            processChunkImmediately(SUID, state, chunkNumber, chunkPath, calculatedHashBase64);

            updateUploadProgress(state, "上传分片 " + chunkNumber);

        } catch (NoSuchAlgorithmException e) {
            log.error("哈希算法 {} 不可用!", HASH_ALGORITHM, e);
            tryDelete(rawTaskPath);
            cleanupRawChunkOnlyWhenEvidenceAbsent(
                    clientId, chunkNumber, chunkPath, rawPublished, uploadedEvidenceCommitted);
            throw new GeneralException("内部服务器错误：哈希算法不可用");
        } catch (IOException e) {
            log.error("保存或哈希分片 {} 失败: 客户端ID={}", chunkNumber, state.getClientId(), e);
            tryDelete(rawTaskPath);
            cleanupRawChunkOnlyWhenEvidenceAbsent(
                    clientId, chunkNumber, chunkPath, rawPublished, uploadedEvidenceCommitted);
            throw new GeneralException("保存分片失败: " + e.getMessage());
        } catch (Exception e) { // 捕获其他潜在异常
            log.error("处理分片 {} 时发生未知错误: 客户端ID={}", chunkNumber, state.getClientId(), e);
            tryDelete(rawTaskPath);
            cleanupRawChunkOnlyWhenEvidenceAbsent(
                    clientId, chunkNumber, chunkPath, rawPublished, uploadedEvidenceCommitted);

            // 未与稳定 DB 目标和 finalizer 状态共同收敛前，保留 Redis 恢复入口。
            throw new RuntimeException("处理分片时发生未知错误: " + e.getMessage(), e);
        }
    }

    /**
     * 仅在严格回读确认 Redis 未提交上传证据时删除本次发布的原始分片。
     */
    private void cleanupRawChunkOnlyWhenEvidenceAbsent(
            String clientId,
            int chunkNumber,
            Path chunkPath,
            boolean rawPublished,
            boolean evidenceCommitted
    ) {
        if (!rawPublished || evidenceCommitted) {
            return;
        }
        try {
            FileUploadState latestState = redisStateManager.getState(clientId);
            if (latestState != null
                    && (latestState.getUploadedChunks().contains(chunkNumber)
                    || latestState.getProcessedChunks().contains(chunkNumber)
                    || latestState.getChunkHashes().containsKey("chunk_" + chunkNumber))) {
                log.warn("上传证据写响应不确定但回读发现已提交，保留原始分片: clientId={}, chunk={}",
                        clientId, chunkNumber);
                return;
            }
            tryDelete(chunkPath);
        } catch (RuntimeException readError) {
            log.warn("无法确认上传证据是否提交，保留原始分片供重试: clientId={}, chunk={}",
                    clientId, chunkNumber, readError);
        }
    }


    // === 私有辅助方法 ===

    /**
     * 获取上传进度
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public ProgressVO getUploadProgress(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);

        redisStateManager.updateLastActivityTime(clientId);
        ProgressInfo progressInfo = calculateProgressInfo(state);
        boolean paused = redisStateManager.isSessionPaused(clientId);

        // 确定状态字符串
        String status;
        if (UPLOAD_SESSION_STATUS_COMPLETED.equals(state.getStatus())) {
            // 如果状态已标记为 completed，直接返回完成
            status = UPLOAD_SESSION_STATUS_COMPLETED;
        } else if (paused) {
            status = "paused";
        } else if (progressInfo.totalChunks > 0 && progressInfo.processedCount == progressInfo.totalChunks) {
            status = "completed";
        } else if (progressInfo.processedCount > 0) {
            status = "processing";
        } else if (progressInfo.uploadedCount > 0) {
            status = "uploading";
        } else {
            status = "pending";
        }

        ProgressVO responseDto = new ProgressVO(progressInfo.totalProgress,
                progressInfo.uploadProgressPercent, progressInfo.processProgressPercent,
                progressInfo.uploadedCount, progressInfo.processedCount, progressInfo.totalChunks,
                clientId, status
        );

        log.debug("获取进度成功: 客户端ID={}, 总进度={}%", clientId, progressInfo.totalProgress);
        return responseDto;
    }

    /**
     * 取消上传并清理资源
     *
     * @return 如果找到并清理了会话则返回 true，否则返回 false
     */
    public boolean cancelUpload(Long userId, String clientId) {
        String SUID = UidEncoder.encodeUid(String.valueOf(userId));
        log.info("收到取消上传请求: 客户端ID={}", clientId);
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            return cleanupUploadSessionInternal(SUID, clientId);
        }
        validateUploadOwnership(userId, state, clientId);
        return cleanupUploadSessionInternal(SUID, clientId); // 内部方法处理查找和清理
    }

    /**
     * 完成文件上传处理
     *
     * @throws GeneralException 会话不存在
     * @throws GeneralException 上传未完成（分片未处理完）
     * @throws GeneralException IO 操作失败
     */
    public void completeUpload(Long userId, String clientId) {

        String uidStr = String.valueOf(userId);
        String SUID = UidEncoder.encodeUid(uidStr);

        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }
        validateUploadOwnership(userId, state, clientId);
        rejectUnsupportedRecoverySchema(state);
        if (isUploadSessionCompleted(state)) {
            convergeCompletedSessionState(state, SUID);
            log.info("上传会话已完成，忽略重复完成请求: 客户端ID={}, 文件名={}", clientId, state.getFileName());
            return;
        }

        RLock finalizationLock = acquireUploadFinalizationLock(clientId);
        try {
            completeUploadWithFinalizationLock(userId, clientId, SUID, state);
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * 在会话级 finalizer 锁内完成普通上传，保证同一会话最多一个调用发布存储事件。
     *
     * @param userId 当前用户ID
     * @param clientId 上传会话ID
     * @param SUID 编码后的用户ID
     * @param expectedState 加锁前读取的会话快照
     */
    private void completeUploadWithFinalizationLock(
            Long userId,
            String clientId,
            String SUID,
            FileUploadState expectedState
    ) {
        FileUploadState state = requireLatestUploadState(userId, clientId, expectedState);
        rejectUnboundPreparedCheckpoint(state);
        if (isUploadSessionCompleted(state)) {
            convergeCompletedSessionState(state, SUID);
            log.info("上传会话已被并发完成，忽略重复完成请求: 客户端ID={}", clientId);
            return;
        }
        if (recoverLegacySuccessFinalization(userId, SUID, state)) {
            return;
        }

        log.info("处理完成上传请求: 客户端ID={}, 文件名={}", clientId, state.getFileName());
        redisStateManager.updateLastActivityTime(clientId);

        // 检查是否所有分片都已处理
        int expectedChunks = state.getTotalChunks();
        boolean allProcessed = state.getProcessedChunks().size() == expectedChunks;

        if (!allProcessed) {
            log.warn("请求完成上传时，并非所有分片都已处理: 客户端ID={}, 已处理={}, 总数={}",
                    clientId, state.getProcessedChunks().size(), expectedChunks);
            int requeuedChunks = requeueMissingProcessedChunks(SUID, state);
            // 异步分片也必须先拿同一 finalizer 锁；锁内等待会形成 complete 与 async 互等。
            // 先从持久化 uploaded/hash/raw 证据恢复丢失的内存任务，再立即返回并释放锁。
            throw new RetryableException(ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of(
                            "processed", state.getProcessedChunks().size(),
                            "expected", expectedChunks,
                            "requeued", requeuedChunks));
        }

        state = requireLatestCompletionState(userId, clientId, state, expectedChunks);
        if (isUploadSessionCompleted(state)) {
            convergeCompletedSessionState(state, SUID);
            log.info("上传会话已被并发完成，忽略重复完成请求: 客户端ID={}", clientId);
            return;
        }
        if (recoverLegacySuccessFinalization(userId, SUID, state)) {
            return;
        }

        try {
            // 在不可逆文件变更前先做一次配额复核，避免超限时污染分片尾部元数据。
            recheckQuotaBeforeFinalProcessing(userId, state);

            // --- 执行最终步骤 (追加下一个分片的密钥) ---
            completeFileProcessing(SUID, state);

            // 原始分片删除前按顺序计算可信整文件摘要，删除后的幂等重试复用该状态值。
            String contentHash = resolveOriginalContentHash(SUID, state);
            state.setContentHash(contentHash);
            redisStateManager.updateState(state);

            // --- 清理原始上传分片 ---
            Path uploadSessionDir = getUploadSessionDir(SUID, clientId);
            cleanupDirectory(uploadSessionDir);
            log.info("原始上传分片目录已清理: {}", uploadSessionDir);

            // 在租户级锁内完成“配额复核 + PREPARE 入库”，避免并发完成阶段绕过限额。
            state = reserveQuotaAndPrepareStoreFile(userId, state);

            log.info("文件上传和处理流程完成: 客户端ID={}, 文件名={}", clientId, state.getFileName());

            // 收集处理后的文件和哈希值
            List<File> processedFiles = collectProcessedFiles(SUID, clientId);
            if (processedFiles == null) {
                log.error("收集处理后的文件失败，无法继续存证流程: 客户端ID={}, 文件名={}", clientId, state.getFileName());
                cleanupFailedLegacyFinalization(userId, state, SUID);
                throw new GeneralException("收集处理后的文件失败，文件存证中止");
            }

            List<String> fileHashes = collectCipherFileHashes(state, processedFiles);
            if (fileHashes == null) {
                log.error("收集文件哈希值失败，无法继续存证流程: 客户端ID={}, 文件名={}", clientId, state.getFileName());
                cleanupFailedLegacyFinalization(userId, state, SUID);
                throw new GeneralException("收集文件哈希值失败，文件存证中止");
            }

            // 验证文件数量与哈希数量一致
            if (processedFiles.size() != fileHashes.size()) {
                log.error("处理后的文件数量({})与哈希值数量({})不匹配: 客户端ID={}, 文件名={}",
                        processedFiles.size(), fileHashes.size(), clientId, state.getFileName());
                cleanupFailedLegacyFinalization(userId, state, SUID);
                throw new GeneralException("文件数量与哈希数量不匹配，文件存证中止");
            }

            log.info("成功收集文件和哈希值: 客户端ID={}, 文件名={}, 分片数量={}",
                    clientId, state.getFileName(), processedFiles.size());

            if (eventPublisher == null) {
                log.error("事件发布器未初始化，无法发送文件存证事件: 客户端ID={}, 文件名={}", clientId, state.getFileName());
                cleanupFailedLegacyFinalization(userId, state, SUID);
                throw new GeneralException("事件发布器未初始化，文件存证中止");
            }
            publishFileStorageEventAndMarkCompleted(userId, SUID, state, processedFiles, fileHashes);

        } catch (IOException e) {
            log.error("完成文件处理或清理时失败: 客户端ID={}", clientId, e);
            // 保留已持久化的内容摘要与会话检查点，下一次 finalizer 可安全重试目录清理。
            throw new GeneralException("完成处理失败：" + e.getMessage());
        } catch (RetryableException e) {
            // DB SUCCESS 后补 Redis 完成态失败时必须保留会话，下一次 complete 可安全恢复。
            throw e;
        } catch (GeneralException e) {
            // GeneralException已经包含了状态清理逻辑，直接重新抛出
            throw e;
        } catch (Exception e) {
            log.error("完成文件处理时发生未知错误: 客户端ID={}", clientId, e);
            // 未证明 DB 最终化和版本链可安全回滚前，不删除唯一 Redis 恢复入口。
            throw new RuntimeException("完成处理时发生未知错误：" + e.getMessage(), e);
        }
    }

    /**
     * 同步发布文件存证事件，并且仅在监听器成功返回后标记 Redis 上传完成态。
     *
     * @param userId 上传用户ID
     * @param SUID 编码后的用户ID
     * @param state 上传会话状态
     * @param processedFiles 已处理分片
     * @param fileHashes 分片哈希
     */
    private void publishFileStorageEventAndMarkCompleted(
            Long userId,
            String SUID,
            FileUploadState state,
            List<File> processedFiles,
            List<String> fileHashes
    ) {
        try {
            eventPublisher.publishEvent(new FileStorageEvent(
                    this,
                    state.getTenantId(),
                    userId,
                    state.getPreparedFileId(),
                    state.getFileName(),
                    SUID,
                    state.getClientId(),
                    processedFiles,
                    fileHashes,
                    generateFileParam(state)
            ));
        } catch (RuntimeException storageError) {
            cleanupFailedLegacyFinalization(userId, state, SUID);
            throw storageError;
        }
        log.info("文件存证事件已同步完成: 用户={}, 文件名={}, 分片数量={}",
                userId, state.getFileName(), processedFiles.size());

        try {
            cleanupLegacyTemporaryFilesStrict(state);
            redisStateManager.markCompleted(state.getClientId(), SUID, 300);
            log.info("上传完成，已标记Redis状态为completed: 客户端ID={}", state.getClientId());
        } catch (IOException | RuntimeException completionStateError) {
            log.warn("标记Redis状态为completed失败，保留 DB SUCCESS 并要求客户端安全重试: 客户端ID={}",
                    state.getClientId(), completionStateError);
            throw new RetryableException(ResultEnum.SERVICE_UNAVAILABLE, completionStateError);
        }
    }

    /**
     * 非阻塞获取上传会话 finalizer 锁；未指定租期以启用 Redisson watchdog 自动续约。
     *
     * @param clientId 上传会话ID
     * @return 当前线程持有的会话锁
     */
    private RLock acquireUploadFinalizationLock(String clientId) {
        RLock lock;
        try {
            lock = redissonClient.getLock(UPLOAD_FINALIZATION_LOCK_KEY_PREFIX + clientId);
            if (lock == null || !lock.tryLock()) {
                throw new RetryableException(
                        ResultEnum.SERVICE_UNAVAILABLE,
                        Map.of("reason", "upload finalization is already in progress"));
            }
        } catch (RetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("获取上传会话 finalizer 锁失败: clientId={}", clientId, e);
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "upload finalization lock is unavailable"));
        }
        return lock;
    }

    /**
     * 直接释放上传会话 finalizer 锁，避免额外所有权查询失败时跳过 unlock 并永久续租。
     *
     * @param lock 会话锁
     * @param clientId 上传会话ID
     */
    private void releaseUploadFinalizationLock(RLock lock, String clientId) {
        if (lock == null) {
            return;
        }
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            log.debug("上传会话 finalizer 锁已不属于当前线程: clientId={}", clientId);
        } catch (RuntimeException e) {
            log.warn("释放上传会话 finalizer 锁失败: clientId={}", clientId, e);
        }
    }

    /**
     * 有界等待稳定 PREPARE 文件级最终化锁，成功后使用 Redisson watchdog 自动续租。
     *
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @return 当前线程持有的文件级锁
     */
    private RLock acquirePreparedFileFinalizationLock(Long preparedFileId) {
        RLock lock;
        String lockKey = PREPARED_FILE_FINALIZATION_LOCK_KEY_PREFIX + preparedFileId;
        try {
            lock = redissonClient.getLock(lockKey);
            if (lock == null || !lock.tryLock(
                    PREPARED_FILE_FINALIZATION_LOCK_WAIT_SECONDS,
                    TimeUnit.SECONDS)) {
                throw new RetryableException(
                        ResultEnum.SERVICE_UNAVAILABLE,
                        Map.of("reason", "prepared file finalization is already in progress"));
            }
            return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "prepared file finalization lock wait was interrupted"));
        } catch (RetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("获取 PREPARE 文件最终化锁失败: lockKey={}", lockKey, e);
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "prepared file finalization lock is unavailable"));
        }
    }

    /**
     * 直接释放 PREPARE 文件级最终化锁，非持有异常表示锁已过期或已被释放。
     *
     * @param lock 文件级最终化锁
     * @param preparedFileId 稳定 PREPARE 文件ID
     */
    private void releasePreparedFileFinalizationLock(RLock lock, Long preparedFileId) {
        if (lock == null) {
            return;
        }
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            log.debug("PREPARE 文件最终化锁已不属于当前线程: preparedFileId={}", preparedFileId);
        } catch (RuntimeException e) {
            log.warn("释放 PREPARE 文件最终化锁失败: preparedFileId={}", preparedFileId, e);
        }
    }

    /**
     * 有界等待租户配额完成锁，获取成功后由 Redisson watchdog 自动续租。
     *
     * @param tenantId 租户ID
     * @return 当前线程持有的租户配额锁
     */
    private RLock acquireQuotaCompletionLock(Long tenantId) {
        String lockKey = QUOTA_COMPLETE_LOCK_KEY_PREFIX + tenantId;
        try {
            RLock lock = redissonClient.getLock(lockKey);
            if (lock == null || !lock.tryLock(QUOTA_COMPLETE_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new RetryableException(
                        ResultEnum.SERVICE_UNAVAILABLE,
                        Map.of("reason", "quota completion lock is busy"));
            }
            return lock;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "quota completion lock wait was interrupted"));
        } catch (RetryableException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("获取上传完成配额锁失败: lockKey={}", lockKey, e);
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "quota completion lock is unavailable"));
        }
    }

    /**
     * 直接释放租户配额完成锁，避免 ownership 查询成为 watchdog 解锁的单点故障。
     *
     * @param lock 租户配额锁
     * @param tenantId 租户ID
     */
    private void releaseQuotaCompletionLock(RLock lock, Long tenantId) {
        if (lock == null) {
            return;
        }
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            log.debug("租户配额完成锁已不属于当前线程: tenantId={}", tenantId);
        } catch (RuntimeException e) {
            log.warn("释放上传完成配额锁失败: tenantId={}", tenantId, e);
        }
    }

    /**
     * 按“会话锁 -> 租户配额锁 -> PREPARE 文件锁”顺序完成直传预占位，并把文件锁交给最终化阶段持有。
     *
     * @param userId 用户ID
     * @param state 当前直传会话状态
     * @return 已持有 PREPARE 文件锁的最终化预留
     */
    private DirectPreparedFinalizationReservation reserveDirectQuotaPrepareAndAcquireFileLock(
            Long userId,
            FileUploadState state
    ) {
        Long tenantId = resolveTenantId(state);
        RLock quotaLock = acquireQuotaCompletionLock(tenantId);
        RLock preparedFileLock = null;
        Long preparedFileId = null;
        try {
            FileUploadState latestState = requireLatestUploadState(userId, state.getClientId(), state);
            if (!latestState.isDirectUpload()) {
                throw new GeneralException(ResultEnum.PARAM_ERROR, "该会话不是直传上传会话");
            }
            if (!latestState.isPrepareStored() && shouldCheckQuotaForSession(latestState)) {
                quotaService.checkUploadQuota(tenantId, userId, latestState.getFileSize());
            }
            if (latestState.getPreparedFileId() == null) {
                rejectUnsupportedRecoverySchema(latestState);
                preparedFileId = latestState.getTargetFileId() != null
                        ? latestState.getTargetFileId()
                        : IdUtils.nextEntityId();
                if (preparedFileId == null || preparedFileId <= 0) {
                    throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                            "无法分配稳定 PREPARE 文件ID");
                }
                latestState.setPreparedFileId(preparedFileId);
                latestState.setDirectFinalizationStage(DIRECT_STAGE_PREPARE_ID_ALLOCATED);
                redisStateManager.updateState(latestState);
            } else {
                preparedFileId = requirePreparedFileId(latestState);
            }

            preparedFileLock = acquirePreparedFileFinalizationLock(preparedFileId);
            if (!latestState.isPrepareStored()) {
                cn.flying.dao.dto.File preparedFile = fileService.prepareStoreFileWithStableId(
                        userId,
                        latestState.getTargetFileId(),
                        preparedFileId,
                        latestState.getFileName(),
                        latestState.getFileSize());
                validatePreparedFile(latestState, preparedFile, userId);
                latestState.setDirectFinalizationStage(DIRECT_STAGE_PREPARE_STORED);
                latestState.setPrepareStored(true);
                redisStateManager.updateState(latestState);
            }
            return new DirectPreparedFinalizationReservation(
                    latestState, preparedFileId, preparedFileLock);
        } catch (RuntimeException exception) {
            releasePreparedFileFinalizationLock(preparedFileLock, preparedFileId);
            throw exception;
        } finally {
            releaseQuotaCompletionLock(quotaLock, tenantId);
        }
    }

    /**
     * 直传配额预留结果；PREPARE 文件锁必须由调用方在 manifest 和完成检查点之后释放。
     */
    private record DirectPreparedFinalizationReservation(
            FileUploadState state,
            Long preparedFileId,
            RLock preparedFileLock
    ) {
    }

    /**
     * 在租户级分布式锁内执行配额复核并写入 PREPARE 元数据，保证完成阶段的原子性。
     *
     * @param userId 用户ID
     * @param state 上传会话状态
     * @return 已持久化 PREPARE 标记的最新会话状态
     */
    private FileUploadState reserveQuotaAndPrepareStoreFile(Long userId, FileUploadState state) {
        Long tenantId = resolveTenantId(state);
        RLock lock = acquireQuotaCompletionLock(tenantId);
        try {
            FileUploadState latestState = requireLatestUploadState(
                    userId, state.getClientId(), state);
            if (latestState.isPrepareStored()) {
                if (latestState.getPreparedFileId() == null) {
                    throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                            "上传会话缺少稳定 PREPARE 文件ID");
                }
                log.info("上传会话已完成 PREPARE 落库，跳过重复预占位: clientId={}", latestState.getClientId());
                return latestState;
            }

            if (shouldCheckQuotaForSession(latestState)) {
                quotaService.checkUploadQuota(tenantId, userId, latestState.getFileSize());
            }
            if (latestState.getPreparedFileId() == null) {
                rejectUnsupportedRecoverySchema(latestState);
                Long preparedFileId = latestState.getTargetFileId() != null
                        ? latestState.getTargetFileId()
                        : IdUtils.nextEntityId();
                if (preparedFileId == null || preparedFileId <= 0) {
                    throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                            "无法分配稳定 PREPARE 文件ID");
                }
                latestState.setPreparedFileId(preparedFileId);
                if (latestState.isDirectUpload()) {
                    latestState.setDirectFinalizationStage(DIRECT_STAGE_PREPARE_ID_ALLOCATED);
                }
                redisStateManager.updateState(latestState);
            }
            cn.flying.dao.dto.File preparedFile = fileService.prepareStoreFileWithStableId(
                    userId,
                    latestState.getTargetFileId(),
                    latestState.getPreparedFileId(),
                    latestState.getFileName(),
                    latestState.getFileSize());
            validatePreparedFile(latestState, preparedFile, userId);
            if (latestState.isDirectUpload()) {
                latestState.setDirectFinalizationStage(DIRECT_STAGE_PREPARE_STORED);
            }
            latestState.setPrepareStored(true);
            redisStateManager.updateState(latestState);
            return latestState;
        } finally {
            releaseQuotaCompletionLock(lock, tenantId);
        }
    }

    /**
     * 校验 PREPARE 服务返回值确实绑定当前直传会话分配的稳定主键。
     */
    private void validatePreparedFile(
            FileUploadState state,
            cn.flying.dao.dto.File preparedFile,
            Long userId
    ) {
        if (preparedFile == null
                || !Objects.equals(preparedFile.getId(), state.getPreparedFileId())
                || !Objects.equals(preparedFile.getUid(), userId)
                || !Objects.equals(preparedFile.getTenantId(), state.getTenantId())
                || (state.getTargetFileId() != null
                    && !Objects.equals(state.getTargetFileId(), state.getPreparedFileId()))
                || !Objects.equals(preparedFile.getFileName(), state.getFileName())
                || !Objects.equals(preparedFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "PREPARE 文件与上传会话不一致");
        }
        Long preparedSize = preparedFile.getFileSize();
        if (state.getFileSize() <= 0
                || preparedSize == null
                || preparedSize <= 0
                || preparedSize.longValue() != state.getFileSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "PREPARE 文件大小与上传会话不一致");
        }
    }

    /**
     * 读取并校验会话稳定 PREPARE 文件ID。
     */
    private Long requirePreparedFileId(FileUploadState state) {
        Long preparedFileId = state == null ? null : state.getPreparedFileId();
        if (preparedFileId == null || preparedFileId <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "上传会话缺少稳定 PREPARE 文件ID");
        }
        return preparedFileId;
    }

    /**
     * 获取文件级锁后重读会话，并验证稳定 PREPARE 绑定未被并发替换。
     */
    private FileUploadState requireLatestPreparedCompletionState(
            Long userId,
            String clientId,
            FileUploadState expectedState,
            Long preparedFileId
    ) {
        FileUploadState latestState = requireLatestUploadState(userId, clientId, expectedState);
        if (!latestState.isPrepareStored()
                || !Objects.equals(latestState.getPreparedFileId(), preparedFileId)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "上传会话 PREPARE 绑定发生变化");
        }
        return latestState;
    }

    /**
     * 按 Redis 与 DB claim 检查点恢复链结果和 DB SUCCESS，避免重试重复上链或按文件名串单。
     */
    private cn.flying.dao.dto.File resolveDirectStoredFile(
            Long userId,
            FileUploadState state,
            List<DirectMultipartCompletedPartVO> completedParts,
            String fileParam
    ) {
        Long preparedFileId = state.getPreparedFileId();
        if (preparedFileId == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传会话缺少稳定 PREPARE 文件ID");
        }

        cn.flying.dao.dto.File persistedFile = fileService.getById(preparedFileId);
        validateDirectFileIdentity(state, persistedFile, userId);
        if (Objects.equals(persistedFile.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            recoverDirectSuccessCheckpoint(state, persistedFile);
            redisStateManager.updateState(state);
            return persistedFile;
        }
        if (!Objects.equals(persistedFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传目标文件状态不可恢复");
        }

        StoreFileResponse chainResult = resolveDirectChainCheckpoint(
                userId, state, completedParts, fileParam);
        cn.flying.dao.dto.File storedFile = fileService.persistDirectUploadedFile(
                userId,
                preparedFileId,
                state.getFileName(),
                state.getFileSize(),
                fileParam,
                chainResult,
                state.getClientId());
        validateDirectStoredFile(state, storedFile, userId, chainResult);
        state.setDirectFileId(storedFile.getId());
        state.setDirectFileHash(storedFile.getFileHash());
        state.setDirectTransactionHash(storedFile.getTransactionHash());
        state.setDirectFinalizationStage(DIRECT_STAGE_FILE_STORED);
        redisStateManager.updateState(state);
        return storedFile;
    }

    /**
     * 复用已保存的链结果；首次链边界只由 FileService 的 durable claim 表达，Redis 不预写伪 ATTESTING。
     */
    private StoreFileResponse resolveDirectChainCheckpoint(
            Long userId,
            FileUploadState state,
            List<DirectMultipartCompletedPartVO> completedParts,
            String fileParam
    ) {
        boolean hasFileHash = CommonUtils.isNotEmpty(state.getDirectFileHash());
        boolean hasTransactionHash = CommonUtils.isNotEmpty(state.getDirectTransactionHash());
        if (hasFileHash != hasTransactionHash) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传链检查点不完整");
        }
        if (hasFileHash) {
            if (!hasTrustedDirectChainStage(state.getDirectFinalizationStage())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传链结果与最终化阶段不一致");
            }
            return new StoreFileResponse(state.getDirectTransactionHash(), state.getDirectFileHash());
        }
        if ((DIRECT_STAGE_FILE_STORED.equals(state.getDirectFinalizationStage())
                || DIRECT_STAGE_MANIFEST_STORED.equals(state.getDirectFinalizationStage()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传最终化阶段缺少链结果");
        }

        StoreFileResponse chainResult;
        try {
            chainResult = fileService.attestDirectUploadedFile(
                    userId,
                    state.getPreparedFileId(),
                    state.getFileName(),
                    completedParts,
                    fileParam,
                    state.getClientId());
        } catch (RuntimeException attestationError) {
            chainResult = recoverDirectChainResultAfterAttestationFailure(
                    userId,
                    state,
                    completedParts,
                    fileParam,
                    attestationError);
        }
        if (chainResult == null
                || CommonUtils.isEmpty(chainResult.fileHash())
                || CommonUtils.isEmpty(chainResult.transactionHash())) {
            retainDirectFinalizationForManualReconciliation(
                    state,
                    "区块链存储返回无效且不可安全重放的结果");
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "区块链存储返回无效结果");
        }
        state.setDirectFileHash(chainResult.fileHash());
        state.setDirectTransactionHash(chainResult.transactionHash());
        state.setDirectFinalizationStage(DIRECT_STAGE_CHAIN_ATTESTED);
        redisStateManager.updateState(state);
        return chainResult;
    }

    /**
     * 首次链调用异常后按 durable claim 分类：NONE/CLAIMED 可安全重试、ATTESTED 可消费、ATTESTING 转人工。
     */
    private StoreFileResponse recoverDirectChainResultAfterAttestationFailure(
            Long userId,
            FileUploadState state,
            List<DirectMultipartCompletedPartVO> completedParts,
            String fileParam,
            RuntimeException attestationError
    ) {
        cn.flying.dao.dto.File persistedFile;
        FileService.FinalizationRecoveryPhase durablePhase;
        try {
            persistedFile = fileService.getById(state.getPreparedFileId());
            validateDirectFileIdentity(state, persistedFile, userId);
            if (Objects.equals(persistedFile.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
                recoverDirectSuccessCheckpoint(state, persistedFile);
                redisStateManager.updateState(state);
                return new StoreFileResponse(
                        persistedFile.getTransactionHash(), persistedFile.getFileHash());
            }
            durablePhase = fileService.getFinalizationRecoveryPhase(
                    userId, state.getPreparedFileId());
        } catch (RuntimeException recoveryReadError) {
            retainDirectFinalizationForManualReconciliation(
                    state, "链调用异常后 durable claim 无法读取或绑定");
            throw uncertainDirectChainResult(attestationError, recoveryReadError);
        }
        if (!Objects.equals(persistedFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            retainDirectFinalizationForManualReconciliation(
                    state, "链调用异常后 DB 目标状态不可恢复");
            throw uncertainDirectChainResult(attestationError, null);
        }
        if (durablePhase == FileService.FinalizationRecoveryPhase.NONE
                || durablePhase == FileService.FinalizationRecoveryPhase.CLAIMED) {
            retainDirectPreChainFinalizationForRetry(state, durablePhase);
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "direct finalization remains safely retryable before chain"));
        }
        if (durablePhase == FileService.FinalizationRecoveryPhase.CHAIN_ATTESTING) {
            retainDirectFinalizationForManualReconciliation(
                    state, "链调用结果不确定，禁止自动重放");
            throw new GeneralException(
                    ResultEnum.BLOCKCHAIN_ERROR,
                    "直传链调用结果不确定，已转人工对账");
        }
        if (durablePhase != FileService.FinalizationRecoveryPhase.CHAIN_ATTESTED) {
            retainDirectFinalizationForManualReconciliation(
                    state, "直传 durable claim 不足以证明链结果可恢复");
            throw uncertainDirectChainResult(attestationError, null);
        }
        try {
            StoreFileResponse recovered = fileService.attestDirectUploadedFile(
                    userId,
                    state.getPreparedFileId(),
                    state.getFileName(),
                    completedParts,
                    fileParam,
                    state.getClientId());
            if (recovered == null
                    || CommonUtils.isEmpty(recovered.fileHash())
                    || CommonUtils.isEmpty(recovered.transactionHash())) {
                throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "durable claim 链结果无效");
            }
            return recovered;
        } catch (RuntimeException recoveryError) {
            retainDirectFinalizationForManualReconciliation(
                    state,
                    "链调用异常后 durable claim 无法安全恢复");
            log.error("直传链调用异常后已失败关闭并保留人工诊断: clientId={}",
                    state.getClientId(), recoveryError);
            throw uncertainDirectChainResult(attestationError, recoveryError);
        }
    }

    /**
     * 构造直传链结果无法自动证明时的统一失败，并保留原始调用类型供诊断。
     */
    private GeneralException uncertainDirectChainResult(
            RuntimeException attestationError,
            RuntimeException recoveryError
    ) {
        GeneralException failure = new GeneralException(
                ResultEnum.FILE_RECORD_ERROR,
                Map.of(
                        "reason", "直传链结果不确定，已转人工对账",
                        "cause", attestationError.getClass().getSimpleName()));
        if (recoveryError != null) {
            failure.addSuppressed(recoveryError);
        }
        return failure;
    }

    /**
     * 仅允许链已确认及其合法后继阶段复用 tx/fileHash，拒绝早期阶段注入伪造链结果。
     */
    private boolean hasTrustedDirectChainStage(String stage) {
        return DIRECT_STAGE_CHAIN_ATTESTED.equals(stage)
                || DIRECT_STAGE_FILE_STORED.equals(stage)
                || DIRECT_STAGE_MANIFEST_STORED.equals(stage);
    }

    /**
     * 从 DB SUCCESS 恢复 Redis 文件检查点，覆盖 DB 已提交但会话尚未更新的崩溃窗口。
     */
    private void recoverDirectSuccessCheckpoint(
            FileUploadState state,
            cn.flying.dao.dto.File persistedFile
    ) {
        requireDirectSuccessEvidence(state, persistedFile);
        state.setDirectFileId(persistedFile.getId());
        state.setDirectFileHash(persistedFile.getFileHash());
        state.setDirectTransactionHash(persistedFile.getTransactionHash());
        state.setDirectFinalizationStage(DIRECT_STAGE_FILE_STORED);
    }

    /**
     * 校验稳定主键加载到的文件属于当前会话，禁止同名或跨用户记录被借用。
     */
    private void validateDirectFileIdentity(
            FileUploadState state,
            cn.flying.dao.dto.File file,
            Long userId
    ) {
        if (file == null
                || !Objects.equals(file.getId(), state.getPreparedFileId())
                || !Objects.equals(file.getUid(), userId)
                || !Objects.equals(file.getTenantId(), state.getTenantId())
                || (state.getTargetFileId() != null
                    && !Objects.equals(state.getTargetFileId(), state.getPreparedFileId()))
                || !Objects.equals(file.getFileName(), state.getFileName())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "稳定 PREPARE 文件与直传会话不一致");
        }
        Long fileSize = file.getFileSize();
        if (state.getFileSize() <= 0
                || fileSize == null
                || fileSize <= 0
                || fileSize.longValue() != state.getFileSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传文件大小与会话不一致");
        }
    }

    /**
     * 校验 DB SUCCESS 中的链结果和内容摘要可作为当前 clientId 的恢复证据。
     */
    private void requireDirectSuccessEvidence(
            FileUploadState state,
            cn.flying.dao.dto.File file
    ) {
        String contentHash = requireContentHash(file.getContentHash());
        if (!Objects.equals(contentHash, requireContentHash(state.getContentHash()))
                || CommonUtils.isEmpty(file.getFileHash())
                || CommonUtils.isEmpty(file.getTransactionHash())
                || (CommonUtils.isNotEmpty(state.getDirectFileHash())
                    && !Objects.equals(state.getDirectFileHash(), file.getFileHash()))
                || (CommonUtils.isNotEmpty(state.getDirectTransactionHash())
                    && !Objects.equals(state.getDirectTransactionHash(), file.getTransactionHash()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传 SUCCESS 证据与会话检查点不一致");
        }
    }

    /**
     * 校验本轮 DB 推进结果完整且与已保存链检查点一致。
     */
    private void validateDirectStoredFile(
            FileUploadState state,
            cn.flying.dao.dto.File storedFile,
            Long userId,
            StoreFileResponse chainResult
    ) {
        validateDirectFileIdentity(state, storedFile, userId);
        if (!Objects.equals(storedFile.getStatus(), FileUploadStatus.SUCCESS.getCode())
                || !Objects.equals(storedFile.getFileHash(), chainResult.fileHash())
                || !Objects.equals(storedFile.getTransactionHash(), chainResult.transactionHash())
                || !Objects.equals(requireContentHash(storedFile.getContentHash()),
                        requireContentHash(state.getContentHash()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传文件落库结果与链检查点不一致");
        }
    }

    /**
     * 复用相同 canonical hash 的 active manifest，覆盖 manifest 已提交但 Redis 未更新的窗口。
     */
    private ChunkManifestView resolveDirectManifest(
            Long userId,
            FileUploadState state,
            cn.flying.dao.dto.File storedFile,
            List<DirectMultipartCompletedPartVO> completedParts
    ) {
        ChunkManifestDraft draft = buildChunkManifestDraft(storedFile, state, completedParts);
        String expectedManifestHash = chunkManifestService.calculateManifestHash(draft);
        if (CommonUtils.isEmpty(expectedManifestHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "无法计算直传 manifest hash");
        }

        Optional<ChunkManifestView> activeManifest = chunkManifestService.findActiveManifest(
                userId, storedFile.getId());
        ChunkManifestView manifest = activeManifest.orElseGet(() ->
                chunkManifestService.saveManifest(userId, storedFile.getId(), draft));
        if (manifest == null
                || !Objects.equals(manifest.fileId(), storedFile.getId())
                || !Objects.equals(manifest.fileHash(), storedFile.getFileHash())
                || !Objects.equals(manifest.manifestHash(), expectedManifestHash)
                || (CommonUtils.isNotEmpty(state.getDirectManifestHash())
                    && !Objects.equals(state.getDirectManifestHash(), manifest.manifestHash()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "直传 manifest 与会话检查点不一致");
        }
        return manifest;
    }

    /**
     * 判断直传会话是否已经产生必须通过 complete 恢复的阶段检查点。
     */
    private boolean hasDirectFinalizationCheckpoint(FileUploadState state) {
        if (state == null || !state.isDirectUpload()) {
            return false;
        }
        String stage = state.getDirectFinalizationStage();
        return state.isPrepareStored()
                || state.getPreparedFileId() != null
                || CommonUtils.isNotEmpty(state.getContentHash())
                || (state.getDirectCompletedParts() != null && !state.getDirectCompletedParts().isEmpty())
                || state.getDirectFileId() != null
                || CommonUtils.isNotEmpty(state.getDirectFileHash())
                || CommonUtils.isNotEmpty(state.getDirectTransactionHash())
                || CommonUtils.isNotEmpty(state.getDirectManifestHash())
                || (CommonUtils.isNotEmpty(stage) && !DIRECT_STAGE_SESSION_CREATED.equals(stage));
    }

    /**
     * 获取上传会话的最新状态，并校验身份与不可变上传计划未发生漂移。
     *
     * @param userId 当前用户ID
     * @param clientId 客户端会话ID
     * @param expectedState 当前调用链中的可信状态快照
     * @return 身份和上传计划一致的最新状态
     */
    private FileUploadState requireLatestUploadState(
            Long userId,
            String clientId,
            FileUploadState expectedState
    ) {
        if (expectedState == null || CommonUtils.isEmpty(clientId)) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }
        FileUploadState latestState = redisStateManager.getState(clientId);
        if (latestState == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }
        validateUploadOwnership(userId, latestState, clientId);
        rejectUnsupportedRecoverySchema(latestState);
        if (!hasSameUploadPlan(expectedState, latestState, clientId)) {
            throw new GeneralException(
                    ResultEnum.FILE_UPLOAD_ERROR,
                    "上传会话状态在完成期间发生变化");
        }
        return latestState;
    }

    /**
     * 比较会话身份与不可变上传计划，允许进度、密钥、hash 和幂等标记正常推进。
     */
    private boolean hasSameUploadPlan(
            FileUploadState expectedState,
            FileUploadState latestState,
            String clientId
    ) {
        return Objects.equals(clientId, latestState.getClientId())
                && Objects.equals(expectedState.getTenantId(), latestState.getTenantId())
                && Objects.equals(expectedState.getUserId(), latestState.getUserId())
                && Objects.equals(expectedState.getSuid(), latestState.getSuid())
                && Objects.equals(expectedState.getTargetFileId(), latestState.getTargetFileId())
                && Objects.equals(expectedState.getFileName(), latestState.getFileName())
                && Objects.equals(expectedState.getContentType(), latestState.getContentType())
                && expectedState.getFileSize() == latestState.getFileSize()
                && expectedState.getChunkSize() == latestState.getChunkSize()
                && expectedState.getTotalChunks() == latestState.getTotalChunks()
                && expectedState.isDirectUpload() == latestState.isDirectUpload()
                && Objects.equals(
                    expectedState.getRecoverySchemaVersion(),
                    latestState.getRecoverySchemaVersion())
                && hasSameDirectUploadPlan(expectedState, latestState);
    }

    /**
     * 比较直传会话的分片计划，防止等待 finalizer 锁期间可信存储路径或哈希发生漂移。
     *
     * @param expectedState 等待前的会话快照
     * @param latestState 获取锁后的最新会话快照
     * @return 非直传会话或直传分片计划完全一致时返回 true
     */
    private boolean hasSameDirectUploadPlan(FileUploadState expectedState, FileUploadState latestState) {
        if (!expectedState.isDirectUpload()) {
            return true;
        }
        List<FileUploadState.DirectUploadPartState> expectedParts = expectedState.getDirectUploadParts();
        List<FileUploadState.DirectUploadPartState> latestParts = latestState.getDirectUploadParts();
        if (expectedParts == null || latestParts == null || expectedParts.size() != latestParts.size()) {
            return false;
        }
        for (int index = 0; index < expectedParts.size(); index++) {
            if (!hasSameDirectUploadPart(expectedParts.get(index), latestParts.get(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 比较单个直传分片的全部可信计划字段。
     *
     * @param expectedPart 等待前的分片计划
     * @param latestPart 获取锁后的分片计划
     * @return 全部计划字段一致时返回 true
     */
    private boolean hasSameDirectUploadPart(
            FileUploadState.DirectUploadPartState expectedPart,
            FileUploadState.DirectUploadPartState latestPart
    ) {
        if (expectedPart == null || latestPart == null) {
            return expectedPart == latestPart;
        }
        return expectedPart.getIndex() == latestPart.getIndex()
                && expectedPart.getSize() == latestPart.getSize()
                && expectedPart.getExpiresAtEpochSeconds() == latestPart.getExpiresAtEpochSeconds()
                && Objects.equals(expectedPart.getPlainHash(), latestPart.getPlainHash())
                && Objects.equals(expectedPart.getCipherHash(), latestPart.getCipherHash())
                && Objects.equals(expectedPart.getChecksumAlgorithm(), latestPart.getChecksumAlgorithm())
                && Objects.equals(expectedPart.getUploadUrl(), latestPart.getUploadUrl())
                && Objects.equals(expectedPart.getStoragePath(), latestPart.getStoragePath())
                && Objects.equals(expectedPart.getStagingObjectName(), latestPart.getStagingObjectName())
                && Objects.equals(expectedPart.getFinalObjectName(), latestPart.getFinalObjectName())
                && Objects.equals(expectedPart.getNodeName(), latestPart.getNodeName());
    }

    /**
     * 在不可逆完成步骤前重载并校验 Redis 会话快照，阻断等待期间的状态丢失或计划漂移。
     *
     * @param userId 当前用户ID
     * @param clientId 客户端会话ID
     * @param initialState 等待前的会话快照
     * @param expectedChunks 等待前声明的分片总数
     * @return 身份、计划和全部分片证据均完整的最新快照
     */
    private FileUploadState requireLatestCompletionState(
            Long userId,
            String clientId,
            FileUploadState initialState,
            int expectedChunks
    ) {
        FileUploadState latestState = requireLatestUploadState(userId, clientId, initialState);
        if (isUploadSessionCompleted(latestState)) {
            return latestState;
        }
        if (redisStateManager.isSessionPaused(clientId)) {
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "上传会话已暂停");
        }
        requireCompleteChunkState(latestState, expectedChunks);
        return latestState;
    }

    /**
     * 校验最新快照精确覆盖每个分片索引，并携带完成流程所需的 hash 与密钥。
     *
     * @param state 最新上传会话快照
     * @param expectedChunks 预期分片总数
     */
    private void requireCompleteChunkState(FileUploadState state, int expectedChunks) {
        Set<Integer> uploadedChunks = state.getUploadedChunks();
        Set<Integer> processedChunks = state.getProcessedChunks();
        Map<String, String> chunkHashes = state.getChunkHashes();
        Map<Integer, byte[]> keys = state.getKeys();
        if (uploadedChunks == null
                || processedChunks == null
                || chunkHashes == null
                || keys == null
                || uploadedChunks.size() != expectedChunks
                || processedChunks.size() != expectedChunks
                || chunkHashes.size() != expectedChunks
                || keys.size() != expectedChunks) {
            throw incompleteCompletionState(state, expectedChunks);
        }
        for (int index = 0; index < expectedChunks; index++) {
            String hash = chunkHashes.get("chunk_" + index);
            byte[] key = keys.get(index);
            if (!uploadedChunks.contains(index)
                    || !processedChunks.contains(index)
                    || hash == null
                    || hash.isBlank()
                    || key == null
                    || key.length == 0) {
                throw incompleteCompletionState(state, expectedChunks);
            }
        }
    }

    /**
     * 构造可重试的完成态缺失异常，不泄露密钥或分片哈希内容。
     */
    private RetryableException incompleteCompletionState(FileUploadState state, int expectedChunks) {
        int processed = state.getProcessedChunks() == null ? 0 : state.getProcessedChunks().size();
        return new RetryableException(
                ResultEnum.SERVICE_UNAVAILABLE,
                Map.of("processed", processed, "expected", expectedChunks));
    }

    /**
     * 在完成阶段的不可逆文件变更前复核配额。
     * 该检查只做判定不写库，用于提前失败，降低重试时重复写尾部元数据的风险。
     *
     * @param userId 用户ID
     * @param state 上传会话状态
     */
    private void recheckQuotaBeforeFinalProcessing(Long userId, FileUploadState state) {
        Long tenantId = resolveTenantId(state);
        RLock lock = acquireQuotaCompletionLock(tenantId);
        try {
            if (shouldCheckQuotaForSession(state)) {
                quotaService.checkUploadQuota(tenantId, userId, state.getFileSize());
            }
        } finally {
            releaseQuotaCompletionLock(lock, tenantId);
        }
    }

    /**
     * 对新建上传会话执行配额校验。
     * 版本续传场景使用既有 PREPARE 记录，不再重复执行增量配额校验。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param fileSize 文件大小
     * @param targetFileId 目标文件ID（版本续传场景）
     */
    private void checkQuotaForNewUploadSession(Long tenantId, Long userId, long fileSize, Long targetFileId) {
        if (targetFileId != null) {
            return;
        }
        quotaService.checkUploadQuota(tenantId, userId, fileSize);
    }

    /**
     * 判断当前会话是否需要执行增量配额校验。
     * 当会话绑定了目标版本文件时，配额已由既有 PREPARE 记录体现，应跳过重复校验。
     *
     * @param state 上传会话状态
     * @return true 表示需要执行配额校验
     */
    private boolean shouldCheckQuotaForSession(FileUploadState state) {
        return state != null && state.getTargetFileId() == null;
    }

    /**
     * 从稳定 PREPARE 主键安全借用已经提交的普通上传 SUCCESS，并只补 Redis 完成态。
     *
     * @param userId 当前用户ID
     * @param suid 编码后的用户ID
     * @param state 当前普通上传会话状态
     * @return true 表示 DB SUCCESS 已验证且 Redis 完成态已补齐
     */
    private boolean recoverLegacySuccessFinalization(
            Long userId,
            String suid,
            FileUploadState state
    ) {
        if (state == null || state.isDirectUpload() || !state.isPrepareStored()) {
            return false;
        }
        Long preparedFileId = requirePreparedFileId(state);
        cn.flying.dao.dto.File persistedFile = fileService.getById(preparedFileId);
        if (persistedFile == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "普通上传 PREPARE 文件不存在");
        }
        if (Objects.equals(persistedFile.getStatus(), FileUploadStatus.PREPARE.getCode())) {
            return false;
        }
        if (!Objects.equals(persistedFile.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "普通上传目标文件状态不可恢复");
        }

        validateLegacySuccessEvidence(userId, state, persistedFile);
        try {
            cleanupLegacyTemporaryFilesStrict(state);
            redisStateManager.markCompleted(state.getClientId(), suid, 300);
        } catch (IOException | RuntimeException completionStateError) {
            throw new RetryableException(ResultEnum.SERVICE_UNAVAILABLE, completionStateError);
        }
        log.info("普通上传已从 DB SUCCESS 补齐 Redis 完成态: clientId={}, preparedFileId={}",
                state.getClientId(), preparedFileId);
        return true;
    }

    /**
     * 严格校验普通上传 SUCCESS 与当前租户、用户、稳定主键和内容摘要完全一致。
     */
    private void validateLegacySuccessEvidence(
            Long userId,
            FileUploadState state,
            cn.flying.dao.dto.File persistedFile
    ) {
        Long preparedFileId = requirePreparedFileId(state);
        Long tenantId = resolveTenantId(state);
        Long persistedSize = persistedFile.getFileSize();
        if (!Objects.equals(persistedFile.getId(), preparedFileId)
                || !Objects.equals(persistedFile.getUid(), userId)
                || !Objects.equals(persistedFile.getTenantId(), tenantId)
                || !Objects.equals(persistedFile.getFileName(), state.getFileName())
                || persistedSize == null
                || persistedSize.longValue() != state.getFileSize()
                || (state.getTargetFileId() != null
                    && !Objects.equals(state.getTargetFileId(), preparedFileId))
                || CommonUtils.isEmpty(persistedFile.getFileHash())
                || CommonUtils.isEmpty(persistedFile.getTransactionHash())
                || !Objects.equals(
                        requireContentHash(persistedFile.getContentHash()),
                        requireContentHash(state.getContentHash()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "普通上传 SUCCESS 记录与会话证据不一致");
        }
    }

    /**
     * 拒绝恢复需要人工对账的直传会话，避免刷新活动时间并缩短诊断 TTL。
     */
    private void rejectManualDirectUploadResume(FileUploadState state) {
        if (state != null
                && (UPLOAD_SESSION_STATUS_FINALIZATION_MANUAL_REQUIRED.equals(state.getStatus())
                    || UPLOAD_SESSION_STATUS_CLEANUP_MANUAL_REQUIRED.equals(state.getStatus()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "直传会话需要人工对账，禁止自动恢复");
        }
    }

    /**
     * 拒绝自动猜测旧会话中已落库但缺失稳定主键的 PREPARE 绑定，并保留人工诊断。
     */
    private void rejectUnboundPreparedCheckpoint(FileUploadState state) {
        if (state == null
                || !state.isPrepareStored()
                || state.getPreparedFileId() != null && state.getPreparedFileId() > 0) {
            return;
        }
        retainFinalizationForManualReconciliation(
                state,
                "旧上传会话已记录 PREPARE 但缺少稳定 preparedFileId");
        throw new GeneralException(
                ResultEnum.FILE_RECORD_ERROR,
                "上传会话 PREPARE 绑定缺少稳定文件ID，无法自动恢复");
    }

    /**
     * 在分配稳定 PREPARE 主键前校验确定性的恢复协议版本，旧会话一律失败关闭。
     */
    private void rejectUnsupportedRecoverySchema(FileUploadState state) {
        if (hasCurrentRecoverySchema(state)) {
            return;
        }
        retainFinalizationForManualReconciliation(
                state,
                "上传会话恢复协议版本缺失或不受支持");
        throw new GeneralException(
                ResultEnum.FILE_RECORD_ERROR,
                "上传会话恢复协议版本不受支持，必须人工对账");
    }

    /**
     * 判断会话是否精确采用当前恢复协议；未知更高版本也必须失败关闭。
     */
    private boolean hasCurrentRecoverySchema(FileUploadState state) {
        return state != null
                && Objects.equals(
                    state.getRecoverySchemaVersion(),
                    CURRENT_RECOVERY_SCHEMA_VERSION);
    }

    /**
     * 判断上传会话是否已进入完成终态，用于阻止重复完成请求重放不可逆副作用。
     *
     * @param state 上传会话状态
     * @return true 表示已完成
     */
    private boolean isUploadSessionCompleted(FileUploadState state) {
        return state != null && UPLOAD_SESSION_STATUS_COMPLETED.equalsIgnoreCase(state.getStatus());
    }

    /**
     * 对已写入 completed 主状态的会话重新执行可幂等终态收敛，确保辅助键 TTL、恢复映射和调度索引全部闭环。
     *
     * @param state 已进入完成终态的最新会话状态
     */
    private void convergeCompletedSessionState(FileUploadState state) {
        convergeCompletedSessionState(state, state.getSuid());
    }

    /**
     * 使用调用链中已验证的用户编码收敛完成态，兼容旧会话主体缺少 suid 的情况。
     *
     * @param state 已进入完成终态的最新会话状态
     * @param suid 已验证的用户编码
     */
    private void convergeCompletedSessionState(FileUploadState state, String suid) {
        try {
            redisStateManager.markCompleted(state.getClientId(), suid, 300);
        } catch (RetryableException exception) {
            throw exception;
        } catch (RuntimeException completionError) {
            throw new RetryableException(ResultEnum.SERVICE_UNAVAILABLE, completionError);
        }
    }

    /**
     * 解析上传会话所属租户ID，优先使用会话中的 tenantId，不存在时回退当前上下文默认租户。
     *
     * @param state 上传会话状态
     * @return 租户ID
     */
    private Long resolveTenantId(FileUploadState state) {
        if (state.getTenantId() != null) {
            return state.getTenantId();
        }
        return TenantContext.getTenantIdOrDefault();
    }

    /**
     * 暂停上传
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public void pauseUpload(Long userId, String clientId) {
        FileUploadState expectedState = redisStateManager.getState(clientId);
        if (expectedState == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }

        validateUploadOwnership(userId, expectedState, clientId);
        rejectUnsupportedRecoverySchema(expectedState);

        RLock finalizationLock = acquireUploadFinalizationLock(clientId);
        try {
            FileUploadState latestState = requireLatestUploadState(
                    userId, clientId, expectedState);
            rejectTerminalUploadPause(latestState);
            FileUploadRedisStateManager.PauseTransitionResult pauseResult =
                    redisStateManager.addPausedSession(clientId);
            if (pauseResult == FileUploadRedisStateManager.PauseTransitionResult.SESSION_NOT_FOUND) {
                throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
            }
            if (pauseResult == FileUploadRedisStateManager.PauseTransitionResult.TERMINAL) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "上传会话已进入终态，禁止暂停");
            }
            if (pauseResult != FileUploadRedisStateManager.PauseTransitionResult.PAUSED) {
                throw new IllegalStateException("暂停状态原子转换返回未知结果");
            }
            log.info("上传会话已暂停: 客户端ID={}", clientId);
        } finally {
            releaseUploadFinalizationLock(finalizationLock, clientId);
        }
    }

    /**
     * 拒绝把已完成或人工对账终态重新加入无 TTL 的暂停索引。
     */
    private void rejectTerminalUploadPause(FileUploadState state) {
        if (isUploadSessionCompleted(state) || isManualReconciliationState(state)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "上传会话已进入终态，禁止暂停");
        }
    }

    /**
     * 恢复上传
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public ResumeUploadVO resumeUpload(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);
        rejectUnsupportedRecoverySchema(state);

        boolean wasPaused = redisStateManager.removePausedSession(clientId);

        // 创建包含已处理分片列表的恢复响应 DTO
        ResumeUploadVO responseDto = new ResumeUploadVO(
                new ArrayList<>(state.getProcessedChunks()), // 返回已处理的分片列表给客户端
                state.getTotalChunks()
        );

        log.info("上传会话已恢复 (之前是否暂停={}): 客户端ID={}", wasPaused, clientId);
        return responseDto;
    }

    /**
     * 检查文件上传状态
     *
     * @throws GeneralException 会话不存在或无权限
     */
    public FileUploadStatusVO checkFileStatus(Long userId, String clientId) {
        FileUploadState state = redisStateManager.getState(clientId);
        if (state == null) {
            throw new GeneralException(ResultEnum.UPLOAD_SESSION_NOT_FOUND);
        }

        // 验证用户权限
        validateUploadOwnership(userId, state, clientId);
        rejectUnsupportedRecoverySchema(state);

        redisStateManager.updateLastActivityTime(clientId);
        boolean isPaused = redisStateManager.isSessionPaused(clientId);
        ProgressInfo progressInfo = calculateProgressInfo(state);
        String statusCode;

        if (isPaused) {
            statusCode = "PAUSED";
        } else if (progressInfo.processedCount == progressInfo.totalChunks && progressInfo.totalChunks > 0) {
            statusCode = "PROCESSING_COMPLETE";
        } else {
            statusCode = "UPLOADING";
        }

        FileUploadStatusVO responseDto = new FileUploadStatusVO(
                state.getFileName(), state.getFileSize(), state.getClientId(),
                isPaused, statusCode, progressInfo.totalProgress,
                new ArrayList<>(state.getProcessedChunks()),
                progressInfo.processedCount, progressInfo.totalChunks
        );

        log.debug("检查状态成功: 客户端ID={}, 状态={}, 进度={}%", clientId, statusCode, progressInfo.totalProgress);
        return responseDto;
    }

    /**
     * 验证上传会话所有权
     * 确保 Redis 中记录的 userId 与当前请求用户一致，否则拒绝访问
     */
    private void validateUploadOwnership(Long userId, FileUploadState state, String clientId) {
        Long currentTenantId = TenantContext.getTenantId();
        if (currentTenantId != null) {
            Long stateTenantId = state.getTenantId();
            if (stateTenantId == null || !stateTenantId.equals(currentTenantId)) {
                log.warn("租户无权访问上传会话: tenantId={}, clientId={}, ownerTenantId={}", currentTenantId, clientId, stateTenantId);
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权访问此上传会话");
            }
        }
        Long stateUserId = state.getUserId();
        if (stateUserId == null || !stateUserId.equals(userId)) {
            log.warn("用户无权访问上传会话: userId={}, clientId={}, ownerUserId={}", userId, clientId, stateUserId);
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权访问此上传会话");
        }
    }

    private ProgressInfo calculateProgressInfo(FileUploadState state) {
        int totalChunks = state.getTotalChunks();
        if (totalChunks == 0) {
            return new ProgressInfo(0, 0, 0, 100, 100, 100);
        }
        int uploadedCount = state.getUploadedChunks().size();
        int processedCount = state.getProcessedChunks().size();
        int uploadProgressPercent = (int) Math.round((uploadedCount * 100.0) / totalChunks);
        int processProgressPercent = (int) Math.round((processedCount * 100.0) / totalChunks);
        int totalProgress = (int) Math.round(uploadProgressPercent * 0.3 + processProgressPercent * 0.7);
        totalProgress = Math.max(0, Math.min(100, totalProgress));

        return new ProgressInfo(totalChunks, uploadedCount, processedCount,
                uploadProgressPercent, processProgressPercent, totalProgress);
    }

    // === 验证辅助方法 ===
    private boolean isValidFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) return false;
        if (fileName.contains("/") || fileName.contains("\\")) return false;
        if (containsPotentiallyUnsafeCharacters(fileName)) return false;
        return SAFE_FILENAME_PATTERN.matcher(fileName).matches();
    }

    private boolean isFileTypeAllowed(String fileName, String contentType) {
        String extension = getFileExtension(fileName);
        if (contentType != null) {
            String lowerContentType = contentType.toLowerCase();
            if (ALLOWED_MIME_TYPES.containsKey(lowerContentType)) {
                return true;
            }
        }
        return extension != null && ALLOWED_FILE_EXTENSIONS.contains(extension);
    }

    // 创建恢复会话响应 DTO
    private StartUploadVO createResumeDto(FileUploadState state) {
        // 恢复时同时返回 uploadedChunks / processedChunks，便于客户端决定跳过上传或仅等待服务端处理
        return new StartUploadVO(state.getClientId(),
                state.getChunkSize(), state.getTotalChunks(), state.getTotalChunks() == 1,
                new ArrayList<>(state.getUploadedChunks()),
                new ArrayList<>(state.getProcessedChunks()),
                true // 标记为恢复
        );
    }

    // 创建新会话响应 DTO
    private StartUploadVO createNewSessionDto(FileUploadState state) {
        return new StartUploadVO(
                state.getClientId(),
                state.getChunkSize(), state.getTotalChunks(), state.getTotalChunks() == 1,
                Collections.emptyList(),
                Collections.emptyList(),
                false // 标记为非恢复
        );
    }

    private boolean containsPotentiallyUnsafeCharacters(String fileName) {
        for (int i = 0; i < fileName.length(); i++) {
            char c = fileName.charAt(i);
            if (Character.isISOControl(c) || c == '\u202E' || c == '\u202B' || c == '\u202F') return true;
        }
        if (fileName.endsWith(".") || fileName.endsWith(" ")) return true;
        String upperName = fileName.toUpperCase();
        return upperName.matches("^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$");
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) return null;
        int dotIndex = fileName.lastIndexOf('.');
        return (dotIndex > 0 && dotIndex < fileName.length() - 1) ? fileName.substring(dotIndex + 1).toLowerCase() : null;
    }

    /**
     * 异步处理已上传的分片：对其进行加密，并在末尾附加其原始哈希值。
     * <p>使用可配置的加密策略（AES-GCM 或 ChaCha20-Poly1305）</p>
     *
     * <h3>加密分片文件结构（v1）：</h3>
     * <pre>
     * [版本头: 4B] [IV/Nonce: 12B] [加密数据] [认证标签] [--HASH--\n] [hash] [--NEXT_KEY--\n] [key]
     * </pre>
     */
    private void processChunkImmediately(String SUID, FileUploadState state, int chunkNumber, Path chunkPath, String chunkHashBase64) {
        CompletableFuture.runAsync(() -> {
            String clientId = state.getClientId();
            Path processedChunkPath = getChunkProcessedPath(SUID, clientId, chunkNumber);
            Path taskTempPath = processedChunkPath.resolveSibling(
                    processedChunkPath.getFileName() + ".tmp-" + UUID.randomUUID());
            RLock finalizationLock = null;
            RLock chunkLock = null;
            log.debug("-------------开始异步处理分片 {}: 原始路径={}, 加密算法={} -------------",
                    chunkNumber, chunkPath, encryptionStrategyFactory.getCurrentAlgorithmName());

            try {
                finalizationLock = acquireAsyncChunkFinalizationLock(clientId, chunkNumber);
                chunkLock = acquireChunkProcessingLock(clientId, chunkNumber);
                redisStateManager.executeWithSessionStateLock(clientId, latestState -> {
                    processChunkWithSessionAndChunkLocks(
                            SUID,
                            state,
                            latestState,
                            chunkNumber,
                            chunkPath,
                            processedChunkPath,
                            taskTempPath,
                            chunkHashBase64);
                    return null;
                });
                log.info("分片 {} 处理成功: 客户端ID={}, 处理后路径={}, 算法={}",
                        chunkNumber, clientId, processedChunkPath,
                        encryptionStrategyFactory.getCurrentAlgorithmName());

            } catch (GeneralException e) {
                log.error("分片 {} 加密失败: 客户端ID={}, 算法={}", chunkNumber, clientId,
                        encryptionStrategyFactory.getCurrentAlgorithmName(), e);
            } catch (Exception e) {
                log.error("异步处理分片 {} 失败: 客户端ID={}", chunkNumber, clientId, e);
            } finally {
                tryDelete(taskTempPath);
                releaseChunkProcessingLock(chunkLock, clientId, chunkNumber);
                releaseUploadFinalizationLock(finalizationLock, clientId);
            }
        }, fileProcessingExecutor);
    }

    /**
     * 用 Redis 主状态与规范原始分片路径重建进程重启后丢失的异步任务。
     * 仅调度同时具备 uploaded、稳定哈希和匹配 raw 文件的未处理分片，任何证据冲突都失败关闭。
     */
    private int requeueMissingProcessedChunks(String suid, FileUploadState state) {
        int requeued = 0;
        for (int chunkNumber = 0; chunkNumber < state.getTotalChunks(); chunkNumber++) {
            if (state.getProcessedChunks().contains(chunkNumber)
                    || !state.getUploadedChunks().contains(chunkNumber)) {
                continue;
            }
            String chunkHash = state.getChunkHashes().get("chunk_" + chunkNumber);
            Path rawChunkPath = getChunkUploadPath(
                    suid, state.getClientId(), chunkNumber);
            try {
                if (CommonUtils.isEmpty(chunkHash)
                        || !Files.isRegularFile(rawChunkPath)
                        || !Objects.equals(chunkHash, calculateChunkHashBase64(rawChunkPath))) {
                    throw new GeneralException(
                            ResultEnum.FILE_RECORD_ERROR,
                            "未处理分片缺少可验证的 uploaded/hash/raw 恢复证据");
                }
            } catch (IOException rawReadError) {
                throw new GeneralException(
                        ResultEnum.FILE_RECORD_ERROR,
                        "读取未处理分片恢复证据失败");
            }
            processChunkImmediately(
                    suid, state, chunkNumber, rawChunkPath, chunkHash);
            requeued++;
        }
        return requeued;
    }

    /**
     * 分片异步任务按有界周期等待同一会话 finalizer 锁，直到 upload 释放后接续处理。
     * 每轮等待都由 Redisson watchdog 锁语义保护，避免一次非阻塞失败把已持久化任务静默丢弃。
     */
    private RLock acquireAsyncChunkFinalizationLock(String clientId, int chunkNumber) {
        RLock lock;
        try {
            lock = redissonClient.getLock(UPLOAD_FINALIZATION_LOCK_KEY_PREFIX + clientId);
            if (lock == null) {
                throw new IllegalStateException("上传会话 finalizer 锁不存在");
            }
            while (!lock.tryLock(ASYNC_FINALIZATION_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.info("异步分片等待会话 finalizer 锁: clientId={}, chunk={}",
                        clientId, chunkNumber);
            }
            return lock;
        } catch (InterruptedException interruptedError) {
            Thread.currentThread().interrupt();
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "async chunk finalization lock wait was interrupted"));
        } catch (RuntimeException lockError) {
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "async chunk finalization lock is unavailable"));
        }
    }

    /**
     * 在跨实例分片锁和 session state 锁内生成稳定密钥、原子发布密文并最后提交 processed 证据。
     */
    private void processChunkWithSessionAndChunkLocks(
            String suid,
            FileUploadState expectedState,
            FileUploadState latestState,
            int chunkNumber,
            Path chunkPath,
            Path processedChunkPath,
            Path taskTempPath,
            String queuedHash
    ) {
        String clientId = latestState.getClientId();
        if (!hasSameUploadPlan(expectedState, latestState, clientId)) {
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "异步处理期间上传计划发生变化");
        }
        if (isUploadSessionCompleted(latestState) || isManualReconciliationState(latestState)) {
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "上传会话已进入不可处理终态");
        }
        if (latestState.getProcessedChunks().contains(chunkNumber)) {
            validateProcessedChunkEvidence(latestState, chunkNumber, processedChunkPath);
            return;
        }

        String trustedHash = latestState.getChunkHashes().get("chunk_" + chunkNumber);
        if (CommonUtils.isEmpty(trustedHash) || !Objects.equals(trustedHash, queuedHash)) {
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "异步分片哈希检查点不一致");
        }
        try {
            if (!Files.isRegularFile(chunkPath)
                    || !Objects.equals(trustedHash, calculateChunkHashBase64(chunkPath))) {
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "原始分片与 Redis 哈希证据不一致");
            }

            ChunkEncryptionStrategy strategy = encryptionStrategyFactory.getStrategy();
            SecretKey candidateKey = strategy.generateKey();
            byte[] stableKeyBytes = redisStateManager.getOrCreateChunkKey(
                    clientId, chunkNumber, candidateKey.getEncoded());
            SecretKey stableKey = new SecretKeySpec(
                    stableKeyBytes, resolveSecretKeyAlgorithm(strategy));
            byte[] iv = strategy.generateIv();
            EncryptionContext encryptionContext = strategy.createEncryptionContext(stableKey, iv);

            Files.createDirectories(processedChunkPath.getParent());
            try (InputStream input = Files.newInputStream(chunkPath);
                 FileChannel tempChannel = FileChannel.open(
                         taskTempPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream output = Channels.newOutputStream(tempChannel)) {
                output.write(ChunkFileHeader.createHeader(strategy));
                output.write(iv);
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    byte[] encryptedBytes = strategy.encryptUpdate(
                            encryptionContext, buffer, 0, bytesRead);
                    if (encryptedBytes.length > 0) {
                        output.write(encryptedBytes);
                    }
                }
                byte[] finalBytes = strategy.encryptFinal(encryptionContext);
                if (finalBytes.length > 0) {
                    output.write(finalBytes);
                }
                output.write(HASH_SEPARATOR.getBytes(StandardCharsets.UTF_8));
                output.write(trustedHash.getBytes(StandardCharsets.UTF_8));
                output.flush();
                tempChannel.force(true);
            }

            Files.move(
                    taskTempPath,
                    processedChunkPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            if (!Files.isRegularFile(processedChunkPath)) {
                throw new IOException("原子发布后的处理分片不存在");
            }
            latestState.getKeys().put(chunkNumber, stableKeyBytes);
            redisStateManager.addProcessedChunk(clientId, chunkNumber);
            FileUploadState updatedState = redisStateManager.getState(clientId);
            if (updatedState != null) {
                updateUploadProgress(updatedState, "处理完分片 " + chunkNumber);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("异步分片文件处理失败", e);
        }
    }

    /**
     * 已处理证据存在时校验最终密文文件和稳定密钥同时存在，禁止盲目幂等跳过。
     */
    private void validateProcessedChunkEvidence(
            FileUploadState state,
            int chunkNumber,
            Path processedChunkPath
    ) {
        byte[] stableKey = state.getKeys().get(chunkNumber);
        if (stableKey == null || stableKey.length == 0 || !Files.isRegularFile(processedChunkPath)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "processed 证据缺少密钥或最终密文");
        }
    }

    /**
     * 计算原始分片 URL-safe SHA-256，供异步任务确认磁盘内容没有被重复请求覆盖。
     */
    private String calculateChunkHashBase64(Path chunkPath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            try (InputStream input = Files.newInputStream(chunkPath);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(OutputStream.nullOutputStream());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 流式计算重复请求分片的 SHA-256，比较既有证据且不覆盖原始文件。
     */
    private String calculateMultipartHashBase64(MultipartFile file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            try (InputStream input = file.getInputStream();
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(OutputStream.nullOutputStream());
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }

    /**
     * 将持久化密钥字节恢复成当前加密策略需要的 JCA 密钥类型。
     */
    private String resolveSecretKeyAlgorithm(ChunkEncryptionStrategy strategy) {
        return strategy.getAlgorithmName().toLowerCase(Locale.ROOT).contains("chacha")
                ? "ChaCha20"
                : "AES";
    }

    /**
     * 阻塞获取跨实例 session+chunk watchdog 锁，串行化稳定密钥到 processed 证据的完整区间。
     */
    private RLock acquireChunkProcessingLock(String clientId, int chunkNumber) {
        try {
            RLock lock = redissonClient.getLock(
                    CHUNK_PROCESSING_LOCK_KEY_PREFIX + clientId + ":" + chunkNumber);
            if (lock == null) {
                throw new IllegalStateException("分片处理锁不存在");
            }
            lock.lock();
            return lock;
        } catch (RuntimeException lockError) {
            throw new RetryableException(
                    ResultEnum.SERVICE_UNAVAILABLE,
                    Map.of("reason", "chunk processing lock is unavailable"));
        }
    }

    /**
     * 释放跨实例分片处理锁，失败仅记录且不删除任何已发布 final 文件。
     */
    private void releaseChunkProcessingLock(
            RLock lock,
            String clientId,
            int chunkNumber
    ) {
        if (lock == null) {
            return;
        }
        try {
            lock.unlock();
        } catch (IllegalMonitorStateException e) {
            log.debug("分片处理锁已不属于当前线程: clientId={}, chunk={}", clientId, chunkNumber);
        } catch (RuntimeException e) {
            log.warn("释放分片处理锁失败: clientId={}, chunk={}", clientId, chunkNumber, e);
        }
    }

    /**
     * 文件处理的最后一步：追加下一个分片的密钥。
     * 该步骤支持幂等重入，避免 completeUpload 重试时重复追加 NEXT_KEY 元数据。
     */
    private void completeFileProcessing(String SUID, FileUploadState state) throws IOException {
        log.info("------------开始最终处理步骤 (追加下一个分片密钥): 客户端ID={}--------------", state.getClientId());
        int totalChunks = state.getTotalChunks();
        Map<Integer, byte[]> keys = redisStateManager.getChunkKeys(state.getClientId());

        // NPE 防护：检查 keys 是否为 null
        if (keys == null) {
            log.error("获取分片密钥失败: keys 为 null, 客户端ID={}. 无法完成处理。", state.getClientId());
            throw new IOException("无法完成处理：无法获取分片密钥信息。");
        }

        if (keys.size() < totalChunks) {
            log.error("密钥数量 ({}) 少于分片总数 ({}), 客户端ID={}. 无法完成处理。", keys.size(), totalChunks, state.getClientId());
            throw new IOException("无法完成处理：并非所有分片的密钥都可用。");
        }
        for (int i = 0; i < totalChunks; i++) {
            if (keys.get(i) == null) {
                log.error("分片 {} 的密钥丢失 (为 null), 客户端ID={}. 无法完成处理。", i, state.getClientId());
                throw new IOException("无法完成处理：分片 " + i + " 的密钥丢失。");
            }
        }

        for (int i = 0; i < totalChunks - 1; i++) {
            Path currentChunkPath = getChunkProcessedPath(SUID, state.getClientId(), i);
            byte[] nextChunkKey = keys.get(i + 1);
            appendKeyToFile(
                    currentChunkPath,
                    nextChunkKey,
                    requirePlainChunkHash(state, i),
                    i);
        }

        // 为最后一个分片追加第一个分片的密钥（形成环形链）
        if (totalChunks > 0) {
            int lastChunkIndex = totalChunks - 1;
            Path lastChunkPath = getChunkProcessedPath(SUID, state.getClientId(), lastChunkIndex);
            byte[] firstChunkKey = keys.get(0);
            appendKeyToFile(
                    lastChunkPath,
                    firstChunkKey,
                    requirePlainChunkHash(state, lastChunkIndex),
                    lastChunkIndex);
        }

        log.info("最终处理步骤 (追加下一个分片密钥) 完成: 客户端ID={}", state.getClientId());
    }

    /**
     * 读取完成态追加所需的权威明文分片哈希，缺失时禁止修改 processed 文件。
     */
    private String requirePlainChunkHash(FileUploadState state, int chunkIndex) throws IOException {
        String hash = state.getChunkHashes().get("chunk_" + chunkIndex);
        if (CommonUtils.isEmpty(hash)) {
            throw new IOException("分片 " + chunkIndex + " 缺少权威明文哈希");
        }
        return hash;
    }

    /**
     * 使用同目录临时文件原子发布 NEXT_KEY 元数据，避免原文件原地追加产生半写尾部。
     *
     * @param filePath 分片文件路径
     * @param keyBytes 待追加密钥
     * @param chunkIndex 分片序号
     * @throws IOException 文件读写异常
     */
    private void appendKeyToFile(
            Path filePath,
            byte[] keyBytes,
            String expectedPlainHash,
            int chunkIndex
    ) throws IOException {
        if (!Files.isRegularFile(filePath)) {
            log.error("处理后的分片文件未找到，无法追加密钥: {}", filePath);
            throw new FileNotFoundException("处理后的分片文件未找到: " + filePath.getFileName());
        }
        if (keyBytes == null || keyBytes.length == 0) {
            throw new IOException("分片 " + chunkIndex + " 的下一个密钥为空");
        }
        if (hasExactNextKeyMetadata(filePath, expectedPlainHash, keyBytes)) {
            log.info("分片 {} 已存在相同 NEXT_KEY 元数据，跳过重复发布: {}", chunkIndex, filePath);
            return;
        }

        Path tempPath = filePath.resolveSibling(
                filePath.getFileName() + ".next-key-" + UUID.randomUUID() + ".tmp");
        byte[] metadata = (KEY_SEPARATOR + Base64.getEncoder().encodeToString(keyBytes))
                .getBytes(StandardCharsets.UTF_8);
        try {
            try (InputStream input = Files.newInputStream(filePath, StandardOpenOption.READ);
                 FileChannel tempChannel = FileChannel.open(
                         tempPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                 OutputStream output = Channels.newOutputStream(tempChannel)) {
                input.transferTo(output);
                output.write(metadata);
                output.flush();
                tempChannel.force(true);
            }
            Files.move(
                    tempPath,
                    filePath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            log.debug("已原子发布分片 {} 的下一个密钥", chunkIndex);
        } catch (IOException publishError) {
            log.error("原子发布分片 {} 的下一个密钥失败: 路径={}",
                    chunkIndex, filePath, publishError);
            throw new IOException("追加密钥到分片 " + chunkIndex + " 失败", publishError);
        } finally {
            tryDelete(tempPath);
        }
    }

    /**
     * 精确校验 processed 尾部：只能是可信 plain hash，或其后紧跟同一预期 NEXT_KEY。
     * 半写、重复 separator、不同有效密钥和未知尾部都失败关闭且不修改原文件。
     *
     * @param filePath 分片文件路径
     * @return true 表示已完整追加同一密钥；false 表示仍是未追加的干净 processed 文件
     */
    private boolean hasExactNextKeyMetadata(
            Path filePath,
            String expectedPlainHash,
            byte[] expectedKey
    ) throws IOException {
        byte[] plainHashSuffix = (HASH_SEPARATOR + expectedPlainHash)
                .getBytes(StandardCharsets.UTF_8);
        long fileSize = Files.size(filePath);
        int scanSize = (int) Math.min(fileSize, NEXT_KEY_TAIL_SCAN_BYTES);
        byte[] tailBytes = readFileTail(filePath, scanSize);
        int plainHashIndex = findLastBytesIndex(tailBytes, plainHashSuffix);
        if (plainHashIndex < 0) {
            throw new IOException("processed 文件缺少精确 plain hash 尾部证据");
        }
        int metadataStart = plainHashIndex + plainHashSuffix.length;
        if (metadataStart == tailBytes.length) {
            return false;
        }
        byte[] expectedMetadata = (KEY_SEPARATOR
                + Base64.getEncoder().encodeToString(expectedKey))
                .getBytes(StandardCharsets.UTF_8);
        byte[] actualMetadata = Arrays.copyOfRange(
                tailBytes, metadataStart, tailBytes.length);
        if (!Arrays.equals(expectedMetadata, actualMetadata)) {
            throw new IOException("processed 文件 NEXT_KEY 尾部不完整、重复或与预期密钥不一致");
        }
        return true;
    }

    /**
     * 读取文件尾部固定字节数。
     *
     * @param filePath 文件路径
     * @param length 读取长度
     * @return 尾部字节数组
     * @throws IOException 文件读写异常
     */
    private byte[] readFileTail(Path filePath, int length) throws IOException {
        long fileSize = Files.size(filePath);
        int resolvedLength = (int) Math.min(fileSize, Math.max(length, 0));
        byte[] result = new byte[resolvedLength];
        if (resolvedLength == 0) {
            return result;
        }

        try (InputStream inputStream = Files.newInputStream(filePath, StandardOpenOption.READ)) {
            long bytesToSkip = fileSize - resolvedLength;
            while (bytesToSkip > 0) {
                long skipped = inputStream.skip(bytesToSkip);
                if (skipped <= 0) {
                    throw new EOFException("无法定位到文件尾部指定位置");
                }
                bytesToSkip -= skipped;
            }

            int offset = 0;
            while (offset < resolvedLength) {
                int read = inputStream.read(result, offset, resolvedLength - offset);
                if (read < 0) {
                    throw new EOFException("读取文件尾部数据时提前结束");
                }
                offset += read;
            }
        }
        return result;
    }

    /**
     * 从字节数组末尾查找目标子序列。
     *
     * @param source 源字节数组
     * @param target 目标子序列
     * @return 命中起始索引，未命中返回 -1
     */
    private int findLastBytesIndex(byte[] source, byte[] target) {
        if (source == null || target == null || source.length < target.length || target.length == 0) {
            return -1;
        }
        outer:
        for (int i = source.length - target.length; i >= 0; i--) {
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * 收集处理后的文件（按分片索引顺序）
     */
    private List<File> collectProcessedFiles(String SUID, String clientId) {
        // 从Redis获取状态以确定总分片数
        FileUploadState state = redisStateManager.getState(clientId);
        int totalChunks = 0;

        if (state != null) {
            totalChunks = state.getTotalChunks();
            log.info("------------从Redis状态获取分片数: 客户端ID={}, 总分片数={}-------------", clientId, totalChunks);
        } else {
            log.warn("无法从Redis获取上传状态: 客户端ID={}", clientId);
        }

        List<File> orderedFiles = new ArrayList<>(totalChunks);

        log.info("------------开始按顺序收集处理后的文件: 客户端ID={}, 总分片数={}-------------", clientId, totalChunks);

        // 按分片索引顺序收集文件
        for (int i = 0; i < totalChunks; i++) {
            Path chunkPath = getChunkProcessedPath(SUID, clientId, i);

            if (Files.exists(chunkPath) && Files.isRegularFile(chunkPath)) {
                File chunkFile = chunkPath.toFile();
                orderedFiles.add(chunkFile);
            } else {
                log.error("分片文件不存在或不是常规文件: 索引={}, 路径={}", i, chunkPath);
                return null;
            }
        }

        // 验证收集的文件数量
        if (orderedFiles.size() != totalChunks) {
            log.error("收集的文件数量({})与预期分片数量({})不匹配: 客户端ID={}", orderedFiles.size(), totalChunks, clientId);
            return null;
        }

        return orderedFiles;
    }

    /**
     * 对已完成尾部元数据的密文分片按序流式计算 SHA-256，作为对象存储内容地址。
     */
    private List<String> collectCipherFileHashes(
            FileUploadState state,
            List<File> processedFiles
    ) {
        int totalChunks = state.getTotalChunks();
        List<String> orderedHashes = new ArrayList<>(totalChunks);
        if (processedFiles == null || processedFiles.size() != totalChunks) {
            return null;
        }
        byte[] buffer = new byte[BUFFER_SIZE];
        for (int index = 0; index < totalChunks; index++) {
            File processedFile = processedFiles.get(index);
            try (InputStream input = Files.newInputStream(processedFile.toPath())) {
                MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
                orderedHashes.add(
                        CONTENT_HASH_PREFIX + HexFormat.of().formatHex(digest.digest()));
            } catch (IOException | NoSuchAlgorithmException hashError) {
                log.error("计算密文分片内容地址失败: clientId={}, chunk={}",
                        state.getClientId(), index, hashError);
                return null;
            }
        }
        return orderedHashes;
    }

    /**
     * 按原始分片索引流式计算整文件 SHA-256；若删除分片后的重试已有可信结果则直接复用。
     *
     * @param suid 用户隔离目录标识
     * @param state 上传会话状态
     * @return 带 sha256 前缀的规范内容摘要
     */
    private String resolveOriginalContentHash(String suid, FileUploadState state) {
        if (CommonUtils.isNotEmpty(state.getContentHash())) {
            return requireContentHash(state.getContentHash());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] buffer = new byte[8192];
            long totalBytes = 0L;
            for (int index = 0; index < state.getTotalChunks(); index++) {
                Path chunkPath = getChunkUploadPath(suid, state.getClientId(), index);
                if (!Files.isRegularFile(chunkPath)) {
                    throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR,
                            "原始分片缺失，无法生成内容哈希: " + index);
                }
                try (InputStream inputStream = Files.newInputStream(chunkPath)) {
                    int read;
                    while ((read = inputStream.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        totalBytes = Math.addExact(totalBytes, read);
                    }
                }
            }
            if (totalBytes != state.getFileSize()) {
                throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "原始分片总长度与文件大小不一致");
            }
            return CONTENT_HASH_PREFIX + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 摘要算法不可用", e);
        } catch (IOException | ArithmeticException e) {
            throw new GeneralException(ResultEnum.FILE_UPLOAD_ERROR, "计算原文件内容哈希失败");
        }
    }

    /**
     * 规范化并校验可信上传链路返回的原文件 SHA-256。
     *
     * @param value 待校验摘要
     * @return 小写规范摘要
     */
    private String requireContentHash(String value) {
        if (value == null || value.length() > 128) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "缺少可信的原文件内容哈希");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!DIRECT_SHA256_PATTERN.matcher(normalized).matches()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "缺少可信的原文件内容哈希");
        }
        return normalized;
    }

    /**
     * 生成文件参数
     * 包含解密所需的初始密钥（最后一个分片的密钥）
     */
    private String generateFileParam(FileUploadState state) {
        if (state.getStartTime() <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "上传会话缺少稳定开始时间");
        }
        // 生成文件参数，包含必要的元数据
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fileName", state.getFileName());
        params.put("fileSize", state.getFileSize());
        params.put("contentType", state.getContentType());
        params.put("uploadTime", state.getStartTime());
        params.put("chunkCount", state.getTotalChunks());
        params.put("contentHash", requireContentHash(state.getContentHash()));

        // 存储初始密钥（最后一个分片的密钥）用于前端解密
        // 密钥链设计：chunk[i] 末尾包含 chunk[i+1] 的密钥，最后一个包含 chunk[0] 的密钥
        // 因此解密需要从最后一个分片开始，需要知道其密钥
        int lastChunkIndex = state.getTotalChunks() - 1;
        Map<Integer, byte[]> keys = state.getKeys();
        if (keys != null && keys.containsKey(lastChunkIndex)) {
            byte[] lastChunkKey = keys.get(lastChunkIndex);
            params.put("initialKey", Base64.getEncoder().encodeToString(lastChunkKey));
        } else {
            log.warn("无法获取最后一个分片的密钥: clientId={}, lastChunkIndex={}",
                    state.getClientId(), lastChunkIndex);
        }

        try {
            return JsonConverter.toJson(params);
        } catch (Exception e) {
            log.error("生成文件参数失败: {}", e.getMessage(), e);
            return "{}"; // 返回空参数
        }
    }

    // === 响应创建辅助方法 (Service 内部使用) ===

    private Path getChunkUploadPath(String SUID, String clientId, int chunkNumber) {
        return getUploadSessionDir(SUID, clientId).resolve("chunk_" + chunkNumber);
    }

    private Path getChunkProcessedPath(String SUID, String clientId, int chunkNumber) {
        return getProcessedSessionDir(SUID, clientId).resolve("encrypted_chunk_" + chunkNumber);
    }

    private boolean isValidChunkNumber(int chunkNumber, int totalChunks) {
        if (totalChunks == 0) return chunkNumber == 0; // 0字节文件
        return chunkNumber >= 0 && chunkNumber < totalChunks;
    }

    // === 进度计算辅助方法 ===
    private void updateUploadProgress(FileUploadState state, String reason) {
        long now = System.currentTimeMillis();
        if (now - state.getLastProgressLogTime() >= PROGRESS_UPDATE_INTERVAL_MS) {
            ProgressInfo info = calculateProgressInfo(state);
            log.info("进度更新 ({}) [客户端ID: {}]: 总进度: {}%, 上传: {}/{} ({}%), 处理: {}/{} ({}%)",
                    reason, state.getClientId(), info.totalProgress,
                    info.uploadedCount, info.totalChunks, info.uploadProgressPercent,
                    info.processedCount, info.totalChunks, info.processProgressPercent);
            state.setLastProgressLogTime(now);

            // 更新Redis中的状态
            redisStateManager.updateState(state);
        }
    }

    /**
     * 判断是否为严重的上传异常，需要清理Redis状态
     *
     * @param e 异常对象
     * @return true表示严重异常，需要清理状态；false表示轻微异常，保留状态支持重试
     */
    private boolean isCriticalUploadError(Exception e) {
        // 严重异常类型：需要清理Redis状态
        if (e instanceof SecurityException ||           // 安全异常
                e instanceof NoSuchAlgorithmException ||    // 算法不可用
                e instanceof IllegalStateException) {       // 非法状态
            return true;
        }

        // IO异常中的严重类型
        if (e instanceof IOException) {
            String message = e.getMessage();
            if (message != null) {
                // 磁盘空间不足、权限问题等严重IO异常
                return message.contains("No space left on device") ||
                        message.contains("Permission denied") ||
                        message.contains("Access is denied");
            }
        }

        // 其他异常视为轻微异常，保留状态支持重试
        return false;
    }

    /**
     * 内部类，用于封装进度计算结果
     */
    private record ProgressInfo(int totalChunks, int uploadedCount, int processedCount, int uploadProgressPercent,
                                int processProgressPercent, int totalProgress) {
    }
}
