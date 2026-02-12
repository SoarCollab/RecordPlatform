package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.QuotaPolicy;
import cn.flying.dao.entity.QuotaUsageSnapshot;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.QuotaPolicyMapper;
import cn.flying.dao.mapper.QuotaUsageSnapshotMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.dao.vo.file.QuotaUserUsageVO;
import cn.flying.service.QuotaService;
import cn.flying.service.quota.QuotaDecision;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 配额服务实现。
 * 默认以 SHADOW 模式运行，避免在基线不稳定时误阻断上传。
 */
@Service
@Slf4j
public class QuotaServiceImpl implements QuotaService {

    private static final String MODE_SHADOW = "SHADOW";
    private static final String MODE_ENFORCE = "ENFORCE";
    private static final String SCOPE_USER = "USER";
    private static final String SCOPE_TENANT = "TENANT";

    @Resource
    private FileMapper fileMapper;

    @Resource
    private QuotaPolicyMapper quotaPolicyMapper;

    @Resource
    private QuotaUsageSnapshotMapper quotaUsageSnapshotMapper;

    @Resource
    private TenantMapper tenantMapper;

    @Value("${quota.enforcement-mode:SHADOW}")
    private String enforcementMode;

    @Value("${quota.default.user.max-storage-bytes:5368709120}")
    private long defaultUserMaxStorageBytes;

    @Value("${quota.default.user.max-file-count:10000}")
    private long defaultUserMaxFileCount;

    @Value("${quota.default.tenant.max-storage-bytes:107374182400}")
    private long defaultTenantMaxStorageBytes;

    @Value("${quota.default.tenant.max-file-count:500000}")
    private long defaultTenantMaxFileCount;

    /**
     * 评估上传请求配额并刷新实时快照。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param incomingFileSizeBytes 待上传文件大小
     * @return 配额判定
     */
    @Override
    public QuotaDecision evaluateUploadQuota(Long tenantId, Long userId, long incomingFileSizeBytes) {
        long resolvedIncomingSize = Math.max(0L, incomingFileSizeBytes);
        long userUsedStorage = nvl(fileMapper.sumQuotaStorageByUserId(userId, tenantId));
        long userUsedFileCount = nvl(fileMapper.countQuotaByUserId(userId, tenantId));
        long tenantUsedStorage = nvl(fileMapper.sumQuotaStorageByTenantId(tenantId));
        long tenantUsedFileCount = nvl(fileMapper.countQuotaByTenantId(tenantId));

        QuotaPolicy userPolicy = resolvePolicy(tenantId, SCOPE_USER, userId);
        QuotaPolicy tenantPolicy = resolvePolicy(tenantId, SCOPE_TENANT, tenantId);

        long userMaxStorage = userPolicy != null ? userPolicy.getMaxStorageBytes() : defaultUserMaxStorageBytes;
        long userMaxFileCount = userPolicy != null ? userPolicy.getMaxFileCount() : defaultUserMaxFileCount;
        long tenantMaxStorage = tenantPolicy != null ? tenantPolicy.getMaxStorageBytes() : defaultTenantMaxStorageBytes;
        long tenantMaxFileCount = tenantPolicy != null ? tenantPolicy.getMaxFileCount() : defaultTenantMaxFileCount;

        boolean userStorageExceeded = userUsedStorage + resolvedIncomingSize > userMaxStorage;
        boolean userFileCountExceeded = userUsedFileCount + 1 > userMaxFileCount;
        boolean tenantStorageExceeded = tenantUsedStorage + resolvedIncomingSize > tenantMaxStorage;
        boolean tenantFileCountExceeded = tenantUsedFileCount + 1 > tenantMaxFileCount;
        boolean exceeded = userStorageExceeded || userFileCountExceeded || tenantStorageExceeded || tenantFileCountExceeded;

        upsertSnapshot(tenantId, userId, userUsedStorage, userUsedFileCount, "REALTIME");
        upsertSnapshot(tenantId, 0L, tenantUsedStorage, tenantUsedFileCount, "REALTIME");

        return new QuotaDecision(
                exceeded,
                userStorageExceeded,
                userFileCountExceeded,
                tenantStorageExceeded,
                tenantFileCountExceeded,
                tenantId,
                userId,
                userUsedStorage,
                userMaxStorage,
                userUsedFileCount,
                userMaxFileCount,
                tenantUsedStorage,
                tenantMaxStorage,
                tenantUsedFileCount,
                tenantMaxFileCount,
                resolvedIncomingSize
        );
    }

    /**
     * 在上传链路中执行配额检查。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param incomingFileSizeBytes 待上传文件大小
     */
    @Override
    public void checkUploadQuota(Long tenantId, Long userId, long incomingFileSizeBytes) {
        QuotaDecision decision = evaluateUploadQuota(tenantId, userId, incomingFileSizeBytes);
        if (!decision.exceeded()) {
            return;
        }

        String mode = getEnforcementMode();
        if (MODE_ENFORCE.equals(mode)) {
            throw new GeneralException(ResultEnum.QUOTA_EXCEEDED, decision.reason());
        }

        log.warn("[quota-shadow] tenantId={}, userId={}, reason={}, incomingSize={}",
                tenantId, userId, decision.reason(), incomingFileSizeBytes);
    }

