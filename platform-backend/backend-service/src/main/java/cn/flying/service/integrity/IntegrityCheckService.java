package cn.flying.service.integrity;

import cn.flying.common.annotation.TenantScope;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.IntegrityAlert;
import cn.flying.dao.entity.IntegrityAlert.AlertStatus;
import cn.flying.dao.entity.IntegrityAlert.AlertType;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.IntegrityAlertMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.file.IntegrityCheckStatsVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Periodically verifies stored files against on-chain records
 * to detect silent data corruption or tampering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrityCheckService {

    private static final String LOCK_KEY = "integrity-check-lock";
    private static final String S3_PATH_FORMAT = "storage/tenant/%d/chunk/%s";

    private final FileMapper fileMapper;
    private final IntegrityAlertMapper integrityAlertMapper;
    private final TenantMapper tenantMapper;
    private final FileRemoteClient fileRemoteClient;
    private final SseEmitterManager sseEmitterManager;
    private final RedissonClient redissonClient;

    @Value("${integrity.check.sample-rate:0.01}")
    private double sampleRate;

    @Value("${integrity.check.batch-size:50}")
    private int batchSize;

    @Value("${integrity.check.lock-timeout-seconds:1800}")
    private long lockTimeoutSeconds;

    /**
     * Run integrity check across all tenants with random sampling.
     * Acquires a distributed lock to prevent concurrent execution.
     *
     * @return check statistics
     */
    @TenantScope(ignoreIsolation = true)
    public IntegrityCheckStatsVO checkIntegrity() {
        return executeWithLock(this::doCheckAllTenants);
    }

    /**
     * Run a full integrity check for a specific tenant (no sampling).
     *
     * @param tenantId the tenant to check
     * @return check statistics
     */
    @TenantScope(ignoreIsolation = true)
    public IntegrityCheckStatsVO triggerManualCheck(Long tenantId) {
        log.info("[integrity-check] manual check triggered for tenantId={}", tenantId);
        return executeWithLock(() -> {
            List<File> files = querySuccessFilesPaged(tenantId);
            return checkFiles(files, tenantId);
        });
    }

    /**
     * Mark an alert as acknowledged by an admin.
     *
     * @param alertId alert ID
     * @param adminId admin user ID
     */
    public void acknowledgeAlert(Long alertId, Long adminId) {
        IntegrityAlert alert = integrityAlertMapper.selectById(alertId);
        if (alert == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        alert.setStatus(AlertStatus.ACKNOWLEDGED.getCode());
        integrityAlertMapper.updateById(alert);
        log.info("[integrity-check] alert {} acknowledged by admin {}", alertId, adminId);
    }

    /**
     * Mark an alert as resolved with a note.
     *
     * @param alertId alert ID
     * @param adminId admin user ID
     * @param note    resolution note
     */
    public void resolveAlert(Long alertId, Long adminId, String note) {
        IntegrityAlert alert = integrityAlertMapper.selectById(alertId);
        if (alert == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE);
        }
        alert.setStatus(AlertStatus.RESOLVED.getCode())
                .setResolvedBy(adminId)
                .setResolvedAt(new Date())
                .setNote(note);
        integrityAlertMapper.updateById(alert);
        log.info("[integrity-check] alert {} resolved by admin {}", alertId, adminId);
    }

    // ========== Internal ==========

    private IntegrityCheckStatsVO executeWithLock(java.util.function.Supplier<IntegrityCheckStatsVO> task) {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, lockTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[integrity-check] interrupted while acquiring lock");
            return new IntegrityCheckStatsVO(0, 0, 0);
        }
        if (!acquired) {
            log.info("[integrity-check] lock already held, skipping this run");
            return new IntegrityCheckStatsVO(0, 0, 0);
        }
        try {
            return task.get();
        } finally {
            try { lock.unlock(); } catch (Exception e) { log.warn("[integrity-check] failed to release lock: {}", e.getMessage()); }
        }
    }

    private IntegrityCheckStatsVO doCheckAllTenants() {
        List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.info("[integrity-check] no active tenants found");
            return new IntegrityCheckStatsVO(0, 0, 0);
        }

        long totalChecked = 0;
        long totalMismatches = 0;
        long totalErrors = 0;

        for (Long tenantId : tenantIds) {
            List<File> files = querySuccessFilesPaged(tenantId);
            List<File> sampled = sampleFiles(files);
            if (sampled.isEmpty()) {
                continue;
            }

            IntegrityCheckStatsVO stats = checkFiles(sampled, tenantId);
            totalChecked += stats.totalChecked();
            totalMismatches += stats.mismatchesFound();
            totalErrors += stats.errorsEncountered();
        }

        return new IntegrityCheckStatsVO(totalChecked, totalMismatches, totalErrors);
    }

    private List<File> querySuccessFilesPaged(Long tenantId) {
        List<File> allFiles = new ArrayList<>();
        long current = 1;
        Page<File> page;
        do {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(File::getTenantId, tenantId)
                    .eq(File::getStatus, FileUploadStatus.SUCCESS.getCode())
                    .eq(File::getDeleted, 0)
                    .select(File::getId, File::getTenantId, File::getUid, File::getFileHash, File::getFileParam, File::getFileName);
            page = fileMapper.selectPage(new Page<>(current, 500, false), wrapper);
            allFiles.addAll(page.getRecords());
            current++;
        } while (page.getRecords().size() == 500);
        return allFiles;
    }

    private List<File> sampleFiles(List<File> files) {
        if (files == null || files.isEmpty()) {
            return List.of();
        }
        List<File> sampled = new ArrayList<>();
        for (File file : files) {
            if (ThreadLocalRandom.current().nextDouble() < sampleRate) {
                sampled.add(file);
            }
        }
        return sampled;
    }

    private IntegrityCheckStatsVO checkFiles(List<File> files, Long tenantId) {
        long checked = 0;
        long mismatches = 0;
        long errors = 0;

        for (int i = 0; i < files.size(); i += batchSize) {
            List<File> batch = files.subList(i, Math.min(i + batchSize, files.size()));
            for (File file : batch) {
                checked++;
                try {
                    VerifyResult result = verifyFile(file);
                    if (result != null) {
                        mismatches++;
                        createAlert(file, result.alertType(), tenantId, result.chainHash());
                    }
                } catch (Exception e) {
                    errors++;
                    log.warn("[integrity-check] error checking fileId={}: {}", file.getId(), e.getMessage());
                }
            }
        }

        return new IntegrityCheckStatsVO(checked, mismatches, errors);
    }

    private record VerifyResult(AlertType alertType, String chainHash) {}

    /**
     * Verify a single file: existence in S3, then DB hash vs on-chain hash.
     *
     * @return a VerifyResult if a problem is found, or null if the file is consistent
     */
    private VerifyResult verifyFile(File file) {
        // 1. Verify file exists in S3
        try {
            String filePath = buildFilePath(file);
            Result<List<byte[]>> storageResult = fileRemoteClient.getFileListByHash(
                    List.of(filePath), List.of(file.getFileHash()));
            if (!storageResult.isSuccess() || storageResult.getData() == null || storageResult.getData().isEmpty()
                    || storageResult.getData().get(0) == null || storageResult.getData().get(0).length == 0) {
                log.warn("[integrity-check] file not found in S3: fileId={}, hash={}", file.getId(), file.getFileHash());
                return new VerifyResult(AlertType.FILE_NOT_FOUND, null);
            }
        } catch (Exception e) {
            log.warn("[integrity-check] failed to download file from S3: fileId={}, error={}", file.getId(), e.getMessage());
            return new VerifyResult(AlertType.FILE_NOT_FOUND, null);
        }

        // 2. Compare DB hash with on-chain record
        try {
            String uploader = String.valueOf(file.getUid());
            Result<FileDetailVO> chainResult = fileRemoteClient.getFile(uploader, file.getFileHash());
            if (!chainResult.isSuccess() || chainResult.getData() == null) {
                log.warn("[integrity-check] chain record not found: fileId={}, hash={}", file.getId(), file.getFileHash());
                return new VerifyResult(AlertType.CHAIN_NOT_FOUND, null);
            }

            String chainHash = chainResult.getData().fileHash();
            if (!file.getFileHash().equalsIgnoreCase(chainHash)) {
                log.warn("[integrity-check] chain hash mismatch: fileId={}, dbHash={}, chainHash={}",
                        file.getId(), file.getFileHash(), chainHash);
                return new VerifyResult(AlertType.HASH_MISMATCH, chainHash);
            }
        } catch (Exception e) {
            log.warn("[integrity-check] failed to query chain: fileId={}, error={}", file.getId(), e.getMessage());
            return new VerifyResult(AlertType.CHAIN_NOT_FOUND, null);
        }

        return null;
    }

    private void createAlert(File file, AlertType alertType, Long tenantId, String chainHash) {
        IntegrityAlert alert = new IntegrityAlert()
                .setTenantId(tenantId)
                .setFileId(file.getId())
                .setFileHash(file.getFileHash())
                .setChainHash(chainHash)
                .setAlertType(alertType.name())
                .setStatus(AlertStatus.PENDING.getCode());
        integrityAlertMapper.insert(alert);

        // Broadcast to admins via SSE
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("alertId", alert.getId());
        payload.put("fileId", file.getId());
        payload.put("fileName", file.getFileName() != null ? file.getFileName() : "");
        payload.put("alertType", alertType.name());
        payload.put("fileHash", file.getFileHash());

        try {
            TenantContext.callWithTenant(tenantId, () -> {
                sseEmitterManager.broadcastToAdmins(tenantId,
                        SseEvent.of(SseEventType.INTEGRITY_ALERT, payload));
                return null;
            });
        } catch (Exception e) {
            log.warn("[integrity-check] failed to broadcast SSE alert for tenantId={}: {}", tenantId, e.getMessage());
        }

        log.warn("[integrity-check] alert created: type={}, fileId={}, tenantId={}", alertType, file.getId(), tenantId);
    }

    private String buildFilePath(File file) {
        return String.format(S3_PATH_FORMAT, file.getTenantId(), file.getFileHash());
    }
}
