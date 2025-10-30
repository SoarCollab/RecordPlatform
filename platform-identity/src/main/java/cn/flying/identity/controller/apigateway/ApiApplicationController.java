package cn.flying.identity.controller.apigateway;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.flying.identity.dto.apigateway.ApiApplication;
import cn.flying.identity.service.apigateway.ApiApplicationService;
import cn.flying.identity.vo.RestResponse;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API應用管理控制器
 * 採用全局異常策略，僅處理成功響應
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/applications")
@Tag(name = "API应用管理", description = "提供应用注册、审核、管理等功能")
@SaCheckLogin
public class ApiApplicationController extends BaseApiGatewayController {

    @Resource
    private ApiApplicationService applicationService;

    /**
     * 註冊新應用
     */
    @PostMapping
    @Operation(summary = "注册应用", description = "开发者注册新的API应用")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "应用注册成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "409", description = "应用已存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> registerApplication(
            @Parameter(description = "应用名称", required = true) @RequestParam String appName,
            @Parameter(description = "应用描述") @RequestParam(required = false) String appDescription,
            @Parameter(description = "应用类型：1-Web，2-移动，3-服务端，4-其他", required = true) @RequestParam Integer appType,
            @Parameter(description = "应用官网") @RequestParam(required = false) String appWebsite,
            @Parameter(description = "回调URL") @RequestParam(required = false) String callbackUrl) {

        Long ownerId = requireCurrentUserId();
        log.info("注册应用请求: appName={}, ownerId={}, appType={}", appName, ownerId, appType);

        Map<String, Object> data = applicationService.registerApplication(
                appName, appDescription, ownerId, appType, appWebsite, callbackUrl);
        return ResponseEntity.status(HttpStatus.CREATED).body(RestResponse.created(data));
    }

