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
}
