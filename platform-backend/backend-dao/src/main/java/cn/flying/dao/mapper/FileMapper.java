package cn.flying.dao.mapper;

import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.QuotaUserUsageVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;
import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 文件Mapper
 * @author flyingcoding
 * @create: 2025-04-20 16:05
 */
@Mapper
public interface FileMapper extends BaseMapper<File> {

    /**
     *  flyingcoding
     *  flyingcoding
     *
     * @param tenantId tenant ID for isolation
     * @param cutoffDate files deleted before this date
     * @param limit max number of files to return
     * @return list of files pending cleanup
     */
    @Select("SELECT id, tenant_id, uid, origin, shared_from_user_id, file_name, classification, file_param, file_hash, transaction_hash, status, deleted, create_time " +
            "FROM file WHERE deleted = 1 AND tenant_id = #{tenantId} AND create_time < #{cutoffDate} LIMIT #{limit}")
    List<File> selectDeletedFilesForCleanup(@Param("tenantId") Long tenantId,
                                            @Param("cutoffDate") Date cutoffDate,
                                            @Param("limit") int limit);

    /**
     *  flyingcoding
     *  flyingcoding
     *
     * @param id file ID
     * @param tenantId tenant ID for isolation
     * @return number of rows affected
     */
    @Delete("DELETE FROM file WHERE id = #{id} AND tenant_id = #{tenantId}")
    int physicalDeleteById(@Param("id") Long id, @Param("tenantId") Long tenantId);

    /**
     * 统计用户文件数量
     *
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @return 文件数量
     */
    @Select("SELECT COUNT(*) FROM file WHERE uid = #{userId} AND tenant_id = #{tenantId} AND deleted = 0")
    Long countByUserId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 统计用户今日上传数量
     *
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @param todayStart 今日开始时间
     * @return 今日上传数量
     */
    @Select("SELECT COUNT(*) FROM file WHERE uid = #{userId} AND tenant_id = #{tenantId} AND deleted = 0 AND create_time >= #{todayStart}")
    Long countTodayUploadsByUserId(@Param("userId") Long userId, @Param("tenantId") Long tenantId, @Param("todayStart") Date todayStart);

    /**
     * 统计使用相同 fileHash 的未删除文件数量（跨租户）
     * 用于判断物理删除时是否还有其他用户引用该存储文件
     *
     * @param fileHash 文件哈希
     * @param excludeId 排除的文件ID（当前要删除的文件）
     * @return 引用该 fileHash 的未删除文件数量
     */
    @Select("SELECT COUNT(*) FROM file WHERE file_hash = #{fileHash} AND id != #{excludeId} AND deleted = 0")
    Long countActiveFilesByHash(@Param("fileHash") String fileHash, @Param("excludeId") Long excludeId);

    /**
     * 根据ID查询文件（包括已删除的记录，绕过@TableLogic）
     * 用于分享文件下载时追溯原始上传者信息
     * <p>
     * <b>安全说明</b>：此方法有意不添加租户隔离，因为：
     * <ul>
     *   <li>仅用于追溯分享文件的原始上传者，不返回敏感数据</li>
     *   <li>分享场景本身就是跨用户的，原始文件可能属于不同租户</li>
     *   <li>调用方仅使用返回的 uid 字段用于区块链查询</li>
     * </ul>
     *
     * @param id 文件ID（来自当前用户文件的 origin 字段，非外部输入）
     * @return 文件记录（可能已删除），仅用于获取 uid
     */
    @Select("SELECT id, uid, tenant_id FROM file WHERE id = #{id}")
    File selectByIdIncludeDeleted(@Param("id") Long id);