    /**
     * 审核应用
     */
    @PostMapping("/{appId}/approval")
    @Operation(summary = "审核应用", description = "管理员审核应用注册申请")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "审核成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:app:approve")
    public ResponseEntity<RestResponse<Void>> approveApplication(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId,
            @Parameter(description = "是否通过", required = true) @RequestParam boolean approved,
            @Parameter(description = "拒绝原因") @RequestParam(required = false) String rejectReason) {

        Long approveBy = requireCurrentUserId();
        log.info("审核应用: appId={}, approved={}, approveBy={}", appId, approved, approveBy);

        applicationService.approveApplication(appId, approved, approveBy, rejectReason);
        return ResponseEntity.ok(RestResponse.ok("审核成功", null));
    }

    /**
     * 更新應用啟用狀態
     */
    @PutMapping("/{appId}/status")
    @Operation(summary = "更新应用状态", description = "启用或禁用指定的应用")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "操作成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:app:manage")
    public ResponseEntity<RestResponse<Void>> updateApplicationStatus(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId,
            @Parameter(description = "是否启用", required = true) @RequestParam boolean enabled,
            @Parameter(description = "操作原因") @RequestParam(required = false) String reason) {

        log.info("更新应用状态: appId={}, enabled={}", appId, enabled);
        if (enabled) {
            applicationService.enableApplication(appId);
            return ResponseEntity.ok(RestResponse.ok("应用已启用", null));
        } else {
            applicationService.disableApplication(appId, reason);
            return ResponseEntity.ok(RestResponse.ok("应用已禁用", null));
        }
    }

    /**
     * 删除应用
     */
    @DeleteMapping("/{appId}")
    @Operation(summary = "删除应用", description = "永久删除应用及其所有关联数据")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "删除成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:app:delete")
    public ResponseEntity<Void> deleteApplication(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId) {

        log.info("删除应用: appId={}", appId);
        applicationService.deleteApplication(appId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 更新应用信息
     */
    @PutMapping("/{appId}")
    @Operation(summary = "更新应用信息", description = "更新应用的基本信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:app:update")
    public ResponseEntity<RestResponse<Void>> updateApplication(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId,
            @RequestBody @Validated ApiApplication application) {

        application.setId(appId);
        log.info("更新应用信息: appId={}", appId);

        applicationService.updateApplication(application);
        return ResponseEntity.ok(RestResponse.ok("应用信息已更新", null));
    }

    /**
     * 获取应用详情
     */
    @GetMapping("/{appId}")
    @Operation(summary = "获取应用详情", description = "查询指定应用的详细信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    public ResponseEntity<RestResponse<ApiApplication>> getApplicationById(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId) {

        log.info("查询应用详情: appId={}", appId);
        ApiApplication application = applicationService.getApplicationById(appId);
        return ResponseEntity.ok(RestResponse.ok("获取成功", application));
    }

    /**
     * 根據應用標識碼查詢
     */
    @GetMapping("/by-code/{appCode}")
    @Operation(summary = "根据标识码获取应用", description = "通过应用标识码查询应用信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    public ResponseEntity<RestResponse<ApiApplication>> getApplicationByCode(
            @Parameter(description = "应用标识码", required = true) @PathVariable String appCode) {

        log.info("根据标识码查询应用: appCode={}", appCode);
        ApiApplication application = applicationService.getApplicationByCode(appCode);
        return ResponseEntity.ok(RestResponse.ok("获取成功", application));
    }

    /**
     * 獲取當前用戶的應用
     */
    @GetMapping("/my")
    @Operation(summary = "获取我的应用", description = "查询当前用户的所有应用")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证")
    })
    public ResponseEntity<RestResponse<List<ApiApplication>>> getMyApplications() {
        Long ownerId = requireCurrentUserId();
        log.info("查询我的应用列表: ownerId={}", ownerId);

        List<ApiApplication> applications = applicationService.getApplicationsByOwner(ownerId);
        return ResponseEntity.ok(RestResponse.ok("获取成功", applications));
    }

    /**
     * 分頁查詢
     */
    @GetMapping
    @Operation(summary = "分页查询应用", description = "分页查询应用列表，支持状态和关键词筛选")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "查询成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:app:list")
    public ResponseEntity<RestResponse<Page<ApiApplication>>> getApplicationsPage(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") int pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") int pageSize,
            @Parameter(description = "应用状态") @RequestParam(required = false) Integer appStatus,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword) {

        log.info("分页查询应用: pageNum={}, pageSize={}, status={}, keyword={}",
                pageNum, pageSize, appStatus, keyword);

        Page<ApiApplication> page = applicationService.getApplicationsPage(pageNum, pageSize, appStatus, keyword);
        return ResponseEntity.ok(RestResponse.ok("查询成功", page));
    }

    /**
     * 更新IP白名單
     */
    @PutMapping("/{appId}/ip-whitelist")
    @Operation(summary = "更新IP白名单", description = "设置应用的IP访问白名单")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "更新成功"),
            @ApiResponse(responseCode = "400", description = "参数无效"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:app:security")
    public ResponseEntity<RestResponse<Void>> updateIpWhitelist(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId,
            @Parameter(description = "IP白名单JSON数组", required = true) @RequestBody String ipWhitelist) {

        log.info("更新IP白名单: appId={}", appId);
        applicationService.updateIpWhitelist(appId, ipWhitelist);
        return ResponseEntity.ok(RestResponse.ok("IP白名单已更新", null));
    }

    /**
     * 驗證 IP 是否在白名單
     */
    @GetMapping("/{appId}/ip-whitelist/validate")
    @Operation(summary = "验证IP白名单", description = "检查IP是否在应用白名单中")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证完成"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    public ResponseEntity<RestResponse<Boolean>> validateIpWhitelist(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId,
            @Parameter(description = "客户端IP", required = true) @RequestParam String clientIp) {

        log.debug("验证IP白名单: appId={}, clientIp={}", appId, clientIp);
        boolean allowed = applicationService.validateIpWhitelist(appId, clientIp);
        return ResponseEntity.ok(RestResponse.ok("验证完成", allowed));
    }

    /**
     * 获取应用统计信息
     */
    @GetMapping("/{appId}/statistics")
    @Operation(summary = "获取应用统计", description = "查询应用的API调用统计信息")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> getApplicationStatistics(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId,
            @Parameter(description = "统计天数") @RequestParam(defaultValue = "7") int days) {

        log.info("查询应用统计: appId={}, days={}", appId, days);
        Map<String, Object> stats = applicationService.getApplicationStatistics(appId, days);
        return ResponseEntity.ok(RestResponse.ok("获取成功", stats));
    }

    /**
     * 获取待审核应用列表
     */
    @GetMapping("/pending")
    @Operation(summary = "获取待审核应用", description = "查询所有待审核的应用")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "获取成功"),
            @ApiResponse(responseCode = "401", description = "未认证"),
            @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:app:approve")
    public ResponseEntity<RestResponse<List<ApiApplication>>> getPendingApplications() {
        log.info("查询待审核应用列表");
        List<ApiApplication> applications = applicationService.getPendingApplications();
        return ResponseEntity.ok(RestResponse.ok("获取成功", applications));
    }

    /**
     * 验证回调 URL
     */
    @PostMapping("/callback-url/validate")
    @Operation(summary = "验证回调URL", description = "验证回调URL格式是否正确")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "验证完成"),
            @ApiResponse(responseCode = "400", description = "URL格式错误")
    })
    public ResponseEntity<RestResponse<Boolean>> validateCallbackUrls(
            @Parameter(description = "回调URL列表", required = true) @RequestParam String callbackUrls) {

        log.debug("验证回调URL: urls={}", callbackUrls);
        boolean valid = applicationService.validateCallbackUrls(callbackUrls);
        return ResponseEntity.ok(RestResponse.ok("验证完成", valid));
    }
}
