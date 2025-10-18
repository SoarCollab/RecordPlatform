package cn.flying.monitor.common.mapper;

import cn.flying.monitor.common.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * User mapper interface
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * Find user by username or email
     */
    @Select("SELECT * FROM users WHERE (username = #{usernameOrEmail} OR email = #{usernameOrEmail}) AND deleted = false")
    User findByUsernameOrEmail(@Param("usernameOrEmail") String usernameOrEmail);

    /**
     * Find users by role name
     */
    @Select("SELECT u.* FROM users u " +
            "JOIN user_roles ur ON u.id = ur.user_id " +
            "JOIN roles r ON ur.role_id = r.id " +
            "WHERE r.code = #{roleName} AND u.deleted = false AND ur.deleted = false")
    List<User> findByRoleName(@Param("roleName") String roleName);

    /**
     * Find users by status
     */
    @Select("SELECT * FROM users WHERE status = #{status} AND deleted = false")
    List<User> findByStatus(@Param("status") User.UserStatus status);
}