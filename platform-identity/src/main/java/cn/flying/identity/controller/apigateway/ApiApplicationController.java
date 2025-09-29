package cn.flying.identity.controller.apigateway;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.flying.identity.dto.apigateway.ApiApplication;
import cn.flying.identity.service.apigateway.ApiApplicationService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
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
 * API应用管理控制器
 * 提供符合RESTful规范的应用注册、审核、管理等接口
 *
 * @author 王贝强
 * @since 2025-10-11
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
     * 注册新应用
     * POST /api/gateway/applications - 创建新应用
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
        
        Result<Map<String, Object>> result = applicationService.registerApplication(
            appName, appDescription, ownerId, appType, appWebsite, callbackUrl);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 审核应用
     * POST /api/gateway/applications/{appId}/approval - 审核应用申请
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
        
        Result<Void> result = applicationService.approveApplication(appId, approved, approveBy, rejectReason);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 启用应用
     * PUT /api/gateway/applications/{appId}/status - 更新应用状态
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
        
        Result<Void> result = enabled 
            ? applicationService.enableApplication(appId)
            : applicationService.disableApplication(appId, reason);
        
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 删除应用
     * DELETE /api/gateway/applications/{appId} - 删除应用
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
        Result<Void> result = applicationService.deleteApplication(appId);
        
        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 更新应用信息
     * PUT /api/gateway/applications/{appId} - 更新应用
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
        
        Result<Void> result = applicationService.updateApplication(application);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取应用详情
     * GET /api/gateway/applications/{appId} - 获取应用详情
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
        Result<ApiApplication> result = applicationService.getApplicationById(appId);
        RestResponse<ApiApplication> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 根据应用标识码获取应用
     * GET /api/gateway/applications/by-code/{appCode} - 根据标识码获取应用
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
        Result<ApiApplication> result = applicationService.getApplicationByCode(appCode);
        RestResponse<ApiApplication> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取我的应用列表
     * GET /api/gateway/applications/my - 获取当前用户的应用
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
        
        Result<List<ApiApplication>> result = applicationService.getApplicationsByOwner(ownerId);
        RestResponse<List<ApiApplication>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 分页查询应用列表
     * GET /api/gateway/applications - 分页查询应用
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
        
        Result<Page<ApiApplication>> result = applicationService.getApplicationsPage(
            pageNum, pageSize, appStatus, keyword);
        RestResponse<Page<ApiApplication>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 更新应用IP白名单
     * PUT /api/gateway/applications/{appId}/ip-whitelist - 更新IP白名单
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
        Result<Void> result = applicationService.updateIpWhitelist(appId, ipWhitelist);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 验证IP是否在白名单中
     * GET /api/gateway/applications/{appId}/ip-whitelist/validate - 验证IP白名单
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
        Result<Boolean> result = applicationService.validateIpWhitelist(appId, clientIp);
        RestResponse<Boolean> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取应用统计信息
     * GET /api/gateway/applications/{appId}/statistics - 获取应用统计
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
        Result<Map<String, Object>> result = applicationService.getApplicationStatistics(appId, days);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取待审核应用列表
     * GET /api/gateway/applications/pending - 获取待审核应用
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
        
        Result<List<ApiApplication>> result = applicationService.getPendingApplications();
        RestResponse<List<ApiApplication>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 验证回调URL格式
     * POST /api/gateway/applications/callback-url/validate - 验证回调URL
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
        Result<Boolean> result = applicationService.validateCallbackUrls(callbackUrls);
        RestResponse<Boolean> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}