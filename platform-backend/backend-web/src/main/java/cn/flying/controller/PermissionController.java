package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.service.PermissionService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.Set;

/**
 * 权限管理控制器。
 */
@RestController
@RequestMapping("/api/v1/system/permissions")
@Tag(name = "权限管理", description = "权限定义和角色权限映射管理")
@PreAuthorize("hasPerm('system:admin')")


@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;

    /**
     * 获取权限树。
     *
     * @return 权限列表
     */
    @OperationLog(module = "权限管理", operationType = "查询", description = "getPermissionTree")
    @GetMapping
    @Operation(summary = "获取权限树")
    public Result<List<SysPermission>> getPermissionTree() {
        Long tenantId = SecurityUtils.getTenantId();
        return Result.success(permissionService.getPermissionTree(tenantId));
    }

    /**
     * 获取权限列表（分页）。
     *
     * @param module   模块名（可选）
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 权限分页
     */
    @GetMapping("/list")
    @Operation(summary = "获取权限列表（分页）")
    @OperationLog(module = "权限管理", operationType = "查询", description = "获取权限列表")
    public Result<IPage<SysPermission>> listPermissions(
            @Parameter(description = "模块名") @RequestParam(required = false) String module,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Long tenantId = SecurityUtils.getTenantId();
        Page<SysPermission> page = new Page<>(pageNum, pageSize);
        IPage<SysPermission> result = permissionService.listPermissions(tenantId, module, page);
        return Result.success(result);
    }

    /**
     * 获取所有权限模块。
     *
     * @return 模块名称列表
     */
    @OperationLog(module = "权限管理", operationType = "查询", description = "listModules")
    @GetMapping("/modules")
    @Operation(summary = "获取所有模块名列表")
    public Result<List<String>> listModules() {
        Long tenantId = SecurityUtils.getTenantId();
        return Result.success(permissionService.listModules(tenantId));
    }

    /**
     * 创建权限定义。
     *
     * @param vo 权限创建参数
     * @return 权限实体
     */
    @PostMapping
    @Operation(summary = "创建权限定义")
    @OperationLog(module = "权限管理", operationType = "新增", description = "创建权限定义")
    public Result<SysPermission> createPermission(@Valid @RequestBody PermissionCreateVO vo) {
        SysPermission permission = new SysPermission()
                .setCode(vo.getCode())
                .setName(vo.getName())
                .setModule(vo.getModule())
                .setAction(vo.getAction())
                .setDescription(vo.getDescription())
                .setStatus(1);

        permissionService.createPermission(permission);
        return Result.success(permission);
    }

    /**
     * 更新权限定义。
     *
     * @param id 权限外部 ID
     * @param vo 更新参数
     * @return 权限实体
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新权限定义")
    @OperationLog(module = "权限管理", operationType = "修改", description = "更新权限定义")
    public Result<SysPermission> updatePermission(
            @Parameter(description = "权限ID") @PathVariable String id,
            @Valid @RequestBody PermissionUpdateVO vo) {

        Long tenantId = SecurityUtils.getTenantId();
        SysPermission permission = permissionService.updatePermission(id, vo.getName(), vo.getDescription(), vo.getStatus(), tenantId);
        if (permission == null) {
            return Result.error(ResultEnum.RESULT_DATA_NONE, null);
        }
        return Result.success(permission);
    }

    /**
     * 删除权限定义。
     *
     * @param id 权限外部 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除权限定义")
    @OperationLog(module = "权限管理", operationType = "删除", description = "删除权限定义")
    public Result<String> deletePermission(
            @Parameter(description = "权限ID") @PathVariable String id) {

        Long tenantId = SecurityUtils.getTenantId();
        permissionService.deletePermission(id, tenantId);
        return Result.success("删除成功");
    }

    /**
     * 获取角色权限码列表。
     *
     * @param role 角色名
     * @return 权限码集合
     */
    @OperationLog(module = "权限管理", operationType = "查询", description = "getRolePermissions")
    @GetMapping("/roles/{role}")
    @Operation(summary = "获取角色的权限列表")
    public Result<Set<String>> getRolePermissions(
            @Parameter(description = "角色名: user, admin, monitor") @PathVariable String role) {

        Long tenantId = SecurityUtils.getTenantId();
        Set<String> permissions = permissionService.getPermissionCodes(role, tenantId);
        return Result.success(permissions);
    }

    /**
     * 权限创建请求体。
     */
    @Data
    public static class PermissionCreateVO {
        @NotBlank(message = "权限码不能为空")
        private String code;

        @NotBlank(message = "权限名称不能为空")
        private String name;

        @NotBlank(message = "模块名不能为空")
        private String module;

        @NotBlank(message = "操作类型不能为空")
        private String action;

        private String description;
    }

    /**
     * 权限更新请求体。
     */
    @Data
    public static class PermissionUpdateVO {
        private String name;
        private String description;
        private Integer status;
    }
}
