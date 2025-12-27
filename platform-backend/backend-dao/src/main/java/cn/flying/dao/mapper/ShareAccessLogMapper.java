package cn.flying.dao.mapper;

import cn.flying.dao.dto.ShareAccessLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 分享访问日志 Mapper
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Mapper
public interface ShareAccessLogMapper extends BaseMapper<ShareAccessLog> {

    /**
     * 查询分享的访问日志
     *
     * @param shareCode 分享码
     * @param tenantId 租户ID
     * @return 访问日志列表
     */
    @Select("SELECT * FROM share_access_log WHERE share_code = #{shareCode} AND tenant_id = #{tenantId} ORDER BY access_time DESC")
    List<ShareAccessLog> selectByShareCode(@Param("shareCode") String shareCode, @Param("tenantId") Long tenantId);

    /**
     * 查询分享所有者的所有访问日志
     *
     * @param shareOwnerId 分享所有者ID
     * @param tenantId 租户ID
     * @return 访问日志列表
     */
    @Select("SELECT * FROM share_access_log WHERE share_owner_id = #{shareOwnerId} AND tenant_id = #{tenantId} ORDER BY access_time DESC")
    List<ShareAccessLog> selectByShareOwnerId(@Param("shareOwnerId") Long shareOwnerId, @Param("tenantId") Long tenantId);

    /**
     * 统计分享的各类型访问次数
     *
     * @param shareCode 分享码
     * @param actionType 操作类型
     * @param tenantId 租户ID
     * @return 访问次数
     */
    @Select("SELECT COUNT(*) FROM share_access_log WHERE share_code = #{shareCode} AND action_type = #{actionType} AND tenant_id = #{tenantId}")
    Long countByShareCodeAndAction(@Param("shareCode") String shareCode, @Param("actionType") Integer actionType, @Param("tenantId") Long tenantId);

    /**
     * 统计分享的独立访问用户数
     *
     * @param shareCode 分享码
     * @param tenantId 租户ID
     * @return 独立用户数
     */
    @Select("SELECT COUNT(DISTINCT actor_user_id) FROM share_access_log WHERE share_code = #{shareCode} AND actor_user_id IS NOT NULL AND tenant_id = #{tenantId}")
    Long countDistinctActors(@Param("shareCode") String shareCode, @Param("tenantId") Long tenantId);

    /**
     * 查询用户的操作日志（作为操作者）
     *
     * @param actorUserId 操作者用户ID
     * @param tenantId 租户ID
     * @return 操作日志列表
     */
    @Select("SELECT * FROM share_access_log WHERE actor_user_id = #{actorUserId} AND tenant_id = #{tenantId} ORDER BY access_time DESC")
    List<ShareAccessLog> selectByActorUserId(@Param("actorUserId") Long actorUserId, @Param("tenantId") Long tenantId);

    /**
     * 清理过期日志
     *
     * @param beforeDate 日期阈值
     * @param limit 删除数量限制
     * @return 删除行数
     */
    @Delete("DELETE FROM share_access_log WHERE access_time < #{beforeDate} LIMIT #{limit}")
    int deleteOldLogs(@Param("beforeDate") Date beforeDate, @Param("limit") int limit);

    /**
     * 批量统计多个分享码的访问次数（按操作类型分组）
     *
     * @param shareCodes 分享码列表
     * @param tenantId 租户ID
     * @return 统计结果列表，每行包含 share_code, action_type, cnt
     */
    @Select("""
        <script>
        SELECT share_code, action_type, COUNT(*) as cnt
        FROM share_access_log
        WHERE tenant_id = #{tenantId}
        AND share_code IN
        <foreach item="code" collection="shareCodes" open="(" separator="," close=")">
            #{code}
        </foreach>
        GROUP BY share_code, action_type
        </script>
        """)
    List<Map<String, Object>> batchCountByShareCodes(@Param("shareCodes") List<String> shareCodes, @Param("tenantId") Long tenantId);

    /**
     * 批量统计多个分享码的独立访问用户数
     *
     * @param shareCodes 分享码列表
     * @param tenantId 租户ID
     * @return 统计结果列表，每行包含 share_code, unique_actors
     */
    @Select("""
        <script>
        SELECT share_code, COUNT(DISTINCT actor_user_id) as unique_actors
        FROM share_access_log
        WHERE tenant_id = #{tenantId}
        AND actor_user_id IS NOT NULL
        AND share_code IN
        <foreach item="code" collection="shareCodes" open="(" separator="," close=")">
            #{code}
        </foreach>
        GROUP BY share_code
        </script>
        """)
    List<Map<String, Object>> batchCountDistinctActors(@Param("shareCodes") List<String> shareCodes, @Param("tenantId") Long tenantId);
}
