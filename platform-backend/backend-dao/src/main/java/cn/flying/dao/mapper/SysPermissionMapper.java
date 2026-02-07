package cn.flying.dao.mapper;

import cn.flying.dao.entity.SysPermission;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

/**
 * 权限定义 Mapper 接口
 * 注意：所有方法均手动处理租户条件 (tenant_id = 0 OR tenant_id = ?) 以支持全局权限
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 根据角色获取权限码列表
     */
    @Select("""
        SELECT DISTINCT p.code FROM sys_permission p
        INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
        WHERE rp.role = #{role}
          AND p.status = 1
          AND (p.tenant_id = 0 OR p.tenant_id = #{tenantId})
          AND (rp.tenant_id = 0 OR rp.tenant_id = #{tenantId})
        """)
    Set<String> selectPermissionCodesByRole(@Param("role") String role, @Param("tenantId") Long tenantId);

    /**
     * 根据多个角色获取权限码列表
     */
    @Select("""
        <script>
        SELECT DISTINCT p.code FROM sys_permission p
        INNER JOIN sys_role_permission rp ON p.id = rp.permission_id
        WHERE rp.role IN
        <foreach collection="roles" item="role" open="(" separator="," close=")">
            #{role}
        </foreach>
          AND p.status = 1
          AND (p.tenant_id = 0 OR p.tenant_id = #{tenantId})
          AND (rp.tenant_id = 0 OR rp.tenant_id = #{tenantId})
        </script>
        """)
    Set<String> selectPermissionCodesByRoles(@Param("roles") List<String> roles, @Param("tenantId") Long tenantId);

    /**
     * 根据模块获取权限列表
     */
    @Select("""
        SELECT id, tenant_id, code, name, module, action, description, status, create_time, update_time
        FROM sys_permission
        WHERE module = #{module}
          AND status = 1
          AND (tenant_id = 0 OR tenant_id = #{tenantId})
        ORDER BY code
        """)
    List<SysPermission> selectByModule(@Param("module") String module, @Param("tenantId") Long tenantId);

    /**
     * 根据权限码获取权限
     */
    @Select("""
        SELECT id, tenant_id, code, name, module, action, description, status, create_time, update_time
        FROM sys_permission
        WHERE code = #{code}
          AND status = 1
          AND (tenant_id = 0 OR tenant_id = #{tenantId})
        LIMIT 1
        """)
    SysPermission selectByCode(@Param("code") String code, @Param("tenantId") Long tenantId);
}
