package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.SysPermission;
import cn.flying.dao.entity.SysRolePermission;
import cn.flying.dao.mapper.SysPermissionMapper;
import cn.flying.dao.mapper.SysRolePermissionMapper;
import cn.flying.service.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 角色权限映射 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/system/roles")
@Tag(name = "角色权限管理（REST）", description = "角色权限映射 REST 新路径")
@PreAuthorize("hasPerm('system:admin')")
public class RolePermissionController {

    @Resource
    private SysPermissionMapper permissionMapper;

    @Resource
    private SysRolePermissionMapper rolePermissionMapper;

    @Resource
    private PermissionService permissionService;

    /**
     * 为角色授予权限（REST 新路径）。
     *
     * @param role 角色名
     * @param vo   授权参数
     * @return 操作结果
     */
    @PostMapping("/{role}/permissions")
    @Operation(summary = "为角色授予权限（REST）")
    @OperationLog(module = "权限管理", operationType = "授权", description = "为角色授予权限（REST）")
    public Result<String> grantRolePermission(
            @Parameter(description = "角色名") @PathVariable String role,
            @Valid @RequestBody GrantPermissionVO vo) {
        Long tenantId = SecurityUtils.getTenantId();

        SysPermission permission = permissionMapper.selectByCode(vo.getPermissionCode(), tenantId);
        if (permission == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "权限码不存在: " + vo.getPermissionCode());
        }

        int count = rolePermissionMapper.countByRoleAndPermission(role, vo.getPermissionCode(), tenantId);
        if (count > 0) {
            throw new GeneralException(ResultEnum.DATA_ALREADY_EXISTED, "该角色已拥有此权限");
        }

        SysRolePermission mapping = new SysRolePermission()
                .setRole(role)
                .setPermissionId(permission.getId());
        rolePermissionMapper.insert(mapping);
        permissionService.evictCache(role, tenantId);
        return Result.success("授权成功");
    }

    /**
     * 撤销角色权限（REST 新路径）。
     *
     * @param role           角色名
     * @param permissionCode 权限码
     * @return 操作结果
     */
    @DeleteMapping("/{role}/permissions/{permissionCode}")
    @Operation(summary = "撤销角色权限（REST）")
    @OperationLog(module = "权限管理", operationType = "撤销", description = "撤销角色权限（REST）")
    public Result<String> revokeRolePermission(
            @Parameter(description = "角色名") @PathVariable String role,
            @Parameter(description = "权限码") @PathVariable String permissionCode) {
        Long tenantId = SecurityUtils.getTenantId();

        SysPermission permission = permissionMapper.selectByCode(permissionCode, tenantId);
        if (permission == null) {
            throw new GeneralException(ResultEnum.RESULT_DATA_NONE, "权限码不存在: " + permissionCode);
        }

        LambdaQueryWrapper<SysRolePermission> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysRolePermission::getRole, role)
                .eq(SysRolePermission::getPermissionId, permission.getId())
                .eq(SysRolePermission::getTenantId, tenantId);
        rolePermissionMapper.delete(wrapper);
        permissionService.evictCache(role, tenantId);
        return Result.success("撤销成功");
    }

    /**
     * 角色授权请求体。
     */
    @Data
    public static class GrantPermissionVO {
        @NotBlank(message = "权限码不能为空")
        private String permissionCode;
    }
}
