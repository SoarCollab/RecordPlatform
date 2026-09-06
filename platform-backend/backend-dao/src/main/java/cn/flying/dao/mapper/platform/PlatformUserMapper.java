package cn.flying.dao.mapper.platform;

import cn.flying.dao.entity.platform.PlatformUserRow;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Bounded global user metadata and tenant-protected count projections. */
@Mapper
public interface PlatformUserMapper {

    String FILTER = """
            FROM account a JOIN tenant t ON t.id = a.tenant_id AND t.deleted = 0
            WHERE a.deleted = 0 AND a.role IN ('user', 'admin', 'monitor')
            <if test="tenantId != null">AND a.tenant_id = #{tenantId}</if>
            <if test="keyword != null">
              AND (a.username LIKE CONCAT('%', #{keyword}, '%') OR a.nickname LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="role != null">AND a.role = #{role}</if>
            <if test="status != null">AND a.status = #{status}</if>
            """;

    /** Reads explicit public metadata columns, excluding platform accounts and authorization state. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>SELECT a.id, a.tenant_id, a.username, a.nickname, a.role, a.status, "
            + "a.register_time, a.last_login_time " + FILTER
            + " ORDER BY a.id ASC LIMIT #{limit} OFFSET #{offset}</script>")
    List<PlatformUserRow> selectPage(@Param("offset") long offset, @Param("limit") long limit,
                                     @Param("keyword") String keyword, @Param("role") String role,
                                     @Param("status") Integer status, @Param("tenantId") Long tenantId);

    /** Counts the same explicit public metadata set as the bounded page. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("<script>SELECT COUNT(*) " + FILTER + "</script>")
    long countPage(@Param("keyword") String keyword, @Param("role") String role,
                   @Param("status") Integer status, @Param("tenantId") Long tenantId);

    /** Counts members with both explicit tenant binding and normal tenant-line enforcement. */
    @Select("""
            SELECT COUNT(*) FROM account
             WHERE tenant_id = #{tenantId} AND deleted = 0 AND role IN ('user', 'admin', 'monitor')
            """)
    Long countTenantMembers(@Param("tenantId") Long tenantId);
}
