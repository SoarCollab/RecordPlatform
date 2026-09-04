package cn.flying.dao.mapper;

import cn.flying.dao.dto.Account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * @program: RecordPlatform
 * @description: 用户mapper接口类
 * @create: 2025-01-16 14:55
 */
@Mapper
public interface AccountMapper extends BaseMapper<Account> {

    /** Loads only fields required to authorize one account in one tenant. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT id, tenant_id, role, status, auth_version, deleted
              FROM account
             WHERE id = #{accountId} AND tenant_id = #{tenantId}
             LIMIT 1
            """)
    Account selectAuthorizationState(@Param("tenantId") Long tenantId, @Param("accountId") Long accountId);

    /** Atomically increments the account-wide session version inside the caller transaction. */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE account
               SET auth_version = auth_version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{accountId} AND tenant_id = #{tenantId} AND deleted = 0
            """)
    int incrementAuthVersion(@Param("tenantId") Long tenantId, @Param("accountId") Long accountId);

    /** Updates a password and revokes all existing account tokens in one database statement. */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE account
               SET password = #{passwordHash},
                   auth_version = auth_version + 1,
                   update_time = CURRENT_TIMESTAMP
             WHERE id = #{accountId} AND tenant_id = #{tenantId} AND deleted = 0 AND status = 1
            """)
    int updatePasswordAndIncrementAuthVersion(
            @Param("tenantId") Long tenantId,
            @Param("accountId") Long accountId,
            @Param("passwordHash") String passwordHash);

    /** Records operational login time without changing authorization state. */
    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            UPDATE account
               SET last_login_time = CURRENT_TIMESTAMP
             WHERE id = #{accountId} AND tenant_id = #{tenantId} AND deleted = 0
            """)
    int updateLastLoginTime(@Param("tenantId") Long tenantId, @Param("accountId") Long accountId);

    /** Counts existing platform administrators without exposing account details. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            SELECT COUNT(*)
              FROM account
             WHERE tenant_id = 0 AND role = 'platform_admin'
            """)
    long countPlatformAdministrators();

    /** Loads a tenant member for update without permitting platform identities. */
    @Select("""
            SELECT id, tenant_id, username, password, email, role, status, auth_version,
                   last_login_time, avatar, nickname, register_time, update_time, deleted
              FROM account
             WHERE id = #{accountId} AND tenant_id = #{tenantId}
               AND role <> 'platform_admin' AND deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    Account selectTenantMemberForUpdate(@Param("tenantId") Long tenantId, @Param("accountId") Long accountId);

    /** Counts active tenant administrators inside the caller's tenant lock. */
    @Select("""
            SELECT COUNT(*) FROM account
             WHERE tenant_id = #{tenantId} AND role = 'admin' AND status = 1 AND deleted = 0
            """)
    long countActiveTenantAdministrators(@Param("tenantId") Long tenantId);

    /** Changes role and revokes existing tokens atomically. */
    @Update("""
            UPDATE account SET role = #{role}, auth_version = auth_version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = #{accountId} AND tenant_id = #{tenantId} AND role <> 'platform_admin' AND deleted = 0
            """)
    int updateTenantMemberRole(@Param("tenantId") Long tenantId,
                               @Param("accountId") Long accountId,
                               @Param("role") String role);

    /** Changes status and revokes existing tokens atomically. */
    @Update("""
            UPDATE account SET status = #{status}, auth_version = auth_version + 1, update_time = CURRENT_TIMESTAMP
             WHERE id = #{accountId} AND tenant_id = #{tenantId} AND role <> 'platform_admin' AND deleted = 0
            """)
    int updateTenantMemberStatus(@Param("tenantId") Long tenantId,
                                 @Param("accountId") Long accountId,
                                 @Param("status") Integer status);

    /** Global uniqueness check used only by invitation acceptance. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM account WHERE LOWER(email) = #{email} OR username = #{username}")
    long countByGlobalEmailOrUsername(@Param("email") String email, @Param("username") String username);

    /** Checks global email uniqueness before issuing an invitation. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT COUNT(*) FROM account WHERE LOWER(email) = #{email}")
    long countByGlobalEmail(@Param("email") String email);

    /** Reads one visible tenant member without exposing platform identities. */
    @Select("""
            SELECT id, tenant_id, username, email, role, status, nickname, register_time, last_login_time
              FROM account
             WHERE id = #{accountId} AND tenant_id = #{tenantId}
               AND role <> 'platform_admin' AND deleted = 0
             LIMIT 1
            """)
    Account selectTenantMember(@Param("tenantId") Long tenantId, @Param("accountId") Long accountId);

    /** Bounded, stable tenant-member search. */
    @Select("""
            <script>
            SELECT id, tenant_id, username, email, role, status, nickname, register_time, last_login_time
              FROM account
             WHERE tenant_id = #{tenantId} AND role &lt;&gt; 'platform_admin' AND deleted = 0
            <if test="keyword != null and keyword != ''">
               AND (username LIKE CONCAT('%', #{keyword}, '%')
                    OR email LIKE CONCAT('%', #{keyword}, '%')
                    OR nickname LIKE CONCAT('%', #{keyword}, '%'))
            </if>
            <if test="role != null and role != ''"> AND role = #{role}</if>
            <if test="status != null"> AND status = #{status}</if>
             ORDER BY register_time DESC, id DESC
            </script>
            """)
    IPage<Account> selectTenantMembers(Page<Account> page,
                                       @Param("tenantId") Long tenantId,
                                       @Param("keyword") String keyword,
                                       @Param("role") String role,
                                       @Param("status") Integer status);
}
