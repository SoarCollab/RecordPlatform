package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 权限管理控制器
 * 提供权限定义和角色权限映射的管理接口
 */
@RestController
@RequestMapping("/api/v1/system/permissions")
@Tag(name = "权限管理", description = "权限定义和角色权限映射管理")
@PreAuthorize("hasPerm('system:admin')")
public class PermissionController {

    @Resource
    private SysPermissionMapper permissionMapper;

    @Resource
    private SysRolePermissionMapper rolePermissionMapper;

    @Resource
    private PermissionService permissionService;

    // ==================== 权限定义管理 ====================

    @GetMapping("/list")
    @Operation(summary = "获取权限列表（分页）")
    @OperationLog(module = "权限管理", operationType = "查询", description = "获取权限列表")
    public Result<IPage<SysPermission>> listPermissions(
            @Parameter(description = "模块名") @RequestParam(required = false) String module,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {

        Long tenantId = SecurityUtils.getTenantId();
        Page<SysPermission> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(SysPermission::getTenantId, 0L).or().eq(SysPermission::getTenantId, tenantId));
        if (module != null && !module.isEmpty()) {
            wrapper.eq(SysPermission::getModule, module);
        }
        wrapper.orderByAsc(SysPermission::getModule, SysPermission::getCode);

        permissionMapper.selectPage(page, wrapper);
        return Result.success(page);
    }

    @GetMapping("/modules")
    @Operation(summary = "获取所有模块名列表")
    public Result<List<String>> listModules() {
        Long tenantId = SecurityUtils.getTenantId();
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.eq(SysPermission::getTenantId, 0L).or().eq(SysPermission::getTenantId, tenantId));
        wrapper.select(SysPermission::getModule);
        wrapper.groupBy(SysPermission::getModule);

        List<SysPermission> permissions = permissionMapper.selectList(wrapper);
        List<String> modules = permissions.stream()
                .map(SysPermission::getModule)
                .distinct()
                .toList();
        return Result.success(modules);
    }

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

        permissionMapper.insert(permission);
        return Result.success(permission);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新权限定义")
    @OperationLog(module = "权限管理", operationType = "修改", description = "更新权限定义")
    public Result<SysPermission> updatePermission(
            @Parameter(description = "权限ID") @PathVariable String id,
            @Valid @RequestBody PermissionUpdateVO vo) {

        Long permissionId = IdUtils.fromExternalId(id);
        SysPermission permission = permissionMapper.selectById(permissionId);
        if (permission == null) {
            return Result.error("权限不存在");
        }

        if (vo.getName() != null) {
            permission.setName(vo.getName());
        }
        if (vo.getDescription() != null) {
            permission.setDescription(vo.getDescription());
        }
        if (vo.getStatus() != null) {
            permission.setStatus(vo.getStatus());
        }

        permissionMapper.updateById(permission);

        // 清除缓存
        permissionService.evictAllCache(SecurityUtils.getTenantId());

        return Result.success(permission);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除权限定义")
    @OperationLog(module = "权限管理", operationType = "删除", description = "删除权限定义")
    public Result<String> deletePermission(
            @Parameter(description = "权限ID") @PathVariable String id) {

        Long permissionId = IdUtils.fromExternalId(id);

        // 先删除关联的角色权限映射
        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getPermissionId, permissionId);
        rolePermissionMapper.delete(wrapper);

        // 删除权限定义
        permissionMapper.deleteById(permissionId);

        // 清除缓存
        permissionService.evictAllCache(SecurityUtils.getTenantId());

        return Result.success("删除成功");
    }

    // ==================== 角色权限映射管理 ====================

    @GetMapping("/roles/{role}")
    @Operation(summary = "获取角色的权限列表")
    public Result<Set<String>> getRolePermissions(
            @Parameter(description = "角色名: user, admin, monitor") @PathVariable String role) {

        Long tenantId = SecurityUtils.getTenantId();
        Set<String> permissions = permissionService.getPermissionCodes(role, tenantId);
        return Result.success(permissions);
    }

    @PostMapping("/roles/{role}/grant")
    @Operation(summary = "为角色授予权限")
    @OperationLog(module = "权限管理", operationType = "授权", description = "为角色授予权限")
    public Result<String> grantPermission(
            @Parameter(description = "角色名") @PathVariable String role,
            @Valid @RequestBody GrantPermissionVO vo) {

        Long tenantId = SecurityUtils.getTenantId();

        // 查找权限
        SysPermission permission = permissionMapper.selectByCode(vo.getPermissionCode(), tenantId);
        if (permission == null) {
            return Result.error("权限码不存在: " + vo.getPermissionCode());
        }

        // 检查是否已存在
        int count = rolePermissionMapper.countByRoleAndPermission(role, vo.getPermissionCode(), tenantId);
        if (count > 0) {
            return Result.error("该角色已拥有此权限");
        }

        // 创建映射
        SysRolePermission mapping = new SysRolePermission()
                .setRole(role)
                .setPermissionId(permission.getId());

        rolePermissionMapper.insert(mapping);

        // 清除缓存
        permissionService.evictCache(role, tenantId);

        return Result.success("授权成功");
    }

    @DeleteMapping("/roles/{role}/revoke")
    @Operation(summary = "撤销角色的权限")
    @OperationLog(module = "权限管理", operationType = "撤销", description = "撤销角色权限")
    public Result<String> revokePermission(
            @Parameter(description = "角色名") @PathVariable String role,
            @Parameter(description = "权限码") @RequestParam String permissionCode) {

        Long tenantId = SecurityUtils.getTenantId();

        // 查找权限
        SysPermission permission = permissionMapper.selectByCode(permissionCode, tenantId);
        if (permission == null) {
            return Result.error("权限码不存在: " + permissionCode);
        }

        // 删除映射
        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getRole, role)
                .eq(SysRolePermission::getPermissionId, permission.getId())
                .eq(SysRolePermission::getTenantId, tenantId);

        rolePermissionMapper.delete(wrapper);

        // 清除缓存
        permissionService.evictCache(role, tenantId);

        return Result.success("撤销成功");
    }

    // ==================== VO 定义 ====================

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

    @Data
    public static class PermissionUpdateVO {
        private String name;
        private String description;
        private Integer status;
    }

    @Data
    public static class GrantPermissionVO {
        @NotBlank(message = "权限码不能为空")
        private String permissionCode;
    }
}
