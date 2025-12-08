package cn.flying.dao.mapper;

import cn.flying.dao.entity.Conversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 私信会话 Mapper 接口
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 根据两个参与者查找会话
     */
    @Select("""
        SELECT * FROM conversation
        WHERE tenant_id = #{tenantId}
          AND participant_a = #{participantA}
          AND participant_b = #{participantB}
        """)
    Conversation selectByParticipants(
            @Param("tenantId") Long tenantId,
            @Param("participantA") Long participantA,
            @Param("participantB") Long participantB
    );

    /**
     * 统计用户有未读消息的会话数
     */
    @Select("""
        SELECT COUNT(DISTINCT c.id) FROM conversation c
        INNER JOIN message m ON c.id = m.conversation_id
        WHERE c.tenant_id = #{tenantId}
          AND m.tenant_id = #{tenantId}
          AND (c.participant_a = #{userId} OR c.participant_b = #{userId})
          AND m.receiver_id = #{userId}
          AND m.is_read = 0
          AND m.deleted = 0
        """)
    int countUnreadConversations(@Param("tenantId") Long tenantId,
                                 @Param("userId") Long userId);
}
