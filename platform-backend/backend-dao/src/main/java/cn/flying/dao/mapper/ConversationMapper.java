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

    @Select("""
        SELECT * FROM conversation
        WHERE participant_a = #{participantA}
          AND participant_b = #{participantB}
        """)
    Conversation selectByParticipants(
            @Param("participantA") Long participantA,
            @Param("participantB") Long participantB
    );

    @Select("""
        SELECT COUNT(DISTINCT c.id) FROM conversation c
        INNER JOIN message m ON c.id = m.conversation_id
        WHERE (c.participant_a = #{userId} OR c.participant_b = #{userId})
          AND m.receiver_id = #{userId}
          AND m.is_read = 0
          AND m.deleted = 0
        """)
    int countUnreadConversations(@Param("userId") Long userId);
}
