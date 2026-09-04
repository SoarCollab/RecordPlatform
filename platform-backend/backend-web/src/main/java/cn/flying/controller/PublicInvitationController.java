package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.vo.admin.AcceptTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantMemberVO;
import cn.flying.service.admin.TenantInvitationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Anonymous one-time invitation acceptance endpoint. */
@RestController
@RequestMapping("/api/v1/public/invitations")
@Tag(name = "公开邀请")
@RequiredArgsConstructor
public class PublicInvitationController {

    private final TenantInvitationService invitationService;

    /** Accepts an opaque token without persisting or logging its raw value or password. */
    @PostMapping("/accept")
    @Operation(summary = "接受租户成员邀请",
            description = "匿名 POST；忽略调用方租户头，仅由一次性邀请令牌恢复所属租户。")
    @SecurityRequirements
    @OperationLog(module = "租户成员", operationType = "接受邀请", description = "接受租户成员邀请",
            saveRequestData = false, saveResponseData = false)
    public Result<TenantMemberVO> accept(@Valid @RequestBody AcceptTenantInvitationRequest request) {
        return Result.success(invitationService.accept(request));
    }
}
