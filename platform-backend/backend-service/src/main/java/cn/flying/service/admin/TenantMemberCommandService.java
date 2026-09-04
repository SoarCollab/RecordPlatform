package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.service.auth.AccountSessionRevocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** Enforces tenant member role, status and session invariants. */
@Service
@RequiredArgsConstructor
public class TenantMemberCommandService {

    private final AccountMapper accountMapper;
    private final TenantMapper tenantMapper;
    private final AccountSessionRevocationService sessionRevocationService;
    private final TenantMemberAuditService auditService;

    /** Changes a tenant role while preserving an active administrator under concurrency. */
    @Transactional
    public void changeRole(Long tenantId, Long actorId, Long accountId, String newRole, String reason) {
        requireAssignableRole(newRole);
        lockTenant(tenantId);
        Account target = requireMember(tenantId, accountId);
        String sanitizedReason = auditService.sanitizeReason(reason);
        if (Objects.equals(target.getRole(), newRole)) {
            return;
        }
        if (Objects.equals(actorId, accountId)) {
            throw new GeneralException(ResultEnum.TENANT_ADMIN_SELF_OPERATION_FORBIDDEN);
        }
        preserveLastAdministrator(tenantId, target, !UserRole.ROLE_ADMINISTER.getRole().equals(newRole));
        if (accountMapper.updateTenantMemberRole(tenantId, accountId, newRole) != 1) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        auditService.record(tenantId, actorId, accountId, null, "ROLE_CHANGED",
                target.getRole(), newRole, sanitizedReason);
        sessionRevocationService.invalidateAfterVersionChange(tenantId, accountId);
    }

    /** Enables or disables a member while preserving the last active administrator. */
    @Transactional
    public void changeStatus(Long tenantId, Long actorId, Long accountId, Integer status, String reason) {
        if (status == null || (status != 0 && status != 1)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        lockTenant(tenantId);
        Account target = requireMember(tenantId, accountId);
        String sanitizedReason = auditService.sanitizeReason(reason);
        if (Objects.equals(target.getStatus(), status)) {
            return;
        }
        if (Objects.equals(actorId, accountId) && status == 0) {
            throw new GeneralException(ResultEnum.TENANT_ADMIN_SELF_OPERATION_FORBIDDEN);
        }
        preserveLastAdministrator(tenantId, target, status == 0);
        if (accountMapper.updateTenantMemberStatus(tenantId, accountId, status) != 1) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        auditService.record(tenantId, actorId, accountId, null, "STATUS_CHANGED",
                String.valueOf(target.getStatus()), String.valueOf(status), sanitizedReason);
        sessionRevocationService.invalidateAfterVersionChange(tenantId, accountId);
    }

    /** Revokes all JWT and SSE state for one tenant member. */
    @Transactional
    public void revokeSessions(Long tenantId, Long actorId, Long accountId, String reason) {
        lockTenant(tenantId);
        Account target = requireMember(tenantId, accountId);
        String sanitizedReason = auditService.sanitizeReason(reason);
        sessionRevocationService.revokeAllSessions(tenantId, accountId);
        auditService.record(tenantId, actorId, accountId, null, "SESSIONS_REVOKED",
                String.valueOf(target.getAuthVersion()), String.valueOf(target.getAuthVersion() + 1), sanitizedReason);
    }

    /** Acquires the tenant mutation fence shared by all last-admin-affecting operations. */
    private void lockTenant(Long tenantId) {
        if (tenantId == null || tenantId < 0 || !Objects.equals(tenantMapper.lockTenantForMemberMutation(tenantId), tenantId)) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
    }

    /** Loads the target only inside the authenticated tenant. */
    private Account requireMember(Long tenantId, Long accountId) {
        if (accountId == null) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        Account account = accountMapper.selectTenantMemberForUpdate(tenantId, accountId);
        if (account == null) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        return account;
    }

    /** Rejects the mutation if it would remove the last active tenant administrator. */
    private void preserveLastAdministrator(Long tenantId, Account target, boolean removesAdmin) {
        if (removesAdmin && UserRole.ROLE_ADMINISTER.getRole().equals(target.getRole())
                && Objects.equals(target.getStatus(), 1)
                && accountMapper.countActiveTenantAdministrators(tenantId) <= 1) {
            throw new GeneralException(ResultEnum.LAST_TENANT_ADMIN_REQUIRED);
        }
    }

    /** Restricts tenant assignment to the three data-plane roles. */
    private void requireAssignableRole(String role) {
        UserRole parsed = UserRole.getRole(role);
        if (!parsed.isTenantRole()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
    }
}
