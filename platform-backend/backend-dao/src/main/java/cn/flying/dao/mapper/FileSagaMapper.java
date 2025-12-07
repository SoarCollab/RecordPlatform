package cn.flying.dao.mapper;

import cn.flying.dao.entity.FileSaga;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Date;
import java.util.List;

@Mapper
public interface FileSagaMapper extends BaseMapper<FileSaga> {

    @Select("SELECT * FROM file_saga WHERE request_id = #{requestId}")
    FileSaga selectByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM file_saga WHERE status = #{status} AND update_time < #{cutoffTime} LIMIT #{limit}")
    List<FileSaga> selectStuckSagas(@Param("status") String status,
                                     @Param("cutoffTime") Date cutoffTime,
                                     @Param("limit") int limit);

    @Update("UPDATE file_saga SET status = #{newStatus}, update_time = NOW() " +
            "WHERE id = #{id} AND status = #{expectedStatus}")
    int updateStatusWithCas(@Param("id") Long id,
                           @Param("expectedStatus") String expectedStatus,
                           @Param("newStatus") String newStatus);
}
