package cn.flying.dao.mapper;

import cn.flying.dao.entity.SysRolePermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色权限映射 Mapper 接口
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    /**
     * 根据角色获取权限ID列表
     */
    @Select("""
        SELECT permission_id FROM sys_role_permission
        WHERE role = #{role}
          AND (tenant_id = 0 OR tenant_id = #{tenantId})
        """)
    List<Long> selectPermissionIdsByRole(@Param("role") String role, @Param("tenantId") Long tenantId);

    /**
     * 删除角色的所有权限
     */
    @Delete("""
        DELETE FROM sys_role_permission
        WHERE role = #{role}
          AND tenant_id = #{tenantId}
        """)
    int deleteByRole(@Param("role") String role, @Param("tenantId") Long tenantId);

    /**
     * 检查角色是否拥有指定权限
     */
    @Select("""
        SELECT COUNT(*) FROM sys_role_permission rp
        INNER JOIN sys_permission p ON rp.permission_id = p.id
        WHERE rp.role = #{role}
          AND p.code = #{permissionCode}
          AND p.status = 1
          AND (rp.tenant_id = 0 OR rp.tenant_id = #{tenantId})
          AND (p.tenant_id = 0 OR p.tenant_id = #{tenantId})
        """)
    int countByRoleAndPermission(@Param("role") String role,
                                  @Param("permissionCode") String permissionCode,
                                  @Param("tenantId") Long tenantId);
}
