package cn.flying.monitor.common.mapper;

import cn.flying.monitor.common.entity.Role;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Role mapper interface
 */
@Mapper
public interface RoleMapper extends BaseMapper<Role> {

    /**
     * Find role by code
     */
    @Select("SELECT * FROM roles WHERE code = #{code} AND deleted = false")
    Role findByCode(@Param("code") String code);

    /**
     * Find role by name
     */
    @Select("SELECT * FROM roles WHERE name = #{name} AND deleted = false LIMIT 1")
    Role findByName(@Param("name") String name);

    /**
     * Check if role name exists
     */
    @Select("SELECT CASE WHEN COUNT(1) > 0 THEN TRUE ELSE FALSE END FROM roles WHERE name = #{name} AND deleted = false")
    boolean existsByName(@Param("name") String name);

    /**
     * Find roles by user ID
     */
    @Select("SELECT r.* FROM roles r " +
            "JOIN user_roles ur ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND r.deleted = false AND ur.deleted = false")
    List<Role> findByUserId(@Param("userId") Long userId);

    /**
     * Find active roles
     */
    @Select("SELECT * FROM roles WHERE status = 'ACTIVE' AND deleted = false")
    List<Role> findActiveRoles();
}