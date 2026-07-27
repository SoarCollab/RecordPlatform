package cn.flying.dao.mapper;

import cn.flying.dao.entity.KeyRotationAuditLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mapper for sanitized automated-rotation audit evidence.
 */
@Mapper
public interface KeyRotationAuditLogMapper extends BaseMapper<KeyRotationAuditLog> {
}
