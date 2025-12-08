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
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 统计用户总未读消息数
     */
    @Select("""
        SELECT COUNT(*) FROM message
        WHERE tenant_id = #{tenantId}
          AND receiver_id = #{userId}
          AND is_read = 0
          AND deleted = 0
        """)
    int countUnreadMessages(@Param("tenantId") Long tenantId,
                            @Param("userId") Long userId);

    /**
     * 统计会话中的未读消息数
     */
    @Select("""
        SELECT COUNT(*) FROM message
        WHERE tenant_id = #{tenantId}
          AND conversation_id = #{conversationId}
          AND receiver_id = #{userId}
          AND is_read = 0
          AND deleted = 0
        """)
    int countUnreadInConversation(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId
    );

    /**
     * 标记会话中的消息为已读
     */
    @Update("""
        UPDATE message
        SET is_read = 1, read_time = #{readTime}
        WHERE tenant_id = #{tenantId}
          AND conversation_id = #{conversationId}
          AND receiver_id = #{userId}
          AND is_read = 0
        """)
    int markConversationAsRead(
            @Param("tenantId") Long tenantId,
            @Param("conversationId") Long conversationId,
            @Param("userId") Long userId,
            @Param("readTime") Date readTime
    );
}
