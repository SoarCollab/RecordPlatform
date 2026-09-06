package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.ChangeTenantMemberStatusRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.service.admin.TenantInvitationService;
import cn.flying.service.admin.TenantMemberCommandService;
import cn.flying.service.admin.TenantMemberQueryService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Tenant administrator member-management API. */
@RestController
@RequestMapping("/api/v1/admin/users")
@Tag(name = "租户成员管理")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('admin') and hasPerm('tenant:user:admin')")
public class TenantUserAdminController {

    private final TenantMemberQueryService queryService;
    private final TenantMemberCommandService commandService;
    private final TenantInvitationService invitationService;

    /** Lists members in the authenticated tenant; no target tenant selector is accepted. */
    @GetMapping
    @Operation(summary = "分页查询本租户成员")
    @OperationLog(module = "租户成员", operationType = "查询", description = "分页查询本租户成员",
            saveRequestData = false, saveResponseData = false)
    public Result<IPage<TenantMemberVO>> list(
            @RequestParam(defaultValue = "1") @Min(1) long pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "user|admin|monitor") String role,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status) {
        return Result.success(queryService.list(SecurityUtils.getTenantId(), pageNum, pageSize, keyword, role, status));
    }

    /** Gets one member in the authenticated tenant. */
    @GetMapping("/{userId}")
    @Operation(summary = "查询本租户成员详情")
    @OperationLog(module = "租户成员", operationType = "查询", description = "查询本租户成员详情",
            saveRequestData = false, saveResponseData = false)
    public Result<TenantMemberVO> get(@PathVariable String userId) {
        return Result.success(queryService.get(SecurityUtils.getTenantId(), decodeUserId(userId)));
    }

    /** Creates a one-time invitation and sends it without returning token material. */
    @PostMapping("/invitations")
    @Operation(summary = "邀请租户成员")
    @OperationLog(module = "租户成员", operationType = "邀请", description = "创建租户成员邀请",
            saveRequestData = false, saveResponseData = false)
    public Result<TenantInvitationVO> invite(@Valid @RequestBody CreateTenantInvitationRequest request) {
        return Result.success(invitationService.create(
                SecurityUtils.getTenantId(), requireActor(), request));
    }

    /** Lists invitation metadata without token or digest fields. */
    @GetMapping("/invitations")
    @Operation(summary = "查询本租户邀请")
    @OperationLog(module = "租户成员", operationType = "查询", description = "查询本租户成员邀请",
            saveRequestData = false, saveResponseData = false)
    public Result<List<TenantInvitationVO>> invitations() {
        return Result.success(invitationService.list(SecurityUtils.getTenantId()));
    }

    /** Revokes a pending invitation in the authenticated tenant. */
    @DeleteMapping("/invitations/{invitationId}")
    @Operation(summary = "撤销租户成员邀请")
    @OperationLog(module = "租户成员", operationType = "撤销", description = "撤销租户成员邀请",
            saveRequestData = false, saveResponseData = false)
    public Result<String> revokeInvitation(@PathVariable String invitationId,
                                           @Valid @RequestBody TenantMemberReasonRequest request) {
        invitationService.revoke(SecurityUtils.getTenantId(), requireActor(),
                decodeInvitationId(invitationId), request.reason());
        return Result.success();
    }

    /** Changes a tenant member's role. */
    @PutMapping("/{userId}/role")
    @Operation(summary = "修改租户成员角色")
    @OperationLog(module = "租户成员", operationType = "修改", description = "修改租户成员角色",
            saveRequestData = false, saveResponseData = false)
    public Result<String> changeRole(@PathVariable String userId,
                                     @Valid @RequestBody ChangeTenantMemberRoleRequest request) {
        commandService.changeRole(SecurityUtils.getTenantId(), requireActor(), decodeUserId(userId),
                request.role(), request.reason());
        return Result.success();
    }

    /** Enables or disables a tenant member. */
    @PutMapping("/{userId}/status")
    @Operation(summary = "修改租户成员状态")
    @OperationLog(module = "租户成员", operationType = "修改", description = "修改租户成员状态",
            saveRequestData = false, saveResponseData = false)
    public Result<String> changeStatus(@PathVariable String userId,
                                       @Valid @RequestBody ChangeTenantMemberStatusRequest request) {
        commandService.changeStatus(SecurityUtils.getTenantId(), requireActor(), decodeUserId(userId),
                request.status(), request.reason());
        return Result.success();
    }

    /** Revokes every active JWT and SSE session for a tenant member. */
    @PostMapping("/{userId}/sessions/revoke")
    @Operation(summary = "强制租户成员退出")
    @OperationLog(module = "租户成员", operationType = "撤销", description = "撤销租户成员全部会话",
            saveRequestData = false, saveResponseData = false)
    public Result<String> revokeSessions(@PathVariable String userId,
                                         @Valid @RequestBody TenantMemberReasonRequest request) {
        commandService.revokeSessions(SecurityUtils.getTenantId(), requireActor(),
                decodeUserId(userId), request.reason());
        return Result.success();
    }

    /** Decodes a user SecureId and rejects entity-typed invitation IDs. */
    private Long decodeUserId(String externalId) {
        return decodeTypedId(externalId, 'U');
    }

    /** Decodes an invitation SecureId and rejects user-typed IDs. */
    private Long decodeInvitationId(String externalId) {
        return decodeTypedId(externalId, 'E');
    }

    /** Preserves SecureId type separation before revealing tenant-owned records. */
    private Long decodeTypedId(String externalId, char requiredPrefix) {
        if (externalId == null || externalId.isEmpty() || externalId.charAt(0) != requiredPrefix) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        Long id = IdUtils.fromExternalId(externalId);
        if (id == null) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        return id;
    }

    /** Requires the authenticated actor identifier populated by JWT processing. */
    private Long requireActor() {
        Long actorId = SecurityUtils.getUserId();
        if (actorId == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHENTICATED);
        }
        return actorId;
    }
}
