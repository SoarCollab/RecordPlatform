package cn.flying.dao.mapper;

import cn.flying.dao.entity.QuotaPolicy;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 配额策略 Mapper。
 */
@Mapper
public interface QuotaPolicyMapper extends BaseMapper<QuotaPolicy> {

    /**
     * 查询生效中的配额策略。
     *
     * @param tenantId 租户ID
     * @param scopeType 作用域类型（TENANT/USER）
     * @param scopeId 作用域ID
     * @return 配额策略
     */
    @Select("SELECT id, tenant_id, scope_type, scope_id, max_storage_bytes, max_file_count, status, create_time, update_time " +
            "FROM quota_policy WHERE tenant_id = #{tenantId} AND scope_type = #{scopeType} AND scope_id = #{scopeId} AND status = 1 LIMIT 1")
    QuotaPolicy selectActivePolicy(@Param("tenantId") Long tenantId,
                                   @Param("scopeType") String scopeType,
                                   @Param("scopeId") Long scopeId);
}
