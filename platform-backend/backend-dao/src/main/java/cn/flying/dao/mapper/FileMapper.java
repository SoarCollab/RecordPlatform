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
 * @author: flyingcoding
 * @create: 2025-04-20 16:05
 */
@Mapper
public interface FileMapper extends BaseMapper<File> {

    /**
     * Select soft-deleted files for cleanup (bypasses MyBatis-Plus logical delete interceptor)
     */
    @Select("SELECT * FROM file WHERE deleted = 1 AND create_time < #{cutoffDate} LIMIT #{limit}")
    List<File> selectDeletedFilesForCleanup(@Param("cutoffDate") Date cutoffDate, @Param("limit") int limit);

    /**
     * Physically delete a file record (bypasses MyBatis-Plus logical delete)
     */
    @Delete("DELETE FROM file WHERE id = #{id}")
    int physicalDeleteById(@Param("id") Long id);
}
