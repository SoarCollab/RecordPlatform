package cn.flying.service.platform;

import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.Tenant;
import cn.flying.dao.mapper.platform.PlatformUserMapper;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.ChangeTenantMemberStatusRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformPage;
import cn.flying.dao.vo.platform.PlatformUserVO;
import cn.flying.service.admin.TenantInvitationService;
import cn.flying.service.admin.TenantMemberCommandService;
import cn.flying.service.admin.TenantMemberQueryService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.LongConsumer;

/** Explicit platform orchestration of reviewed, tenant-isolated member and invitation capabilities. */
@Service
@RequiredArgsConstructor
public class PlatformUserService {

    private final PlatformUserMapper mapper;
    private final PlatformTenantQueryService tenantQueries;
    private final PlatformTenantCommandService tenantCommands;
    private final TenantMemberQueryService memberQueries;
    private final TenantMemberCommandService memberCommands;
    private final TenantInvitationService invitations;
    private final PlatformOperationExecutor executor;

    /** Returns a bounded global allowlist of tenant member metadata without secrets or platform accounts. */
    public IPage<PlatformUserVO> list(long pageNum, long pageSize, String keyword, String role,
                                     Integer status, Long tenantId) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_READ);
        long offset = PlatformInputs.offset(pageNum, pageSize);
        String search = PlatformInputs.keyword(keyword);
        PlatformInputs.memberFilters(role, status);
        if (tenantId != null) {
            tenantQueries.requireTarget(tenantId);
        }
        long total = mapper.countPage(search, role, status, tenantId);
        List<PlatformUserVO> rows = mapper.selectPage(offset, pageSize, search, role, status, tenantId).stream()
                .map(row -> new PlatformUserVO(IdUtils.toExternalUserId(row.getId()),
                        IdUtils.toExternalId(row.getTenantId()), row.getUsername(), row.getNickname(),
                        row.getRole(), row.getStatus(), row.getRegisterTime(), row.getLastLoginTime())).toList();
        return new PlatformPage<PlatformUserVO>(pageNum, pageSize, total).setRecords(rows);
    }

    /** Reuses the reviewed tenant member projection only inside an explicitly forced target context. */
    public IPage<TenantMemberVO> members(Long tenantId, long pageNum, long pageSize, String keyword,
                                         String role, Integer status) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_READ);
        PlatformInputs.offset(pageNum, pageSize);
        String search = PlatformInputs.keyword(keyword);
        PlatformInputs.memberFilters(role, status);
        tenantQueries.requireTarget(tenantId);
        return TenantContext.callWithTenantIsolation(tenantId,
                () -> PlatformPage.from(memberQueries.list(tenantId, pageNum, pageSize, search, role, status)));
    }

    /** Lists at most the reviewed invitation service's 100 recent metadata rows for one target. */
    public List<TenantInvitationVO> invitations(Long tenantId) {
        SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_READ);
        tenantQueries.requireTarget(tenantId);
        return TenantContext.callWithTenantIsolation(tenantId, () -> invitations.list(tenantId));
    }

    /** Applies the existing last-admin and self-operation role rules under target isolation. */
    public PlatformMutationVO changeRole(Long tenantId, Long userId, String key,
                                         ChangeTenantMemberRoleRequest request) {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_WRITE);
        return memberCommand(PlatformOperationType.USER_ROLE_CHANGE, tenantId, userId, key, request,
                request == null ? null : request.reason(),
                ignored -> memberCommands.changeRole(tenantId, actorId, userId, request.role(), request.reason()));
    }

    /** Applies the existing status invariants and account-session revocation under target isolation. */
    public PlatformMutationVO changeStatus(Long tenantId, Long userId, String key,
                                           ChangeTenantMemberStatusRequest request) {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_WRITE);
        return memberCommand(PlatformOperationType.USER_STATUS_CHANGE, tenantId, userId, key, request,
                request == null ? null : request.reason(),
                ignored -> memberCommands.changeStatus(tenantId, actorId, userId, request.status(), request.reason()));
    }

    /** Reuses atomic authorization-version and SSE revocation without exposing that internal version. */
    public PlatformMutationVO revokeSessions(Long tenantId, Long userId, String key, TenantMemberReasonRequest request) {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_WRITE);
        return memberCommand(PlatformOperationType.USER_SESSIONS_REVOKE, tenantId, userId, key, request,
                request == null ? null : request.reason(),
                ignored -> memberCommands.revokeSessions(tenantId, actorId, userId, request.reason()));
    }

    /** Invites any tenant role, including a new tenant's first administrator, through digest-only direct mail. */
    public PlatformMutationVO invite(Long tenantId, String key, CreateTenantInvitationRequest request) {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_WRITE);
        PlatformInputs.tenantId(tenantId);
        return executor.execute(PlatformOperationType.INVITATION_CREATE, tenantId, null, null,
                key, request, request == null ? null : request.reason(), () -> {
                    Tenant target = tenantCommands.lockTarget(tenantId);
                    if (!Integer.valueOf(1).equals(target.getStatus())) {
                        throw new GeneralException(ResultEnum.PLATFORM_TENANT_INACTIVE);
                    }
                    return TenantContext.callWithTenantIsolation(tenantId, () -> {
                        TenantInvitationVO created = invitations.create(tenantId, actorId, request);
                        Long invitationId = created == null ? null : IdUtils.fromExternalId(created.id());
                        if (invitationId == null || created.id() == null || !created.id().startsWith("E")) {
                            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
                        }
                        return new PlatformChange(tenantId, invitationId, null, null,
                                null, "status=PENDING; role=" + request.role());
                    });
                });
    }

    /** Revokes one target-owned pending invitation through the existing conditional update. */
    public PlatformMutationVO revokeInvitation(Long tenantId, Long invitationId, String key,
                                               TenantMemberReasonRequest request) {
        Long actorId = SecurityUtils.requirePlatformPermission(PlatformPermissions.USER_WRITE);
        PlatformInputs.tenantId(tenantId);
        return executor.execute(PlatformOperationType.INVITATION_REVOKE, tenantId, invitationId, null,
                key, request, request == null ? null : request.reason(), () -> {
                    tenantCommands.lockTarget(tenantId);
                    return TenantContext.callWithTenantIsolation(tenantId, () -> {
                        invitations.revoke(tenantId, actorId, invitationId, request.reason());
                        return new PlatformChange(tenantId, invitationId, null, null, "status=PENDING", "status=REVOKED");
                    });
                });
    }

    /** Serializes a reviewed member command with its safe before/after evidence and restores system context. */
    private PlatformMutationVO memberCommand(PlatformOperationType operation, Long tenantId, Long userId,
                                             String key, Object request, String reason, LongConsumer action) {
        PlatformInputs.tenantId(tenantId);
        return executor.execute(operation, tenantId, userId, null, key, request, reason, () -> {
            tenantCommands.lockTarget(tenantId);
            return TenantContext.callWithTenantIsolation(tenantId, () -> {
                TenantMemberVO before = memberQueries.get(tenantId, userId);
                action.accept(userId);
                TenantMemberVO after = memberQueries.get(tenantId, userId);
                return new PlatformChange(tenantId, userId, null, null,
                        memberSummary(before), memberSummary(after));
            });
        });
    }

    /** Limits member audit summaries to role and status, excluding email and authorization versions. */
    private String memberSummary(TenantMemberVO member) {
        return "role=" + member.role() + "; status=" + member.status();
    }
}
