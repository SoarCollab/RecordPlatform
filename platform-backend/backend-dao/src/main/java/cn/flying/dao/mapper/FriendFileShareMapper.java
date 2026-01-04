package cn.flying.dao.mapper;

import cn.flying.dao.entity.FriendFileShare;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

/**
 * 好友文件分享 Mapper 接口
 */
@Mapper
public interface FriendFileShareMapper extends BaseMapper<FriendFileShare> {

    /**
     * 统计用户收到的未读分享数
     */
    @Select("""
        SELECT COUNT(*) FROM friend_file_share
        WHERE friend_id = #{userId}
          AND is_read = 0
          AND status = 1
          AND tenant_id = #{tenantId}
        """)
    int countUnread(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 标记分享为已读
     */
    @Update("""
        UPDATE friend_file_share
        SET is_read = 1, read_time = #{readTime}
        WHERE id = #{shareId}
          AND friend_id = #{userId}
          AND is_read = 0
          AND tenant_id = #{tenantId}
        """)
    int markAsRead(
            @Param("shareId") Long shareId,
            @Param("userId") Long userId,
            @Param("readTime") Date readTime,
            @Param("tenantId") Long tenantId
    );

    /**
     * 查询用户通过好友分享可访问指定文件的分享者ID
     * 使用 JSON_CONTAINS 检查 file_hashes 数组中是否包含指定的文件哈希
     */
    @Select("""
        SELECT sharer_id FROM friend_file_share
        WHERE friend_id = #{userId}
          AND status = 1
          AND JSON_CONTAINS(file_hashes, JSON_QUOTE(#{fileHash}))
          AND tenant_id = #{tenantId}
        LIMIT 1
        """)
    Long findSharerIdForFile(
            @Param("userId") Long userId,
            @Param("fileHash") String fileHash,
            @Param("tenantId") Long tenantId
    );
}
