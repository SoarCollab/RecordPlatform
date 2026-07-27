package cn.flying.dao.mapper;

import cn.flying.dao.entity.TenantCryptoPolicyAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Persistence boundary for sanitized crypto policy audit evidence.
 */
@Mapper
public interface TenantCryptoPolicyAuditMapper extends BaseMapper<TenantCryptoPolicyAudit> {
}
