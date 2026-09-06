package cn.flying.dao.mapper;

import cn.flying.dao.entity.AccountMemberAudit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persists tenant-scoped member mutation audits. */
@Mapper
public interface AccountMemberAuditMapper extends BaseMapper<AccountMemberAudit> {
}
