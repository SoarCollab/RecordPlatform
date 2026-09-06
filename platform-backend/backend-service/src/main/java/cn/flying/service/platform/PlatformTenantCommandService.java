package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.QuotaPolicy;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.platform.PlatformQuotaMapper;
import cn.flying.dao.mapper.platform.PlatformTenantMapper;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.dao.vo.platform.ChangePlatformTenantStatusRequest;
import cn.flying.dao.vo.platform.CreatePlatformTenantRequest;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.UpdatePlatformQuotaRequest;
import cn.flying.dao.vo.platform.UpdatePlatformTenantRequest;
import cn.flying.service.QuotaService;
import cn.flying.service.admin.TenantMemberAuditService;
import cn.flying.service.auth.TenantSessionRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** Explicit tenant lifecycle and quota commands composed inside durable platform operations. */
@Service
@RequiredArgsConstructor
public class PlatformTenantCommandService {

    private final PlatformTenantMapper tenantMapper;
    private final PlatformQuotaMapper quotaMapper;
    private final QuotaService quotaService;
    private final TenantSessionRevocationService sessionRevocationService;
    private final TenantMemberAuditService sanitizer;
    private final PlatformOperationExecutor executor;

    /** Creates a tenant plus its initial quota atomically without creating any account or password. */
    public PlatformMutationVO create(String idempotencyKey, CreatePlatformTenantRequest request) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_WRITE);
        return executor.execute(PlatformOperationType.TENANT_CREATE, null, null, null,
                idempotencyKey, request, request == null ? null : request.reason(), () -> {
                    Long tenantId = IdUtils.nextEntityId();
                    Tenant tenant = new Tenant().setId(tenantId).setName(request.name().trim()).setCode(request.code());
                    try {
                        if (tenantMapper.insertTenant(tenant) != 1) {
                            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
                        }
                    } catch (DuplicateKeyException exception) {
                        throw new GeneralException(ResultEnum.PLATFORM_TENANT_CODE_EXISTS);
                    }
                    TenantContext.runWithTenantIsolation(tenantId, () -> {
                        QuotaStatusVO defaults = quotaService.getCurrentQuotaStatus(tenantId, 0L);
                        if (defaults == null) {
                            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
                        }
                        Long bytes = request.maxStorageBytes() == null
                                ? PlatformInputs.measured(defaults.tenantMaxStorageBytes()) : request.maxStorageBytes();
                        Long count = request.maxFileCount() == null
                                ? PlatformInputs.measured(defaults.tenantMaxFileCount()) : request.maxFileCount();
                        insertQuota(tenantId, bytes, count, 0L);
                    });
                    return new PlatformChange(tenantId, tenantId, null, 0L, null,
                            "status=1; tenantVersion=0; quotaVersion=0");
                });
    }

    /** Changes safe metadata after a current tenant lock and optimistic version comparison. */
    public PlatformMutationVO update(Long tenantId, String idempotencyKey, UpdatePlatformTenantRequest request) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_WRITE);
        PlatformInputs.tenantId(tenantId);
        return executor.execute(PlatformOperationType.TENANT_UPDATE, tenantId, tenantId, null,
                idempotencyKey, request, request == null ? null : request.reason(), () -> {
                    Tenant target = lockTarget(tenantId);
                    PlatformInputs.version(target.getVersion(), request.expectedVersion());
                    return TenantContext.callWithTenantIsolation(tenantId, () -> {
                        if (tenantMapper.updateName(tenantId, request.name().trim(), request.expectedVersion()) != 1) {
                            throw new GeneralException(ResultEnum.PLATFORM_VERSION_CONFLICT);
                        }
                        return new PlatformChange(tenantId, tenantId, null, target.getVersion() + 1,
                                "metadataVersion=" + target.getVersion(),
                                "metadataVersion=" + (target.getVersion() + 1));
                    });
                });
    }

    /** Disables or restores a tenant and fences all old tenant sessions before the transaction commits. */
    public PlatformMutationVO changeStatus(Long tenantId, String idempotencyKey,
                                           ChangePlatformTenantStatusRequest request) {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.TENANT_WRITE);
        PlatformInputs.tenantId(tenantId);
        return executor.execute(PlatformOperationType.TENANT_STATUS_CHANGE, tenantId, tenantId, null,
                idempotencyKey, request, request == null ? null : request.reason(), () -> {
                    Tenant target = lockTarget(tenantId);
                    PlatformInputs.version(target.getVersion(), request.expectedVersion());
                    if (tenantId == 0L && request.status() == 0) {
                        throw new GeneralException(ResultEnum.PLATFORM_SYSTEM_TENANT_PROTECTED);
                    }
                    return TenantContext.callWithTenantIsolation(tenantId, () -> {
                        if (tenantMapper.updateStatus(tenantId, request.status(), request.expectedVersion(),
                                sanitizer.sanitizeReason(request.reason()), actorId) != 1) {
                            throw new GeneralException(ResultEnum.PLATFORM_VERSION_CONFLICT);
                        }
                        sessionRevocationService.invalidateAfterLifecycleChange(tenantId);
                        return new PlatformChange(tenantId, tenantId, null, target.getVersion() + 1,
                                "status=" + target.getStatus() + "; version=" + target.getVersion(),
                                "status=" + request.status() + "; version=" + (target.getVersion() + 1));
                    });
                });
    }

    /** Updates only the target tenant's exact override while preserving existing rollout enforcement policy. */
    public PlatformMutationVO updateQuota(Long tenantId, String idempotencyKey, UpdatePlatformQuotaRequest request) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.QUOTA_WRITE);
        PlatformInputs.tenantId(tenantId);
        return executor.execute(PlatformOperationType.QUOTA_UPDATE, tenantId, tenantId, null,
                idempotencyKey, request, request == null ? null : request.reason(), () -> {
                    lockTarget(tenantId);
                    return TenantContext.callWithTenantIsolation(tenantId, () -> {
                        QuotaPolicy current = quotaMapper.lockOverride(tenantId);
                        Long version = current == null ? 0L : current.getVersion();
                        PlatformInputs.version(version, request.expectedVersion());
                        if (current == null) {
                            insertQuota(tenantId, request.maxStorageBytes(), request.maxFileCount(), version + 1);
                        } else if (quotaMapper.updateOverride(tenantId, request.maxStorageBytes(),
                                request.maxFileCount(), request.expectedVersion()) != 1) {
                            throw new GeneralException(ResultEnum.PLATFORM_VERSION_CONFLICT);
                        }
                        return new PlatformChange(tenantId, tenantId, null, version + 1,
                                current == null ? "override=absent; version=0"
                                        : "storageBytes=" + current.getMaxStorageBytes() + "; fileCount="
                                        + current.getMaxFileCount() + "; version=" + version,
                                "storageBytes=" + request.maxStorageBytes() + "; fileCount="
                                        + request.maxFileCount() + "; version=" + (version + 1));
                    });
                });
    }

    /** Locks current nondeleted metadata before an already-authorized command switches target context. */
    Tenant lockTarget(Long tenantId) {
        Tenant target = tenantMapper.lockTenant(tenantId);
        if (target == null) {
            throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        }
        return target;
    }

    /** Inserts an exact TENANT override through normal forced tenant isolation. */
    private void insertQuota(Long tenantId, Long bytes, Long count, Long version) {
        QuotaPolicy policy = new QuotaPolicy().setId(IdUtils.nextEntityId()).setTenantId(tenantId)
                .setScopeType("TENANT").setScopeId(tenantId).setMaxStorageBytes(bytes)
                .setMaxFileCount(count).setStatus(1).setVersion(version);
        if (quotaMapper.insertOverride(policy) != 1) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
    }
}
