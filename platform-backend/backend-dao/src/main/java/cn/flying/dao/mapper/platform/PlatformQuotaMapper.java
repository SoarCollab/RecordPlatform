package cn.flying.dao.mapper.platform;

import cn.flying.dao.entity.QuotaPolicy;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Tenant-line-protected, versioned TENANT override access. */
@Mapper
public interface PlatformQuotaMapper {

    String COLUMNS = "id, tenant_id, scope_type, scope_id, max_storage_bytes, max_file_count, status, "
            + "version, create_time, update_time";

    /** Reads the writable exact override, including a currently disabled override. */
    @Select("SELECT " + COLUMNS + " FROM quota_policy WHERE tenant_id = #{tenantId} "
            + "AND scope_type = 'TENANT' AND scope_id = #{tenantId}")
    QuotaPolicy selectOverride(@Param("tenantId") Long tenantId);

    /** Locks the exact override after the target tenant serialization lock. */
    @Select("SELECT " + COLUMNS + " FROM quota_policy WHERE tenant_id = #{tenantId} "
            + "AND scope_type = 'TENANT' AND scope_id = #{tenantId} FOR UPDATE")
    QuotaPolicy lockOverride(@Param("tenantId") Long tenantId);

    /** Identifies the existing tenant-default fallback without replacing QuotaService resolution. */
    @Select("SELECT " + COLUMNS + " FROM quota_policy WHERE tenant_id = #{tenantId} "
            + "AND scope_type = 'TENANT' AND scope_id = 0 AND status = 1")
    QuotaPolicy selectDefault(@Param("tenantId") Long tenantId);

    /** Inserts one explicit tenant override with a Snowflake ID and caller-selected initial version. */
    @Insert("""
            INSERT INTO quota_policy
                (id, tenant_id, scope_type, scope_id, max_storage_bytes, max_file_count, status, version)
            VALUES (#{id}, #{tenantId}, 'TENANT', #{tenantId}, #{maxStorageBytes}, #{maxFileCount}, 1, #{version})
            """)
    int insertOverride(QuotaPolicy policy);

    /** Atomically replaces an exact tenant override and advances only its own version. */
    @Update("""
            UPDATE quota_policy
               SET max_storage_bytes = #{maxStorageBytes}, max_file_count = #{maxFileCount},
                   status = 1, version = version + 1, update_time = CURRENT_TIMESTAMP
             WHERE tenant_id = #{tenantId} AND scope_type = 'TENANT' AND scope_id = #{tenantId}
               AND version = #{expectedVersion}
            """)
    int updateOverride(@Param("tenantId") Long tenantId, @Param("maxStorageBytes") Long maxStorageBytes,
                       @Param("maxFileCount") Long maxFileCount, @Param("expectedVersion") Long expectedVersion);
}
