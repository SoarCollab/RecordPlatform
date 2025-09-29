package cn.flying.identity.controller.apigateway;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.service.apigateway.ApiKeyService;
import cn.flying.identity.util.ResponseConverter;
import cn.flying.identity.vo.RestResponse;
import cn.flying.platformapi.constant.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API密钥管理控制器
 * 提供符合RESTful规范的API密钥的生成、验证、管理等接口
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@RestController
@RequestMapping("/api/gateway/keys")
@Tag(name = "API密钥管理", description = "提供API密钥的生成、验证、管理等功能")
@SaCheckLogin
public class ApiKeyController {

    @Resource
    private ApiKeyService apiKeyService;

    /**
     * 生成新的API密钥
     * POST /api/gateway/keys - 创建新密钥
     */
    @PostMapping
    @Operation(summary = "生成API密钥", description = "为应用生成新的API密钥对，ApiSecret只返回一次")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "密钥生成成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:key:generate")
    public ResponseEntity<RestResponse<Map<String, Object>>> generateApiKey(
            @Parameter(description = "应用ID", required = true) @RequestParam Long appId,
            @Parameter(description = "密钥名称") @RequestParam(required = false) String keyName,
            @Parameter(description = "密钥类型：1-正式，2-测试", required = true) @RequestParam Integer keyType,
            @Parameter(description = "过期天数") @RequestParam(required = false) Integer expireDays) {

        log.info("生成API密钥请求: appId={}, keyName={}, keyType={}", appId, keyName, keyType);
        Result<Map<String, Object>> result = apiKeyService.generateApiKey(appId, keyName, keyType, expireDays);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 验证API密钥
     * POST /api/gateway/keys/validation - 验证密钥
     */
    @PostMapping("/validation")
    @Operation(summary = "验证API密钥", description = "验证API请求的签名和权限")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "验证成功"),
        @ApiResponse(responseCode = "400", description = "参数无效"),
        @ApiResponse(responseCode = "401", description = "密钥验证失败")
    })
    public ResponseEntity<RestResponse<Map<String, Object>>> validateApiKey(
            @Parameter(description = "API密钥", required = true) @RequestParam String apiKey,
            @Parameter(description = "时间戳", required = true) @RequestParam Long timestamp,
            @Parameter(description = "随机字符串", required = true) @RequestParam String nonce,
            @Parameter(description = "签名", required = true) @RequestParam String signature,
            @Parameter(description = "请求数据") @RequestParam(required = false) String requestData) {

        log.debug("验证API密钥: apiKey={}, timestamp={}", apiKey, timestamp);
        Result<Map<String, Object>> result = apiKeyService.validateApiKey(
            apiKey, timestamp, nonce, signature, requestData);
        RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 轮换API密钥
     * POST /api/gateway/keys/{oldKeyId}/rotation - 轮换密钥
     */
    @PostMapping("/{oldKeyId}/rotation")
    @Operation(summary = "轮换API密钥", description = "生成新密钥并禁用旧密钥")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "轮换成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "旧密钥不存在")
    })
    @SaCheckPermission("api:key:rotate")
    public ResponseEntity<RestResponse<Map<String, Object>>> rotateKey(
            @Parameter(description = "旧密钥ID", required = true) @PathVariable Long oldKeyId) {

        log.info("轮换API密钥: oldKeyId={}", oldKeyId);
        Result<Map<String, Object>> result = apiKeyService.rotateKey(oldKeyId);
        
        if (result.isSuccess()) {
            return ResponseEntity.status(HttpStatus.CREATED)
                .body(RestResponse.created(result.getData()));
        } else {
            RestResponse<Map<String, Object>> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).body(response);
        }
    }

    /**
     * 更新密钥状态
     * PUT /api/gateway/keys/{keyId}/status - 更新密钥状态
     */
    @PutMapping("/{keyId}/status")
    @Operation(summary = "更新密钥状态", description = "启用或禁用指定的API密钥")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "操作成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "密钥不存在")
    })
    @SaCheckPermission("api:key:manage")
    public ResponseEntity<RestResponse<Void>> updateKeyStatus(
            @Parameter(description = "密钥ID", required = true) @PathVariable Long keyId,
            @Parameter(description = "是否启用", required = true) @RequestParam boolean enabled) {

        log.info("更新API密钥状态: keyId={}, enabled={}", keyId, enabled);
        Result<Void> result = enabled 
            ? apiKeyService.enableKey(keyId)
            : apiKeyService.disableKey(keyId);
        
        RestResponse<Void> response = ResponseConverter.convert(result);
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 删除密钥
     * DELETE /api/gateway/keys/{keyId} - 删除密钥
     */
    @DeleteMapping("/{keyId}")
    @Operation(summary = "删除密钥", description = "永久删除指定的API密钥")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "删除成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "密钥不存在")
    })
    @SaCheckPermission("api:key:delete")
    public ResponseEntity<Void> deleteKey(
            @Parameter(description = "密钥ID", required = true) @PathVariable Long keyId) {

        log.info("删除API密钥: keyId={}", keyId);
        Result<Void> result = apiKeyService.deleteKey(keyId);
        
        if (result.isSuccess()) {
            return ResponseEntity.noContent().build();
        } else {
            RestResponse<Void> response = ResponseConverter.convert(result);
            return ResponseEntity.status(response.getStatus()).build();
        }
    }

    /**
     * 获取应用的所有密钥
     * GET /api/gateway/keys/application/{appId} - 获取应用密钥列表
     */
    @GetMapping("/application/{appId}")
    @Operation(summary = "获取应用密钥列表", description = "查询指定应用的所有API密钥")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "应用不存在")
    })
    @SaCheckPermission("api:key:list")
    public ResponseEntity<RestResponse<List<ApiKey>>> getKeysByApp(
            @Parameter(description = "应用ID", required = true) @PathVariable Long appId) {

        log.info("查询应用密钥列表: appId={}", appId);
        Result<List<ApiKey>> result = apiKeyService.getKeysByAppId(appId);
        RestResponse<List<ApiKey>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取密钥详情
     * GET /api/gateway/keys/{keyId} - 获取密钥详情
     */
    @GetMapping("/{keyId}")
    @Operation(summary = "获取密钥详情", description = "查询指定密钥的详细信息")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限"),
        @ApiResponse(responseCode = "404", description = "密钥不存在")
    })
    @SaCheckPermission("api:key:view")
    public ResponseEntity<RestResponse<ApiKey>> getKeyById(
            @Parameter(description = "密钥ID", required = true) @PathVariable Long keyId) {

        log.info("查询密钥详情: keyId={}", keyId);
        Result<ApiKey> result = apiKeyService.getKeyById(keyId);
        RestResponse<ApiKey> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 获取即将过期的密钥
     * GET /api/gateway/keys/expiring - 获取即将过期的密钥
     */
    @GetMapping("/expiring")
    @Operation(summary = "获取即将过期密钥", description = "查询指定天数内即将过期的密钥")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "获取成功"),
        @ApiResponse(responseCode = "401", description = "未认证"),
        @ApiResponse(responseCode = "403", description = "无权限")
    })
    @SaCheckPermission("api:key:list")
    public ResponseEntity<RestResponse<List<ApiKey>>> getExpiringKeys(
            @Parameter(description = "天数阈值") @RequestParam(defaultValue = "7") int days) {

        log.info("查询即将过期密钥: days={}", days);
        Result<List<ApiKey>> result = apiKeyService.getExpiringKeys(days);
        RestResponse<List<ApiKey>> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }

    /**
     * 更新密钥最后使用时间
     * PUT /api/gateway/keys/{keyId}/last-used-time - 更新使用时间
     */
    @PutMapping("/{keyId}/last-used-time")
    @Operation(summary = "更新最后使用时间", description = "更新密钥的最后使用时间")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "更新成功"),
        @ApiResponse(responseCode = "404", description = "密钥不存在")
    })
    public ResponseEntity<RestResponse<Void>> updateLastUsedTime(
            @Parameter(description = "密钥ID", required = true) @PathVariable Long keyId) {

        log.debug("更新密钥使用时间: keyId={}", keyId);
        Result<Void> result = apiKeyService.updateLastUsedTime(keyId);
        RestResponse<Void> response = ResponseConverter.convert(result);
        
        return ResponseEntity.status(response.getStatus()).body(response);
    }
}