package cn.flying.dao.mapper.platform;

import cn.flying.dao.entity.platform.PlatformOverviewRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Explicit aggregate projections without content, endpoints, or credentials. */
@Mapper
public interface PlatformOverviewMapper {

    /** Counts platform metadata in one query without materializing global business entities. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT (SELECT COUNT(*) FROM tenant WHERE deleted = 0) AS tenants,
                   (SELECT COUNT(*) FROM tenant WHERE deleted = 0 AND status = 1) AS active_tenants,
                   (SELECT COUNT(*) FROM tenant WHERE deleted = 0 AND status = 0) AS disabled_tenants,
                   (SELECT COUNT(*) FROM account a JOIN tenant t ON t.id = a.tenant_id AND t.deleted = 0
                     WHERE a.deleted = 0 AND a.role IN ('user', 'admin', 'monitor')) AS users,
                   (SELECT COUNT(*) FROM account a JOIN tenant t ON t.id = a.tenant_id AND t.deleted = 0
                     WHERE a.deleted = 0 AND a.status = 1 AND a.role IN ('user', 'admin', 'monitor')) AS active_users
            """)
    PlatformOverviewRow selectOverview();

    /** Counts only ordinary business operation audit rows in the forced target tenant. */
    @Select("SELECT COUNT(*) FROM sys_operation_log WHERE tenant_id = #{tenantId}")
    Long countTenantAudit(@Param("tenantId") Long tenantId);

    /** Counts completed tenant-owned batches; shared chain height is never a tenant measurement. */
    @Select("""
            SELECT COUNT(*) FROM attestation_batch
             WHERE tenant_id = #{tenantId} AND deleted = 0 AND status = 'COMPLETED'
            """)
    Long countCompletedAttestations(@Param("tenantId") Long tenantId);
}
