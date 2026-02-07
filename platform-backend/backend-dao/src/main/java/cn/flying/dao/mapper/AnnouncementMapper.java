package cn.flying.dao.mapper;

import cn.flying.dao.entity.Announcement;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * 公告 Mapper 接口
 */
@Mapper
public interface AnnouncementMapper extends BaseMapper<Announcement> {

    /**
     * 查询需要自动发布的定时公告
     */
    @Select("""
        SELECT id, tenant_id, title, content, priority, is_pinned, publish_time, expire_time, status, publisher_id, create_time, update_time, deleted
        FROM announcement
        WHERE status = 0
          AND publish_time IS NOT NULL
          AND publish_time <= #{now}
          AND deleted = 0
        """)
    List<Announcement> selectScheduledAnnouncements(@Param("now") Date now);

    /**
     * 查询已过期的公告
     */
    @Select("""
        SELECT id, tenant_id, title, content, priority, is_pinned, publish_time, expire_time, status, publisher_id, create_time, update_time, deleted
        FROM announcement
        WHERE status = 1
          AND expire_time IS NOT NULL
          AND expire_time <= #{now}
          AND deleted = 0
        """)
    List<Announcement> selectExpiredAnnouncements(@Param("now") Date now);
}
