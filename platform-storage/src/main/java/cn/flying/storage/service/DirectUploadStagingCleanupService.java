package cn.flying.storage.service;

import cn.flying.storage.config.DirectUploadCleanupSchedulingConfiguration;
import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.S3ClientManager;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 有界回收超过保留期的 direct-upload staging 对象。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectUploadStagingCleanupService {

    private static final long MIN_BATCH_SAFETY_MARGIN_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final long MAX_BATCH_SAFETY_MARGIN_MILLIS = Duration.ofSeconds(60).toMillis();
    private static final long MIN_PROVIDER_REQUEST_BUDGET_NANOS = Duration.ofMillis(1).toNanos();
    private static final Duration MAX_DELETE_CALL_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration MAX_DELETE_ATTEMPT_TIMEOUT = Duration.ofSeconds(30);

    private final S3ClientManager clientManager;
    private final StorageProperties storageProperties;
    private final DirectUploadStagingTracker stagingTracker;
    private final DirectUploadLockManager lockManager;
    private final DirectUploadOperationIntentStore operationIntentStore;
    private final MeterRegistry meterRegistry;

    /**
     * 定期处理 Redis 中已到期的 staging 记录；失败记录保留到下一轮重试。
     */
    @Scheduled(
            fixedDelayString = "#{@storageProperties.getDirectUpload().getEffectiveCleanupIntervalMillis()}",
            initialDelayString = "#{@storageProperties.getDirectUpload().getEffectiveCleanupInitialDelayMillis()}",
            scheduler = DirectUploadCleanupSchedulingConfiguration.CLEANUP_SCHEDULER_BEAN_NAME
    )
    public void cleanupExpiredStagingObjects() {
        StorageProperties.DirectUploadConfig config = storageProperties.getDirectUpload();
        if (!config.isCleanupEnabled()) {
            return;
        }

        DirectUploadStagingTracker.ClaimBatch batch;
        long batchStartedNanos = currentMonotonicTimeNanos();
        try {
            batch = stagingTracker.claimExpired(config.getEffectiveCleanupBatchSize());
        } catch (RuntimeException e) {
            incrementCleanupMetric("claim_failure");
            log.warn("领取直传 staging 清理批次失败，保留原生命周期记录", e);
            return;
        }

        int processed = 0;
        int deleted = 0;
        boolean budgetExhausted = false;
        long batchDeadlineNanos = calculateBatchDeadlineNanos(batchStartedNanos, batch, config);
        try {
            List<DirectUploadStagingTracker.TrackedStaging> entries = batch.entries();
            for (int index = 0; index < entries.size(); index++) {
                if (!hasProviderRequestBudget(batchDeadlineNanos)) {
                    rescheduleUnprocessedClaims(entries.subList(index, entries.size()));
                    budgetExhausted = true;
                    break;
                }
                DirectUploadStagingTracker.TrackedStaging tracked = entries.get(index);
                processed++;
                CleanupOutcome outcome = cleanupOne(tracked, batchDeadlineNanos);
                if (outcome == CleanupOutcome.DELETED) {
                    deleted++;
                }
                if (outcome == CleanupOutcome.BUDGET_EXHAUSTED) {
                    rescheduleUnprocessedClaims(entries.subList(index + 1, entries.size()));
                    budgetExhausted = true;
                    break;
                }
            }
        } finally {
            releaseBatchSafely(batch);
        }
        if (budgetExhausted) {
            incrementCleanupMetric("budget_exhausted");
        }
        if (processed > 0) {
            log.info("直传 staging 生命周期清理完成: processed={}, deleted={}, budgetExhausted={}",
                    processed, deleted, budgetExhausted);
        }
    }

    /**
     * 使用领取批次的真实租约计算严格更短的单调时钟预算，并保留 30 到 60 秒退出余量。
     */
    private long calculateBatchDeadlineNanos(
            long batchStartedNanos,
            DirectUploadStagingTracker.ClaimBatch batch,
            StorageProperties.DirectUploadConfig config
    ) {
        long claimLeaseMillis = batch.claimLeaseMillis() > 0
                ? batch.claimLeaseMillis()
                : TimeUnit.SECONDS.toMillis(config.getEffectiveCleanupClaimLeaseSeconds());
        long proportionalMargin = claimLeaseMillis / 10;
        long safetyMarginMillis = Math.max(
                MIN_BATCH_SAFETY_MARGIN_MILLIS,
                Math.min(MAX_BATCH_SAFETY_MARGIN_MILLIS, proportionalMargin)
        );
        long budgetMillis = Math.max(0, claimLeaseMillis - safetyMarginMillis);
        return batchStartedNanos + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
    }

    /**
     * 尽力释放集群 claim gate；Redis 故障时依靠 claim lease 自动恢复。
     */
    private void releaseBatchSafely(DirectUploadStagingTracker.ClaimBatch batch) {
        try {
            stagingTracker.releaseClaimBatch(batch);
        } catch (RuntimeException e) {
            incrementCleanupMetric("batch_release_failure");
            log.warn("释放直传 staging 清理批次失败，将等待 claim lease 到期", e);
        }
    }

    /**
     * 在同一分片锁保护下幂等删除一个到期 staging 对象。
     *
     * @param tracked 到期 tracker 记录
     * @return 是否已删除对象并完成当前 claim
     */
    private CleanupOutcome cleanupOne(
            DirectUploadStagingTracker.TrackedStaging tracked,
            long batchDeadlineNanos
    ) {
        DirectUploadStagingDescriptor descriptor = tracked.descriptor();
        try {
            Optional<DirectUploadLockManager.LockHandle> optionalLock =
                    lockManager.tryAcquireForCleanup(descriptor);
            if (optionalLock.isEmpty()) {
                rescheduleAfterFailure(tracked);
                incrementCleanupMetric("locked");
                return CleanupOutcome.RETAINED;
            }
            return cleanupWithLock(tracked, optionalLock.get(), batchDeadlineNanos);
        } catch (CleanupBudgetExceededException e) {
            rescheduleAfterFailure(tracked);
            return CleanupOutcome.BUDGET_EXHAUSTED;
        } catch (RuntimeException e) {
            rescheduleAfterFailure(tracked);
            incrementCleanupMetric("failure");
            log.warn("清理到期直传 staging 对象失败: node={}, partIndex={}",
                    descriptor.nodeName(), descriptor.partIndex(), e);
            return CleanupOutcome.RETAINED;
        }
    }

    /**
     * 在已获取的分片锁内重验 fencing token 后删除 staging。
     */
    private CleanupOutcome cleanupWithLock(
            DirectUploadStagingTracker.TrackedStaging tracked,
            DirectUploadLockManager.LockHandle lockHandle,
            long batchDeadlineNanos
    ) {
        DirectUploadStagingDescriptor descriptor = tracked.descriptor();
        try (DirectUploadLockManager.LockHandle ignored = lockHandle) {
            if (!stagingTracker.isClaimCurrent(tracked)) {
                incrementCleanupMetric("claim_superseded");
                return CleanupOutcome.RETAINED;
            }
            Optional<DirectUploadOperationIntentStore.OperationIntent> optionalIntent =
                    operationIntentStore.followOrCreateCleanup(tracked);
            if (optionalIntent.isEmpty()) {
                incrementCleanupMetric("claim_superseded");
                return CleanupOutcome.RETAINED;
            }
            DirectUploadOperationIntentStore.OperationIntent intent = optionalIntent.get();
            try (S3ClientManager.TopologyLease topology = clientManager.acquireTopologyLease()) {
                S3Client client = topology.getClient(descriptor.nodeName());
                if (client == null) {
                    rescheduleAfterFailure(tracked);
                    incrementCleanupMetric("client_unavailable");
                    return CleanupOutcome.RETAINED;
                }
                if (!deleteIfPresent(
                        client,
                        tracked,
                        intent,
                        descriptor.objectName(),
                        "original",
                        batchDeadlineNanos
                )) {
                    return CleanupOutcome.RETAINED;
                }
                if (!deleteIfPresent(
                        client,
                        tracked,
                        intent,
                        descriptor.sealedObjectName(),
                        "sealed",
                        batchDeadlineNanos
                )) {
                    return CleanupOutcome.RETAINED;
                }
                operationIntentStore.retire(
                        intent,
                        Duration.ofHours(
                                storageProperties.getDirectUpload().getEffectiveStagingRetentionHours()
                        )
                );
                if (!stagingTracker.completeClaim(tracked)) {
                    incrementCleanupMetric("completion_fenced");
                    log.warn("直传 staging DELETE 后 claim 已换代，保留新代 lifecycle: node={}, partIndex={}",
                            descriptor.nodeName(), descriptor.partIndex());
                    return CleanupOutcome.RETAINED;
                }
                incrementCleanupMetric("success");
                return CleanupOutcome.DELETED;
            }
        }
    }

    /**
     * 使用相同请求超时幂等删除 original 或 sealed staging 对象，404 视为已完成。
     */
    private boolean deleteIfPresent(
            S3Client client,
            DirectUploadStagingTracker.TrackedStaging tracked,
            DirectUploadOperationIntentStore.OperationIntent intent,
            String objectName,
            String kind,
            long batchDeadlineNanos
    ) {
        DirectUploadStagingDescriptor descriptor = tracked.descriptor();
        if (!stagingTracker.isClaimCurrent(tracked)) {
            incrementCleanupMetric("claim_superseded");
            return false;
        }
        operationIntentStore.verify(intent);
        try {
            client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(descriptor.nodeName())
                    .key(objectName)
                    .overrideConfiguration(cleanupRequestOverride(batchDeadlineNanos))
                    .build());
        } catch (S3Exception e) {
            if (e.statusCode() != 404) {
                throw e;
            }
            log.info("到期直传 {} staging 已不存在，继续幂等清理: node={}, partIndex={}",
                    kind, descriptor.nodeName(), descriptor.partIndex());
        }
        return true;
    }

    /**
     * 使用一次精确 fencing Lua 调用重排预算内尚未处理的 claim；Redis 故障时保留租约状态。
     */
    private void rescheduleUnprocessedClaims(List<DirectUploadStagingTracker.TrackedStaging> trackedClaims) {
        if (trackedClaims.isEmpty()) {
            return;
        }
        try {
            int rescheduled = stagingTracker.rescheduleClaims(List.copyOf(trackedClaims));
            if (rescheduled < trackedClaims.size()) {
                incrementCleanupMetric("claim_superseded");
            }
        } catch (RuntimeException e) {
            incrementCleanupMetric("reschedule_failure");
            log.warn("批量延后未处理的直传 staging claim 失败，等待原 claim lease 到期", e);
        }
    }

    /**
     * 尽力把失败成员移出当前到期窗口；Redis 不确认时保留原记录并继续处理同批其他成员。
     */
    private void rescheduleAfterFailure(DirectUploadStagingTracker.TrackedStaging tracked) {
        try {
            if (!stagingTracker.rescheduleClaim(tracked)) {
                incrementCleanupMetric("claim_superseded");
            }
        } catch (RuntimeException e) {
            incrementCleanupMetric("reschedule_failure");
            log.warn("延后直传 staging 清理失败，保留原到期记录: node={}, partIndex={}",
                    tracked.descriptor().nodeName(), tracked.descriptor().partIndex(), e);
        }
    }

    /**
     * 为锁内 staging DELETE 设置请求级硬超时，避免 provider 阻塞超过 claim lease。
     *
     * @return 有界 API 调用与单次尝试配置
     */
    private AwsRequestOverrideConfiguration cleanupRequestOverride(long batchDeadlineNanos) {
        long remainingNanos = remainingBudgetNanos(batchDeadlineNanos);
        if (remainingNanos < MIN_PROVIDER_REQUEST_BUDGET_NANOS) {
            throw new CleanupBudgetExceededException();
        }
        Duration configuredCallTimeout = Duration.ofSeconds(
                storageProperties.getDirectUpload().getEffectiveTransferTimeoutSeconds()
        );
        Duration callTimeout = minDuration(
                Duration.ofNanos(remainingNanos),
                minDuration(MAX_DELETE_CALL_TIMEOUT, configuredCallTimeout)
        );
        Duration attemptTimeout = minDuration(MAX_DELETE_ATTEMPT_TIMEOUT, callTimeout.dividedBy(2));
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(callTimeout)
                .apiCallAttemptTimeout(attemptTimeout)
                .build();
    }

    /**
     * 判断批次截止点前是否仍有足够的最小 provider 请求预算。
     */
    private boolean hasProviderRequestBudget(long batchDeadlineNanos) {
        return remainingBudgetNanos(batchDeadlineNanos) >= MIN_PROVIDER_REQUEST_BUDGET_NANOS;
    }

    /**
     * 以可测试的单调时钟计算剩余纳秒数，nanoTime 环绕在短租约窗口内仍可安全相减。
     */
    private long remainingBudgetNanos(long batchDeadlineNanos) {
        return batchDeadlineNanos - currentMonotonicTimeNanos();
    }

    /**
     * 返回两个正 Duration 中较小者。
     */
    private Duration minDuration(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    /**
     * 提供批次截止计算使用的单调时钟；测试可覆盖以模拟租约推进。
     */
    long currentMonotonicTimeNanos() {
        return System.nanoTime();
    }

    /**
     * 记录低基数的 staging 生命周期清理结果。
     */
    private void incrementCleanupMetric(String result) {
        meterRegistry.counter(
                "storage_direct_upload_staging_cleanup_total",
                "result",
                result
        ).increment();
    }

    /**
     * 单条 staging 清理结果，用于在批次预算耗尽后停止后续 provider 请求。
     */
    private enum CleanupOutcome {
        DELETED,
        RETAINED,
        BUDGET_EXHAUSTED
    }

    /**
     * 表示当前批次已没有安全的 provider 请求时间，不携带对象或凭据信息。
     */
    private static final class CleanupBudgetExceededException extends RuntimeException {
        private CleanupBudgetExceededException() {
            super("direct-upload staging cleanup batch budget exhausted", null, false, false);
        }
    }
}
