package cn.flying.dao.mapper;

import cn.flying.dao.entity.Ticket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 工单 Mapper 接口
 */
@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {

    @Select("""
        SELECT COUNT(*) FROM ticket
        WHERE creator_id = #{userId}
          AND status IN (0, 1, 2)
          AND deleted = 0
        """)
    int countUserPendingTickets(@Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ticket
        WHERE (
            (creator_id = #{userId} AND (creator_last_view_time IS NULL OR update_time > creator_last_view_time))
            OR
            (assignee_id = #{userId} AND (assignee_last_view_time IS NULL OR update_time > assignee_last_view_time))
        )
        AND deleted = 0
        """)
    int countUserUnreadTickets(@Param("userId") Long userId);

    @Select("""
        SELECT COUNT(*) FROM ticket
        WHERE (assignee_id = #{adminId} OR assignee_id IS NULL)
          AND status IN (0, 1)
          AND deleted = 0
        """)
    int countAdminPendingTickets(@Param("adminId") Long adminId);

    @Select("""
        SELECT MAX(CAST(SUBSTRING(ticket_no, 11) AS UNSIGNED))
        FROM ticket
        WHERE ticket_no LIKE CONCAT('TK', #{datePrefix}, '%')
        """)
    Integer getMaxDailySequence(@Param("datePrefix") String datePrefix);
}
