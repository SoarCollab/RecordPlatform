package cn.flying.dao.mapper;

import cn.flying.dao.entity.FriendRequest;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 好友请求 Mapper 接口
 */
@Mapper
public interface FriendRequestMapper extends BaseMapper<FriendRequest> {

    /**
     * 统计用户收到的待处理请求数
     */
    @Select("""
        SELECT COUNT(*) FROM friend_request
        WHERE addressee_id = #{userId}
          AND status = 0
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    int countPendingReceived(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 查询两个用户之间是否有待处理的请求
     */
    @Select("""
        SELECT COUNT(*) FROM friend_request
        WHERE ((requester_id = #{userA} AND addressee_id = #{userB})
           OR (requester_id = #{userB} AND addressee_id = #{userA}))
          AND status = 0
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    int countPendingBetween(
            @Param("userA") Long userA,
            @Param("userB") Long userB,
            @Param("tenantId") Long tenantId
    );
}