    /**
     * 获取当前用户配额状态。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @return 配额状态
     */
    @Override
    public QuotaStatusVO getCurrentQuotaStatus(Long tenantId, Long userId) {
        QuotaDecision decision = evaluateUploadQuota(tenantId, userId, 0L);
        return new QuotaStatusVO(
                tenantId,
                userId,
                getEnforcementMode(),
                decision.userUsedStorageBytes(),
                decision.userMaxStorageBytes(),
                decision.userUsedFileCount(),
                decision.userMaxFileCount(),
                decision.tenantUsedStorageBytes(),
                decision.tenantMaxStorageBytes(),
                decision.tenantUsedFileCount(),
                decision.tenantMaxFileCount()
        );
    }

    /**
     * 按租户重算使用量快照并记录偏差日志。
     * 对账流程需要跨租户访问，必须绕过租户行级拦截。
     */
    @Override
    public void reconcileUsageSnapshots() {
        TenantContext.runWithoutIsolation(this::reconcileUsageSnapshotsInternal);
    }

    /**
     * 对账核心实现：遍历活跃租户并刷新租户/用户快照。
     */
    private void reconcileUsageSnapshotsInternal() {
        List<Long> tenantIds = tenantMapper.selectActiveTenantIds();
        if (tenantIds == null || tenantIds.isEmpty()) {
            return;
        }

        for (Long tenantId : tenantIds) {
            long tenantUsedStorage = nvl(fileMapper.sumQuotaStorageByTenantId(tenantId));
            long tenantUsedFileCount = nvl(fileMapper.countQuotaByTenantId(tenantId));
            logSnapshotDrift(tenantId, 0L, tenantUsedStorage, tenantUsedFileCount);
            upsertSnapshot(tenantId, 0L, tenantUsedStorage, tenantUsedFileCount, "RECON");

            List<QuotaUserUsageVO> usageList = fileMapper.aggregateQuotaUserUsageByTenant(tenantId);
            if (usageList == null) {
                usageList = List.of();
            }
            resetMissingUserSnapshots(tenantId, usageList, "RECON");
            for (QuotaUserUsageVO usage : usageList) {
                Long userId = usage.getUserId();
                long usedStorage = nvl(usage.getUsedStorageBytes());
                long usedCount = nvl(usage.getUsedFileCount());
                logSnapshotDrift(tenantId, userId, usedStorage, usedCount);
                upsertSnapshot(tenantId, userId, usedStorage, usedCount, "RECON");
            }
        }
    }

    /**
     * 清零本轮未命中的用户快照，防止用户文件清空后保留历史占用值。
     *
     * @param tenantId 租户ID
     * @param usageList 本轮对账聚合结果
     * @param source 快照来源
     */
    private void resetMissingUserSnapshots(Long tenantId, List<QuotaUserUsageVO> usageList, String source) {
        List<Long> activeUserIds = usageList.stream()
                .map(QuotaUserUsageVO::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        quotaUsageSnapshotMapper.resetMissingUserSnapshots(tenantId, activeUserIds, source);
    }

    /**
     * 定时触发配额对账任务。
     */
    @Scheduled(cron = "${quota.reconcile.cron:0 0/30 * * * ?}")
    public void scheduleQuotaReconcile() {
        reconcileUsageSnapshots();
    }

    /**
     * 获取当前执行模式（自动归一化为 SHADOW/ENFORCE）。
     *
     * @return 归一化模式字符串
     */
    @Override
    public String getEnforcementMode() {
        String mode = enforcementMode == null ? MODE_SHADOW : enforcementMode.trim().toUpperCase();
        return MODE_ENFORCE.equals(mode) ? MODE_ENFORCE : MODE_SHADOW;
    }

    /**
     * 查询策略，优先匹配精确 scopeId，其次匹配 scopeId=0 的租户默认策略。
     *
     * @param tenantId 租户ID
     * @param scopeType 作用域类型
     * @param scopeId 作用域ID
     * @return 命中策略，不存在返回 null
     */
    private QuotaPolicy resolvePolicy(Long tenantId, String scopeType, Long scopeId) {
        QuotaPolicy policy = quotaPolicyMapper.selectActivePolicy(tenantId, scopeType, scopeId);
        if (policy != null) {
            return policy;
        }
        return quotaPolicyMapper.selectActivePolicy(tenantId, scopeType, 0L);
    }

    /**
     * Upsert 快照记录。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID（0 表示租户级）
     * @param usedStorageBytes 已使用存储
     * @param usedFileCount 已使用文件数
     * @param source 快照来源
     */
    private void upsertSnapshot(Long tenantId, Long userId, long usedStorageBytes, long usedFileCount, String source) {
        quotaUsageSnapshotMapper.upsertSnapshot(tenantId, userId, usedStorageBytes, usedFileCount, source);
    }

    /**
     * 输出快照偏差日志，便于观察对账质量。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID（0 表示租户级）
     * @param actualStorage 实际存储
     * @param actualCount 实际文件数
     */
    private void logSnapshotDrift(Long tenantId, Long userId, long actualStorage, long actualCount) {
        QuotaUsageSnapshot snapshot = quotaUsageSnapshotMapper.selectByScope(tenantId, userId);
        if (snapshot == null) {
            return;
        }
        long storageDiff = Math.abs(nvl(snapshot.getUsedStorageBytes()) - actualStorage);
        long countDiff = Math.abs(nvl(snapshot.getUsedFileCount()) - actualCount);
        if (storageDiff > 0 || countDiff > 0) {
            log.info("[quota-reconcile] drift detected: tenantId={}, userId={}, storageDiff={}, countDiff={}",
                    tenantId, userId, storageDiff, countDiff);
        }
    }

    /**
     * 空值转 0，统一数值计算。
     *
     * @param value 输入值
     * @return 非空 long
     */
    private long nvl(Long value) {
        return value == null ? 0L : value;
    }
}
