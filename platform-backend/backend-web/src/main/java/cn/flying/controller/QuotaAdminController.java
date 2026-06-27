package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.file.QuotaRolloutAuditUpsertVO;
import cn.flying.dao.vo.file.QuotaRolloutAuditVO;
import cn.flying.service.QuotaRolloutAuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 配额灰度扩容管理员控制器。
 * 提供灰度审计记录的写入与查询能力，用于扩容治理留痕。
 */
@RestController
@RequestMapping("/api/v1/admin/quota/rollout/audits")
@PreAuthorize("isAdmin()")
@Tag(name = "管理员-配额灰度审计", description = "配额灰度扩容审计管理")


@RequiredArgsConstructor
public class QuotaAdminController {

    private final QuotaRolloutAuditService quotaRolloutAuditService;

    /**
     * 写入或更新配额灰度扩容审计记录。
     *
     * @param userId 当前用户ID
     * @param tenantId 当前租户ID
     * @param request 审计写入请求
     * @return 审计记录
     */
    @PostMapping("")
    @Operation(summary = "写入或更新配额灰度审计记录")
    @OperationLog(module = "管理员-配额灰度审计", operationType = "写入", description = "写入或更新配额灰度审计记录")
    public Result<QuotaRolloutAuditVO> upsertQuotaRolloutAudit(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @RequestBody @Valid QuotaRolloutAuditUpsertVO request) {
        return Result.success(quotaRolloutAuditService.upsertAudit(userId, tenantId, request));
    }

    /**
     * 查询当前租户在某一灰度批次下的审计记录。
     *
     * @param tenantId 当前租户ID
     * @param batchId 灰度批次ID
     * @return 审计记录
     */
    @GetMapping("")
    @Operation(summary = "查询配额灰度审计记录")
    @OperationLog(module = "管理员-配额灰度审计", operationType = "查询", description = "查询配额灰度审计记录")
    public Result<QuotaRolloutAuditVO> getQuotaRolloutAudit(
            @RequestAttribute(Const.ATTR_TENANT_ID) Long tenantId,
            @Parameter(description = "灰度批次ID") @RequestParam String batchId) {
        return Result.success(quotaRolloutAuditService.getLatestAudit(batchId, tenantId));
    }
}
