package cn.flying.dao.mapper;

import cn.flying.dao.dto.File;
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
    @Select("SELECT * FROM file WHERE deleted = 1 AND tenant_id = #{tenantId} AND create_time < #{cutoffDate} LIMIT #{limit}")
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
}

