package cn.flying.dao.mapper;

import cn.flying.dao.dto.Account;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;

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
}