    /**
     * 计算用户文件总存储量（使用数据库聚合，避免加载全部文件到内存）
     * file_param 是 JSON 格式，使用 JSON_EXTRACT 提取 fileSize
     * 支持 tenantId 为 null 时忽略租户条件
     *
     * @param userId 用户ID
     * @param tenantId 租户ID（可为 null）
     * @return 总存储量（字节）
     */
    @Select("""
            <script>
            SELECT COALESCE(SUM(
                CASE
                    WHEN file_param IS NOT NULL AND JSON_VALID(file_param)
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(file_param, '$.fileSize')) AS UNSIGNED)
                    ELSE 0
                END
            ), 0)
            FROM file
            WHERE uid = #{userId} AND deleted = 0
            <if test="tenantId != null">
                AND tenant_id = #{tenantId}
            </if>
            </script>
            """)
    Long sumStorageByUserId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 统计用户在配额口径下的文件数量（仅 PREPARE/SUCCESS）。
     *
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @return 配额口径文件数量
     */
    @Select("SELECT COUNT(*) FROM file WHERE uid = #{userId} AND tenant_id = #{tenantId} AND deleted = 0 AND status IN (0, 1)")
    Long countQuotaByUserId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 统计用户在配额口径下的总存储（仅 PREPARE/SUCCESS）。
     *
     * @param userId 用户ID
     * @param tenantId 租户ID
     * @return 配额口径总存储（字节）
     */
    @Select("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN file_param IS NOT NULL AND JSON_VALID(file_param)
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(file_param, '$.fileSize')) AS UNSIGNED)
                    ELSE 0
                END
            ), 0)
            FROM file
            WHERE uid = #{userId} AND tenant_id = #{tenantId} AND deleted = 0 AND status IN (0, 1)
            """)
    Long sumQuotaStorageByUserId(@Param("userId") Long userId, @Param("tenantId") Long tenantId);

    /**
     * 统计租户文件数量。
     *
     * @param tenantId 租户ID
     * @return 文件数量
     */
    @Select("SELECT COUNT(*) FROM file WHERE tenant_id = #{tenantId} AND deleted = 0")
    Long countByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 统计租户在配额口径下的文件数量（仅 PREPARE/SUCCESS）。
     *
     * @param tenantId 租户ID
     * @return 配额口径文件数量
     */
    @Select("SELECT COUNT(*) FROM file WHERE tenant_id = #{tenantId} AND deleted = 0 AND status IN (0, 1)")
    Long countQuotaByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 统计租户总存储量（字节）。
     *
     * @param tenantId 租户ID
     * @return 总存储字节数
     */
    @Select("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN file_param IS NOT NULL AND JSON_VALID(file_param)
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(file_param, '$.fileSize')) AS UNSIGNED)
                    ELSE 0
                END
            ), 0)
            FROM file
            WHERE tenant_id = #{tenantId} AND deleted = 0
            """)
    Long sumStorageByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 统计租户在配额口径下的总存储（仅 PREPARE/SUCCESS）。
     *
     * @param tenantId 租户ID
     * @return 配额口径总存储（字节）
     */
    @Select("""
            SELECT COALESCE(SUM(
                CASE
                    WHEN file_param IS NOT NULL AND JSON_VALID(file_param)
                    THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(file_param, '$.fileSize')) AS UNSIGNED)
                    ELSE 0
                END
            ), 0)
            FROM file
            WHERE tenant_id = #{tenantId} AND deleted = 0 AND status IN (0, 1)
            """)
    Long sumQuotaStorageByTenantId(@Param("tenantId") Long tenantId);

    /**
     * 聚合租户下每个用户的文件使用量，用于配额快照对账。
     *
     * @param tenantId 租户ID
     * @return 用户使用量列表
     */
    @Select("""
            SELECT
                uid AS userId,
                COALESCE(SUM(
                    CASE
                        WHEN file_param IS NOT NULL AND JSON_VALID(file_param)
                        THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(file_param, '$.fileSize')) AS UNSIGNED)
                        ELSE 0
                    END
                ), 0) AS usedStorageBytes,
                COUNT(*) AS usedFileCount
            FROM file
            WHERE tenant_id = #{tenantId} AND deleted = 0
            GROUP BY uid
            """)
    List<QuotaUserUsageVO> aggregateUserUsageByTenant(@Param("tenantId") Long tenantId);

    /**
     * 按配额口径聚合租户下每个用户的使用量（仅 PREPARE/SUCCESS）。
     *
     * @param tenantId 租户ID
     * @return 用户配额口径使用量列表
     */
    @Select("""
            SELECT
                uid AS userId,
                COALESCE(SUM(
                    CASE
                        WHEN file_param IS NOT NULL AND JSON_VALID(file_param)
                        THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(file_param, '$.fileSize')) AS UNSIGNED)
                        ELSE 0
                    END
                ), 0) AS usedStorageBytes,
                COUNT(*) AS usedFileCount
            FROM file
            WHERE tenant_id = #{tenantId} AND deleted = 0 AND status IN (0, 1)
            GROUP BY uid
            """)
    List<QuotaUserUsageVO> aggregateQuotaUserUsageByTenant(@Param("tenantId") Long tenantId);
}
