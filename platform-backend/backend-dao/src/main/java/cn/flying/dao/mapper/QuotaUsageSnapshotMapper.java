package cn.flying.dao.mapper;

import cn.flying.dao.entity.QuotaUsageSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 配额快照 Mapper。
 */
@Mapper
public interface QuotaUsageSnapshotMapper extends BaseMapper<QuotaUsageSnapshot> {

    /**
     * 按租户和用户查询最新快照。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID（0 表示租户聚合）
     * @return 快照记录
     */
    @Select("SELECT id, tenant_id, user_id, used_storage_bytes, used_file_count, source, snapshot_time " +
            "FROM quota_usage_snapshot WHERE tenant_id = #{tenantId} AND user_id = #{userId} LIMIT 1")
    QuotaUsageSnapshot selectByScope(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /**
     * 写入或更新快照记录。
     *
     * @param tenantId 租户ID
     * @param userId 用户ID（0 表示租户聚合）
     * @param usedStorageBytes 已使用存储字节数
     * @param usedFileCount 已使用文件数
     * @param source 快照来源（REALTIME/RECON）
     * @return 影响行数
     */
    @Insert("INSERT INTO quota_usage_snapshot(tenant_id, user_id, used_storage_bytes, used_file_count, source, snapshot_time) " +
            "VALUES(#{tenantId}, #{userId}, #{usedStorageBytes}, #{usedFileCount}, #{source}, NOW()) " +
            "ON DUPLICATE KEY UPDATE used_storage_bytes = VALUES(used_storage_bytes), " +
            "used_file_count = VALUES(used_file_count), source = VALUES(source), snapshot_time = NOW()")
    int upsertSnapshot(@Param("tenantId") Long tenantId,
                       @Param("userId") Long userId,
                       @Param("usedStorageBytes") Long usedStorageBytes,
                       @Param("usedFileCount") Long usedFileCount,
                       @Param("source") String source);

    /**
     * 将指定租户下“不在本轮聚合结果中的用户快照”置零，避免历史占用值残留。
     *
     * @param tenantId 租户ID
     * @param activeUserIds 本轮聚合命中的用户ID列表
     * @param source 快照来源（通常为 RECON）
     * @return 影响行数
     */
    @Update({
            "<script>",
            "UPDATE quota_usage_snapshot",
            "SET used_storage_bytes = 0, used_file_count = 0, source = #{source}, snapshot_time = NOW()",
            "WHERE tenant_id = #{tenantId}",
            "AND user_id &lt;&gt; 0",
            "<if test='activeUserIds != null and activeUserIds.size() &gt; 0'>",
            "AND user_id NOT IN",
            "<foreach collection='activeUserIds' item='userId' open='(' separator=',' close=')'>",
            "#{userId}",
            "</foreach>",
            "</if>",
            "</script>"
    })
    int resetMissingUserSnapshots(@Param("tenantId") Long tenantId,
                                  @Param("activeUserIds") List<Long> activeUserIds,
                                  @Param("source") String source);
}
