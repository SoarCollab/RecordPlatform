package cn.flying.dao.mapper;

import cn.flying.dao.entity.Friendship;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 好友关系 Mapper 接口
 */
@Mapper
public interface FriendshipMapper extends BaseMapper<Friendship> {

    /**
     * 判断两个用户是否是好友
     */
    @Select("""
        SELECT COUNT(*) FROM friendship
        WHERE user_a = #{userA}
          AND user_b = #{userB}
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    int areFriends(
            @Param("userA") Long userA,
            @Param("userB") Long userB,
            @Param("tenantId") Long tenantId
    );

    /**
     * 根据两个用户查询好友关系
     */
    @Select("""
        SELECT * FROM friendship
        WHERE user_a = #{userA}
          AND user_b = #{userB}
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    Friendship findByUsers(
            @Param("userA") Long userA,
            @Param("userB") Long userB,
            @Param("tenantId") Long tenantId
    );

    /**
     * 统计用户的好友数量
     */
    @Select("""
        SELECT COUNT(*) FROM friendship
        WHERE (user_a = #{userId} OR user_b = #{userId})
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    int countFriends(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
