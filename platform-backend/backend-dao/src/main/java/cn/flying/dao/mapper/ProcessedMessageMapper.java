package cn.flying.dao.mapper;

import cn.flying.dao.entity.ProcessedMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Date;

@Mapper
public interface ProcessedMessageMapper extends BaseMapper<ProcessedMessage> {

    @Select("SELECT COUNT(*) > 0 FROM processed_message WHERE message_id = #{messageId}")
    boolean exists(@Param("messageId") String messageId);

    @Delete("DELETE FROM processed_message WHERE processed_at < #{cutoffDate}")
    int cleanupOldMessages(@Param("cutoffDate") Date cutoffDate);
}
