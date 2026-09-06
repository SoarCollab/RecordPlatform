package cn.flying.dao.mapper.platform;

import cn.flying.dao.entity.Tenant;
import cn.flying.dao.entity.platform.PlatformTenantRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Named metadata reads and explicitly addressed tenant lifecycle writes. */
@Mapper
public interface PlatformTenantMapper {

    String METADATA = "t.id, t.name, t.code, t.status, t.version, t.disabled_reason, "
            + "t.disabled_at, t.disabled_by, t.create_time, t.update_time";
    String MEMBER_COUNT = "(SELECT COUNT(*) FROM account a WHERE a.tenant_id = t.id "
            + "AND a.deleted = 0 AND a.role IN ('user', 'admin', 'monitor')) AS member_count";
    String FILTER = """
            WHERE t.deleted = 0
            <if test="keyword != null">
              AND (t.name LIKE CONCAT('%', #{keyword}, '%') OR t.code LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="status != null">AND t.status = #{status}</if>
            """;

    /** Reads only one bounded, stably ordered tenant metadata page. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>SELECT " + METADATA + ", " + MEMBER_COUNT + " FROM tenant t " + FILTER
            + " ORDER BY t.id ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<PlatformTenantRow> selectPage(@Param("offset") long offset, @Param("limit") long limit,
                                       @Param("keyword") String keyword, @Param("status") Integer status);

    /** Counts precisely the same filtered metadata set as the page query. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>SELECT COUNT(*) FROM tenant t " + FILTER + "</script>")
    long countPage(@Param("keyword") String keyword, @Param("status") Integer status);

    /** Resolves one explicit tenant target without exposing any business rows. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT " + METADATA + ", " + MEMBER_COUNT
            + " FROM tenant t WHERE t.id = #{tenantId} AND t.deleted = 0")
    PlatformTenantRow selectMetadata(@Param("tenantId") Long tenantId);

    /** Takes a current row lock before lifecycle, quota or target-member commands. */
    @Select("""
            SELECT id, name, code, status, version, disabled_reason, disabled_at, disabled_by,
                   create_time, update_time, deleted
              FROM tenant WHERE id = #{tenantId} AND deleted = 0 AND version >= 0 FOR UPDATE
            """)
    Tenant lockTenant(@Param("tenantId") Long tenantId);

    /** Serializes missing safe-configuration inserts independently of tenant-zero business status. */
    @Select("SELECT id FROM tenant WHERE id = 0 FOR UPDATE")
    Long lockSystemConfiguration();

    /** Creates metadata only; there is deliberately no account or credential insert here. */
    @Insert("""
            INSERT INTO tenant (id, name, code, status, version, deleted, create_time, update_time)
            VALUES (#{id}, #{name}, #{code}, 1, 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    int insertTenant(Tenant tenant);

    /** Updates display metadata only after a matching current version has been locked. */
    @Update("""
            UPDATE tenant SET name = #{name}, version = version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = #{tenantId} AND deleted = 0 AND version = #{expectedVersion}
            """)
    int updateName(@Param("tenantId") Long tenantId, @Param("name") String name,
                   @Param("expectedVersion") Long expectedVersion);

    /** Changes lifecycle metadata while preserving the guarded system tenant at SQL level. */
    @Update("""
            UPDATE tenant
               SET status = #{status}, version = version + 1, update_time = CURRENT_TIMESTAMP,
                   disabled_reason = CASE WHEN #{status} = 0 THEN #{reason} ELSE NULL END,
                   disabled_at = CASE WHEN #{status} = 0 THEN CURRENT_TIMESTAMP ELSE NULL END,
                   disabled_by = CASE WHEN #{status} = 0 THEN #{actorId} ELSE NULL END
             WHERE id = #{tenantId} AND deleted = 0 AND version = #{expectedVersion}
               AND (id != 0 OR #{status} = 1)
            """)
    int updateStatus(@Param("tenantId") Long tenantId, @Param("status") Integer status,
                     @Param("expectedVersion") Long expectedVersion, @Param("reason") String reason,
                     @Param("actorId") Long actorId);
}
