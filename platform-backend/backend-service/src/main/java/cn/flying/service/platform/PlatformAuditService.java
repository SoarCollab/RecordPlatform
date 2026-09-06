package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.platform.PlatformOperationLog;
import cn.flying.dao.mapper.platform.PlatformOperationMapper;
import cn.flying.dao.vo.platform.PlatformAuditVO;
import cn.flying.dao.vo.platform.PlatformPage;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/** Platform-only audit queries over system-owned operation records. */
@Service
@RequiredArgsConstructor
public class PlatformAuditService {

    private final PlatformOperationMapper mapper;
    private final PlatformOperationExecutor executor;

    /** Returns a bounded stable history page including failed and ambiguous operations. */
    public IPage<PlatformAuditVO> list(long pageNum, long pageSize, Long tenantId, String status) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.AUDIT_READ);
        long offset = PlatformInputs.offset(pageNum, pageSize);
        if (tenantId != null) {
            PlatformInputs.tenantId(tenantId);
        }
        if (status != null && !Set.of("PROCESSING", "SUCCESS", "FAILURE").contains(status)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        long total = mapper.countPage(tenantId, status);
        List<PlatformAuditVO> rows = mapper.selectPage(offset, pageSize, tenantId, status).stream()
                .map(this::toView).toList();
        return new PlatformPage<PlatformAuditVO>(pageNum, pageSize, total).setRecords(rows);
    }

    /** Reads one system-owned record without making it accessible through tenant audit APIs. */
    public PlatformAuditVO get(Long operationId) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.AUDIT_READ);
        PlatformOperationLog row = operationId == null ? null : mapper.selectById(operationId);
        if (row == null) {
            throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        }
        return toView(row);
    }

    /** Emits fixed audit metadata, external IDs, and revalidated safe result fields only. */
    private PlatformAuditVO toView(PlatformOperationLog row) {
        PlatformOperationType type;
        try {
            type = PlatformOperationType.valueOf(row.getOperation());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        if (!type.resourceType().equals(row.getResourceType())
                || !Long.valueOf(0).equals(row.getTenantId())
                || row.getStatus() == null || !Set.of("PROCESSING", "SUCCESS", "FAILURE").contains(row.getStatus())) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        return new PlatformAuditVO(IdUtils.toExternalId(row.getId()), IdUtils.toExternalUserId(row.getActorId()),
                type.name(), IdUtils.toExternalId(row.getTargetTenantId()), type.resourceType(),
                PlatformOperationExecutor.externalResource(type.resourceType(), row.getResourceId(), row.getResourceKey()),
                executor.safeSummary(row.getReason()), executor.safeSummary(row.getBeforeSummary()),
                executor.safeSummary(row.getAfterSummary()), row.getStatus(),
                "SUCCESS".equals(row.getStatus()) ? executor.readResult(row) : null,
                row.getErrorCode(), PlatformOperationExecutor.safeTraceId(row.getTraceId()),
                row.getStartedAt(), row.getCompletedAt(),
                row.getDurationMs() == null ? null : PlatformInputs.measured(row.getDurationMs()));
    }
}
