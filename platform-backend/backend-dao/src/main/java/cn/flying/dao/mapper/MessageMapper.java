package cn.flying.dao.mapper;

import cn.flying.dao.entity.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 私信消息 Mapper 接口
 * 带租户隔离：确保私信功能在租户内部使用
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 统计用户未读消息总数（带租户隔离）
     */
    @Select("""
        SELECT COUNT(*) FROM message
        WHERE receiver_id = #{userId}
          AND is_read = 0
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    int countUnreadMessages(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 统计会话内用户未读消息数（带租户隔离）
     */
    @Select("""
        SELECT COUNT(*) FROM message
        WHERE conversation_id = #{conversationId}
          AND receiver_id = #{userId}
          AND is_read = 0
          AND deleted = 0
          AND tenant_id = #{tenantId}
        """)
    int countUnreadInConversation(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("tenantId") Long tenantId
    );

    /**
     * 批量标记会话消息为已读（带租户隔离）
     */
    @Update("""
        UPDATE message
        SET is_read = 1, read_time = #{readTime}
        WHERE conversation_id = #{conversationId}
          AND receiver_id = #{userId}
          AND is_read = 0
          AND tenant_id = #{tenantId}
        """)
    int markConversationAsRead(
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("readTime") Date readTime,
            @Param("tenantId") Long tenantId
    );
}
