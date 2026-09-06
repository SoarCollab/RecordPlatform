package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.QuotaPolicy;
import cn.flying.dao.entity.platform.PlatformTenantRow;
import cn.flying.dao.mapper.platform.PlatformOverviewMapper;
import cn.flying.dao.mapper.platform.PlatformQuotaMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.mapper.platform.PlatformUserMapper;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.dao.vo.platform.PlatformQuotaVO;
import cn.flying.dao.vo.platform.PlatformPage;
import cn.flying.dao.vo.platform.PlatformTenantVO;
import cn.flying.dao.vo.platform.PlatformUsageVO;
import cn.flying.service.QuotaService;
import cn.flying.service.admin.TenantMemberAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Bounded tenant metadata and explicitly isolated usage/quota read models. */
@Service
@RequiredArgsConstructor
public class PlatformTenantQueryService {

    private final PlatformTenantMapper tenantMapper;
    private final PlatformQuotaMapper quotaMapper;
    private final PlatformUserMapper userMapper;
    private final PlatformOverviewMapper overviewMapper;
    private final QuotaService quotaService;
    private final TenantMemberAuditService sanitizer;

    /** Returns a stable metadata page after validating platform authority and query bounds. */
    public IPage<PlatformTenantVO> list(long pageNum, long pageSize, String keyword, Integer status) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_READ);
        long offset = PlatformInputs.offset(pageNum, pageSize);
        String search = PlatformInputs.keyword(keyword);
        PlatformInputs.status(status);
        long total = tenantMapper.countPage(search, status);
        List<PlatformTenantVO> rows = tenantMapper.selectPage(offset, pageSize, search, status).stream()
                .map(this::toView).toList();
        return new PlatformPage<PlatformTenantVO>(pageNum, pageSize, total).setRecords(rows);
    }

    /** Returns one explicitly addressed tenant without opening a tenant data-plane session. */
    public PlatformTenantVO get(Long tenantId) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_READ);
        return toView(requireTarget(tenantId));
    }

    /** Measures real quota-eligible usage and explicitly tenant-owned audit/attestation counts. */
    public PlatformUsageVO usage(Long tenantId) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_READ);
        requireTarget(tenantId);
        return TenantContext.callWithTenantIsolation(tenantId, () -> {
            QuotaStatusVO quota = currentQuota(tenantId);
            return new PlatformUsageVO(IdUtils.toExternalId(tenantId),
                    PlatformInputs.measured(userMapper.countTenantMembers(tenantId)),
                    PlatformInputs.measured(quota.tenantUsedFileCount()),
                    PlatformInputs.measured(quota.tenantUsedStorageBytes()),
                    PlatformInputs.measured(overviewMapper.countTenantAudit(tenantId)),
                    PlatformInputs.measured(overviewMapper.countCompletedAttestations(tenantId)),
                    "TENANT_BUSINESS_OPERATION_LOG", "TENANT_COMPLETED_ATTESTATION_BATCH", "AVAILABLE");
        });
    }

    /** Returns effective limits and a writable exact-override version under forced target isolation. */
    public PlatformQuotaVO quota(Long tenantId) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.QUOTA_READ);
        requireTarget(tenantId);
        return TenantContext.callWithTenantIsolation(tenantId, () -> {
            QuotaStatusVO status = currentQuota(tenantId);
            QuotaPolicy override = quotaMapper.selectOverride(tenantId);
            String source;
            if (override != null && Integer.valueOf(1).equals(override.getStatus())) {
                source = "TENANT_OVERRIDE";
            } else {
                source = quotaMapper.selectDefault(tenantId) == null ? "APPLICATION_DEFAULT" : "TENANT_DEFAULT";
            }
            if (status.enforcementMode() == null || !Set.of("SHADOW", "ENFORCE").contains(status.enforcementMode())) {
                throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
            }
            return new PlatformQuotaVO(IdUtils.toExternalId(tenantId),
                    PlatformInputs.measured(status.tenantMaxStorageBytes()),
                    PlatformInputs.measured(status.tenantMaxFileCount()),
                    PlatformInputs.measured(status.tenantUsedStorageBytes()),
                    PlatformInputs.measured(status.tenantUsedFileCount()), source, status.enforcementMode(),
                    override == null ? 0L : PlatformInputs.measured(override.getVersion()));
        });
    }

    /** Resolves a target for already-authorized local service collaborators before their context switch. */
    PlatformTenantRow requireTarget(Long tenantId) {
        PlatformInputs.tenantId(tenantId);
        PlatformTenantRow target = tenantMapper.selectMetadata(tenantId);
        if (target == null) {
            throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        }
        PlatformInputs.measured(target.getVersion());
        return target;
    }

    /** Uses the existing read-only quota resolver and its application defaults without writing snapshots. */
    private QuotaStatusVO currentQuota(Long tenantId) {
        QuotaStatusVO quota = quotaService.getCurrentQuotaStatus(tenantId, 0L);
        if (quota == null) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        return quota;
    }

    /** Converts internal metadata to typed external IDs and redacts legacy disable reasons. */
    private PlatformTenantVO toView(PlatformTenantRow row) {
        String disabledReason = row.getDisabledReason();
        if (disabledReason != null && !disabledReason.isBlank()) {
            disabledReason = sanitizer.sanitizeReason(disabledReason);
        }
        return new PlatformTenantVO(IdUtils.toExternalId(row.getId()), row.getCode(), row.getName(),
                row.getStatus(), PlatformInputs.measured(row.getVersion()), disabledReason,
                row.getDisabledAt(), IdUtils.toExternalUserId(row.getDisabledBy()),
                row.getCreateTime(), row.getUpdateTime(), PlatformInputs.measured(row.getMemberCount()));
    }
}
