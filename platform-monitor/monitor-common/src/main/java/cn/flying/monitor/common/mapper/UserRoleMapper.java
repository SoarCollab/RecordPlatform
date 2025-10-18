package cn.flying.monitor.common.mapper;

import cn.flying.monitor.common.entity.UserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * UserRole mapper interface
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRole> {

    /**
     * Find user role by user ID and role ID
     */
    @Select("SELECT * FROM user_roles WHERE user_id = #{userId} AND role_id = #{roleId} AND deleted = false")
    UserRole findByUserIdAndRoleId(@Param("userId") Long userId, @Param("roleId") Long roleId);
}