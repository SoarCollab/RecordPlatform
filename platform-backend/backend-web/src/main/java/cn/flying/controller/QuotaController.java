package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.file.QuotaStatusVO;
import cn.flying.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文件配额查询控制器。
 */
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "文件配额", description = "文件配额状态查询")
public class QuotaController {

    @Resource
    private QuotaService quotaService;

    /**
     * 查询当前用户配额状态。
     *
     * @param userId 当前用户ID
     * @param tenantId 当前租户ID
     * @return 配额状态
     */
    @GetMapping("/quota")
    @Operation(summary = "查询当前配额状态")
    @OperationLog(module = "文件配额", operationType = "查询", description = "查询当前配额状态")
    public Result<QuotaStatusVO> getQuotaStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestAttribute(value = Const.ATTR_TENANT_ID, required = false) Long tenantId) {
        Long resolvedTenantId = tenantId != null ? tenantId : TenantContext.getTenantIdOrDefault();
        return Result.success(quotaService.getCurrentQuotaStatus(resolvedTenantId, userId));
    }
}
