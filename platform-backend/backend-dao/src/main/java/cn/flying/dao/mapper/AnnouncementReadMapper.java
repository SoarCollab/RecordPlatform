package cn.flying.dao.mapper;

import cn.flying.dao.entity.AnnouncementRead;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Set;

/**
 * 公告已读记录 Mapper 接口
 */
@Mapper
public interface AnnouncementReadMapper extends BaseMapper<AnnouncementRead> {

    /**
     * 获取用户已读公告ID集合
     */
    @Select("""
        SELECT announcement_id FROM announcement_read
        WHERE tenant_id = #{tenantId}
          AND user_id = #{userId}
        """)
    Set<Long> selectReadAnnouncementIds(@Param("tenantId") Long tenantId,
                                        @Param("userId") Long userId);

    /**
     * 统计用户未读公告数量
     */
    @Select("""
        SELECT COUNT(*) FROM announcement a
        WHERE a.tenant_id = #{tenantId}
          AND a.status = 1
          AND a.deleted = 0
          AND NOT EXISTS (
            SELECT 1 FROM announcement_read ar
            WHERE ar.tenant_id = #{tenantId}
              AND ar.announcement_id = a.id
              AND ar.user_id = #{userId}
          )
        """)
    int countUnreadAnnouncements(@Param("tenantId") Long tenantId,
                                 @Param("userId") Long userId);
}
