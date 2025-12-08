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

    /**
     * 统计用户待处理工单数
     */
    @Select("""
        SELECT COUNT(*) FROM ticket
        WHERE creator_id = #{userId}
          AND status IN (0, 1, 2)
          AND deleted = 0
        """)
    int countUserPendingTickets(@Param("userId") Long userId);

    /**
     * 统计管理员待处理工单数
     */
    @Select("""
        SELECT COUNT(*) FROM ticket
        WHERE (assignee_id = #{adminId} OR assignee_id IS NULL)
          AND status IN (0, 1)
          AND deleted = 0
        """)
    int countAdminPendingTickets(@Param("adminId") Long adminId);

    /**
     * 获取今日最大工单序列号
     */
    @Select("""
        SELECT MAX(CAST(SUBSTRING(ticket_no, 11) AS UNSIGNED))
        FROM ticket
        WHERE ticket_no LIKE CONCAT('TK', #{datePrefix}, '%')
        """)
    Integer getMaxDailySequence(@Param("datePrefix") String datePrefix);
}
