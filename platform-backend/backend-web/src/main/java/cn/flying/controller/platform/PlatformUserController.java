package cn.flying.controller.platform;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.PlatformPermissions;
import cn.flying.common.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import cn.flying.dao.vo.admin.ChangeTenantMemberRoleRequest;
import cn.flying.dao.vo.admin.ChangeTenantMemberStatusRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberReasonRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.dao.vo.platform.PlatformMutationVO;
import cn.flying.dao.vo.platform.PlatformUserVO;
import cn.flying.service.platform.PlatformUserService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Cross-tenant metadata and explicit target-member administration API. */
@RestController
@RequestMapping("/api/v1/platform")
@Tag(name = "平台管理")
@RequiredArgsConstructor
@Validated
public class PlatformUserController {

    private final PlatformUserService service;

    /** Returns a bounded tenant-member metadata page without credential fields. */
    @GetMapping("/users")
    @Operation(operationId = "platformListUsers", summary = "分页查询平台用户")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "分页查询平台用户",
            saveRequestData = false, saveResponseData = false)
    public Result<IPage<PlatformUserVO>> list(@RequestParam(defaultValue = "1") @Min(1) @Max(9007199254740991L) long pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "user|admin|monitor") String role,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status,
            @RequestParam(required = false) String tenantId) {
        return Result.success(service.list(pageNum, pageSize, keyword, role, status,
                tenantId == null ? null : PlatformIds.tenant(tenantId)));
    }

    /** Reuses the isolated tenant member read model for an explicit target. */
    @GetMapping("/tenants/{tenantId}/users")
    @Operation(operationId = "platformListTenantMembers", summary = "查询目标租户成员")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询目标租户成员",
            saveRequestData = false, saveResponseData = false)
    public Result<IPage<TenantMemberVO>> members(@PathVariable String tenantId, @RequestParam(defaultValue = "1") @Min(1) @Max(9007199254740991L) long pageNum,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) long pageSize,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(required = false) @Pattern(regexp = "user|admin|monitor") String role,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status) {
        return Result.success(service.members(PlatformIds.tenant(tenantId), pageNum, pageSize, keyword, role, status));
    }

    /** Applies reviewed tenant role invariants through an explicit target command. */
    @PutMapping("/tenants/{tenantId}/users/{userId}/role")
    @Operation(operationId = "platformChangeTenantMemberRole", summary = "修改目标租户成员角色")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "修改", description = "修改目标租户成员角色",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> changeRole(@PathVariable String tenantId, @PathVariable String userId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody ChangeTenantMemberRoleRequest request) {
        return Result.success(service.changeRole(PlatformIds.tenant(tenantId), PlatformIds.user(userId), key, request));
    }

    /** Applies reviewed member lifecycle and session invalidation rules. */
    @PutMapping("/tenants/{tenantId}/users/{userId}/status")
    @Operation(operationId = "platformChangeTenantMemberStatus", summary = "修改目标租户成员状态")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "修改", description = "修改目标租户成员状态",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> changeStatus(@PathVariable String tenantId, @PathVariable String userId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody ChangeTenantMemberStatusRequest request) {
        return Result.success(service.changeStatus(PlatformIds.tenant(tenantId), PlatformIds.user(userId), key, request));
    }

    /** Revokes all sessions without exposing authorization versions. */
    @PostMapping("/tenants/{tenantId}/users/{userId}/sessions/revoke")
    @Operation(operationId = "platformRevokeTenantMemberSessions", summary = "撤销目标租户成员会话")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "撤销", description = "撤销目标租户成员会话",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> revokeSessions(@PathVariable String tenantId, @PathVariable String userId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody TenantMemberReasonRequest request) {
        return Result.success(service.revokeSessions(PlatformIds.tenant(tenantId), PlatformIds.user(userId), key, request));
    }

    /** Lists invitation metadata without capability or digest material. */
    @GetMapping("/tenants/{tenantId}/invitations")
    @Operation(operationId = "platformListTenantInvitations", summary = "查询目标租户邀请")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_READ + "')")
    @OperationLog(module = "平台管理", operationType = "查询", description = "查询目标租户邀请",
            saveRequestData = false, saveResponseData = false)
    public Result<List<TenantInvitationVO>> invitations(@PathVariable String tenantId) {
        return Result.success(service.invitations(PlatformIds.tenant(tenantId)));
    }

    /** Invites a member or first administrator through one-time direct mail. */
    @PostMapping("/tenants/{tenantId}/invitations")
    @Operation(operationId = "platformInviteTenantMember", summary = "邀请目标租户成员")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "邀请", description = "邀请目标租户成员",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> invite(@PathVariable String tenantId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody CreateTenantInvitationRequest request) {
        return Result.success(service.invite(PlatformIds.tenant(tenantId), key, request));
    }

    /** Revokes a pending target invitation without accepting another ID type. */
    @DeleteMapping("/tenants/{tenantId}/invitations/{invitationId}")
    @Operation(operationId = "platformRevokeTenantInvitation", summary = "撤销目标租户邀请")
    @PreAuthorize("hasRole('platform_admin') and hasAuthority('" + PlatformPermissions.USER_WRITE + "')")
    @OperationLog(module = "平台管理", operationType = "撤销", description = "撤销目标租户邀请",
            saveRequestData = false, saveResponseData = false)
    public Result<PlatformMutationVO> revokeInvitation(@PathVariable String tenantId, @PathVariable String invitationId, @RequestHeader("Idempotency-Key") @Pattern(regexp = PlatformIds.UUID_PATTERN) String key,
            @Valid @RequestBody TenantMemberReasonRequest request) {
        return Result.success(service.revokeInvitation(
                PlatformIds.tenant(tenantId), PlatformIds.entity(invitationId), key, request));
    }
}
