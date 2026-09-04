package cn.flying.dao.mapper;

import cn.flying.dao.entity.AccountInvitation;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Narrow invitation persistence contract. */
@Mapper
public interface AccountInvitationMapper extends BaseMapper<AccountInvitation> {

    /** Resolves only the owner tenant for an opaque token digest. */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT tenant_id FROM account_invitation WHERE token_hash = #{tokenHash} LIMIT 1")
    Long selectOwnerTenantIdByTokenHash(@Param("tokenHash") String tokenHash);

    /** Locks one invitation after the caller has forced its owner tenant context. */
    @Select("""
            SELECT id, tenant_id, token_hash, email, role, status, invited_by, expires_at,
                   accepted_by, accepted_at, revoked_by, revoked_at, revoke_reason, create_time, update_time
              FROM account_invitation
             WHERE tenant_id = #{tenantId} AND token_hash = #{tokenHash}
             LIMIT 1 FOR UPDATE
            """)
    AccountInvitation selectForAcceptance(@Param("tenantId") Long tenantId, @Param("tokenHash") String tokenHash);

    /** Atomically completes a still-pending invitation. */
    @Update("""
            UPDATE account_invitation
               SET status = 'ACCEPTED', accepted_by = #{accountId}, accepted_at = #{now}, update_time = #{now}
             WHERE id = #{invitationId} AND tenant_id = #{tenantId} AND status = 'PENDING'
            """)
    int markAccepted(@Param("tenantId") Long tenantId,
                     @Param("invitationId") Long invitationId,
                     @Param("accountId") Long accountId,
                     @Param("now") LocalDateTime now);

    /** Retires expired pending invitations before issuing a replacement. */
    @Update("""
            UPDATE account_invitation
               SET status = 'EXPIRED', update_time = #{now}
             WHERE tenant_id = #{tenantId} AND email = #{email}
               AND status = 'PENDING' AND expires_at <= #{now}
            """)
    int expirePastDueByEmail(@Param("tenantId") Long tenantId,
                             @Param("email") String email,
                             @Param("now") LocalDateTime now);

    /** Revokes one live invitation within its authenticated tenant. */
    @Update("""
            UPDATE account_invitation
               SET status = 'REVOKED', revoked_by = #{actorId}, revoked_at = #{now},
                   revoke_reason = #{reason}, update_time = #{now}
             WHERE id = #{invitationId} AND tenant_id = #{tenantId} AND status = 'PENDING'
            """)
    int revokePending(@Param("tenantId") Long tenantId,
                      @Param("invitationId") Long invitationId,
                      @Param("actorId") Long actorId,
                      @Param("reason") String reason,
                      @Param("now") LocalDateTime now);

    /** Counts a live duplicate without exposing other tenants. */
    @Select("""
            SELECT COUNT(*) FROM account_invitation
             WHERE tenant_id = #{tenantId} AND email = #{email} AND status = 'PENDING' AND expires_at > #{now}
            """)
    long countLiveByEmail(@Param("tenantId") Long tenantId,
                          @Param("email") String email,
                          @Param("now") LocalDateTime now);
}
