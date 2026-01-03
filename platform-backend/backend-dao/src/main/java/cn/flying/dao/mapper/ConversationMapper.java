package cn.flying.dao.mapper;

import cn.flying.dao.entity.Conversation;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 私信会话 Mapper 接口
 * 带租户隔离：确保私信功能在租户内部使用
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {

    /**
     * 根据参与者查询会话（带租户隔离）
     */
    @Select("""
        SELECT * FROM conversation
        WHERE participant_a = #{participantA}
          AND participant_b = #{participantB}
          AND tenant_id = #{tenantId}
        """)
    Conversation selectByParticipants(
            @Param("participantA") Long participantA,
            @Param("participantB") Long participantB,
            @Param("tenantId") Long tenantId
    );

    /**
     * 统计用户未读会话数（带租户隔离）
     * 注意：手动处理租户条件，禁用自动注入避免 JOIN 歧义
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
        SELECT COUNT(DISTINCT c.id) FROM conversation c
        INNER JOIN message m ON c.id = m.conversation_id
        WHERE (c.participant_a = #{userId} OR c.participant_b = #{userId})
          AND m.receiver_id = #{userId}
          AND m.is_read = 0
          AND m.deleted = 0
          AND c.tenant_id = #{tenantId}
          AND m.tenant_id = #{tenantId}
        """)
    int countUnreadConversations(@Param("userId") Long userId, @Param("tenantId") Long tenantId);
}
