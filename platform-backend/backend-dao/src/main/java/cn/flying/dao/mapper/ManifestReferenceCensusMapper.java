package cn.flying.dao.mapper;

import cn.flying.dao.entity.ManifestReferenceCensus;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Mapper for reference-census completion boundaries.
 */
@Mapper
public interface ManifestReferenceCensusMapper extends BaseMapper<ManifestReferenceCensus> {

    /**
     * Loads the newest completed census for a tenant.
     *
     * @param tenantId tenant ID
     * @return latest completed census or null
     */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, status, census_digest, known_reference_count,
                   unknown_hold_count, last_error_class, completed_at,
                   create_time, update_time, deleted
            FROM manifest_reference_census
            WHERE tenant_id = #{tenantId}
              AND status = 'COMPLETED'
              AND deleted = 0
            ORDER BY completed_at DESC, id DESC
            LIMIT 1
            """)
    ManifestReferenceCensus selectLatestCompleted(@Param("tenantId") Long tenantId);
}
