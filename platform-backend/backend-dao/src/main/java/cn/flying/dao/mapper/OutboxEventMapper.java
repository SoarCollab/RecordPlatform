package cn.flying.dao.mapper;

import cn.flying.dao.entity.OutboxEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface OutboxEventMapper extends BaseMapper<OutboxEvent> {

    @Select("SELECT * FROM outbox_event " +
            "WHERE status = 'PENDING' AND next_attempt_at <= #{now} " +
            "ORDER BY create_time LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<OutboxEvent> fetchPendingEvents(@Param("now") Date now, @Param("limit") int limit);

    @Update("UPDATE outbox_event SET status = 'SENT', sent_time = NOW() WHERE id = #{id}")
    int markSent(@Param("id") String id);

    @Update("UPDATE outbox_event SET status = 'FAILED', retry_count = retry_count + 1, " +
            "next_attempt_at = #{nextAttempt} WHERE id = #{id}")
    int markFailed(@Param("id") String id, @Param("nextAttempt") Date nextAttempt);

    /**
     * 统计指定状态的事件数量
     */
    @Select("SELECT COUNT(*) FROM outbox_event WHERE status = #{status}")
    long countByStatus(@Param("status") String status);

    /**
     * 统计失败且超过最大重试次数的事件数量
     */
    @Select("SELECT COUNT(*) FROM outbox_event WHERE status = 'FAILED' AND retry_count >= #{maxRetries}")
    long countExhaustedRetries(@Param("maxRetries") int maxRetries);
}
