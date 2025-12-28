package cn.flying.dao.mapper;

import cn.flying.dao.dto.FileShare;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 文件分享 Mapper
 *
 * @author flyingcoding
 * @since 2025-12-23
 */
@Mapper
public interface FileShareMapper extends BaseMapper<FileShare> {

    /**
     * 根据分享码查询分享记录
     *
     * @param shareCode 分享码
     * @return 分享记录
     */
    @Select("SELECT * FROM file_share WHERE share_code = #{shareCode} AND deleted = 0")
    FileShare selectByShareCode(@Param("shareCode") String shareCode);

    /**
     * 增加访问次数
     *
     * @param shareCode 分享码
     * @return 影响行数
     */
    @Update("UPDATE file_share SET access_count = access_count + 1 WHERE share_code = #{shareCode} AND deleted = 0")
    int incrementAccessCount(@Param("shareCode") String shareCode);

    /**
     * 原子操作：仅当分享处于活跃状态时增加访问计数
     * 解决 TOCTOU 竞态条件
     *
     * @param shareCode 分享码
     * @return 影响行数（0表示分享已取消/过期，无法增加计数）
     */
    @Update("UPDATE file_share SET access_count = access_count + 1 WHERE share_code = #{shareCode} AND status = 1 AND deleted = 0")
    int incrementAccessCountIfActive(@Param("shareCode") String shareCode);

    /**
     * 原子操作：将已过期的分享标记为过期状态
     *
     * @param shareCode 分享码
     * @return 影响行数（0表示未过期或已处理）
     */
    @Update("UPDATE file_share SET status = 2 WHERE share_code = #{shareCode} AND status = 1 AND expire_time < NOW() AND deleted = 0")
    int markAsExpiredIfNecessary(@Param("shareCode") String shareCode);

    /**
     * 批量更新过期分享的状态
     *
     * @param tenantId 租户ID
     * @return 影响行数
     */
    @Update("UPDATE file_share SET status = 2 WHERE tenant_id = #{tenantId} AND status = 1 AND expire_time < NOW() AND deleted = 0")
    int updateExpiredShares(@Param("tenantId") Long tenantId);

    /**
     * 查询用户的分享列表（按创建时间降序）
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @return 分享列表
     */
    @Select("SELECT * FROM file_share WHERE tenant_id = #{tenantId} AND user_id = #{userId} AND deleted = 0 ORDER BY create_time DESC")
    List<FileShare> selectByUserId(@Param("tenantId") Long tenantId, @Param("userId") Long userId);
}
