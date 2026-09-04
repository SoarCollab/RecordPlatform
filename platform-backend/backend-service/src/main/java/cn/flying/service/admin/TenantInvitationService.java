package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.AccountInvitation;
import cn.flying.dao.mapper.AccountInvitationMapper;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.dao.vo.admin.AcceptTenantInvitationRequest;
import cn.flying.dao.vo.admin.CreateTenantInvitationRequest;
import cn.flying.dao.vo.admin.TenantInvitationVO;
import cn.flying.dao.vo.admin.TenantMemberVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Manages digest-only, tenant-bound member invitations. */
@Service
@RequiredArgsConstructor
public class TenantInvitationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String PENDING = "PENDING";
    private final AccountInvitationMapper invitationMapper;
    private final AccountMapper accountMapper;
    private final TenantMapper tenantMapper;
    private final PasswordEncoder passwordEncoder;
    private final TenantInvitationMailSender invitationMailSender;
    private final TenantMemberAuditService auditService;

    @Value("${spring.web.invitation.accept-url:/invitations/accept}")
    private String invitationAcceptUrl;

    /** Creates an invitation and emits its opaque token only to the mail delivery boundary. */
    @Transactional
    public TenantInvitationVO create(Long tenantId, Long actorId, CreateTenantInvitationRequest request) {
        String email = normalizeEmail(request.email());
        String reason = auditService.sanitizeReason(request.reason());
        requireAssignableRole(request.role());
        lockTenantForInvitationCreation(tenantId);
        if (accountMapper.countByGlobalEmail(email) != 0) {
            throw new GeneralException(ResultEnum.INVITATION_ACCOUNT_CONFLICT);
        }
        LocalDateTime now = LocalDateTime.now();
        invitationMapper.expirePastDueByEmail(tenantId, email, now);
        if (invitationMapper.countLiveByEmail(tenantId, email, now) != 0) {
            throw new GeneralException(ResultEnum.INVITATION_ALREADY_EXISTS);
        }

        String rawToken = newOpaqueToken();
        AccountInvitation invitation = new AccountInvitation()
                .setId(IdUtils.nextEntityId())
                .setTenantId(tenantId)
                .setTokenHash(hashToken(rawToken))
                .setEmail(email)
                .setRole(request.role())
                .setStatus(PENDING)
                .setInvitedBy(actorId)
                .setExpiresAt(now.plusHours(request.expiresInHours()))
                .setCreateTime(now)
                .setUpdateTime(now);
        try {
            if (invitationMapper.insert(invitation) != 1) {
                throw new IllegalStateException("Invitation persistence failed");
            }
        } catch (DuplicateKeyException exception) {
            throw new GeneralException(ResultEnum.INVITATION_ALREADY_EXISTS);
        }
        auditService.record(tenantId, actorId, null, invitation.getId(), "INVITATION_CREATED",
                null, request.role(), reason);
        invitationMailSender.sendInvitation(email, invitationAcceptUrl + "#token="
                + URLEncoder.encode(rawToken, StandardCharsets.UTF_8));
        return toView(invitation);
    }

    /** Lists recent invitation metadata without returning token material. */
    public List<TenantInvitationVO> list(Long tenantId) {
        return invitationMapper.selectList(Wrappers.<AccountInvitation>lambdaQuery()
                        .eq(AccountInvitation::getTenantId, tenantId)
                        .orderByDesc(AccountInvitation::getCreateTime)
                        .last("LIMIT 100"))
                .stream().map(this::toView).toList();
    }

    /** Revokes one pending invitation in the authenticated tenant. */
    @Transactional
    public void revoke(Long tenantId, Long actorId, Long invitationId, String reason) {
        String sanitizedReason = auditService.sanitizeReason(reason);
        if (invitationId == null || invitationMapper.revokePending(
                tenantId, invitationId, actorId, sanitizedReason, LocalDateTime.now()) != 1) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
        auditService.record(tenantId, actorId, null, invitationId, "INVITATION_REVOKED",
                PENDING, "REVOKED", sanitizedReason);
    }

    /** Accepts a token once after a narrow owner lookup and forced tenant isolation. */
    @Transactional
    public TenantMemberVO accept(AcceptTenantInvitationRequest request) {
        String tokenHash = hashToken(request.token());
        Long tenantId = invitationMapper.selectOwnerTenantIdByTokenHash(tokenHash);
        if (tenantId == null || tenantId < 0) {
            throw new GeneralException(ResultEnum.INVITATION_INVALID);
        }
        return TenantContext.callWithTenantIsolation(tenantId,
                () -> acceptInTenant(tenantId, tokenHash, request));
    }

    /** Performs all tenant-owned acceptance reads and writes under the recovered owner context. */
    private TenantMemberVO acceptInTenant(Long tenantId, String tokenHash, AcceptTenantInvitationRequest request) {
        AccountInvitation invitation = invitationMapper.selectForAcceptance(tenantId, tokenHash);
        LocalDateTime now = LocalDateTime.now();
        if (invitation == null || !PENDING.equals(invitation.getStatus())
                || !invitation.getExpiresAt().isAfter(now)) {
            throw new GeneralException(ResultEnum.INVITATION_INVALID);
        }
        String username = request.username().trim();
        if (accountMapper.countByGlobalEmailOrUsername(invitation.getEmail(), username) != 0) {
            throw new GeneralException(ResultEnum.INVITATION_ACCOUNT_CONFLICT);
        }
        Account account = new Account();
        account.setId(IdUtils.nextUserId());
        account.setTenantId(tenantId);
        account.setUsername(username);
        account.setEmail(invitation.getEmail());
        account.setNickname(request.nickname() == null ? null : request.nickname().trim());
        account.setPassword(passwordEncoder.encode(request.password()));
        account.setRole(invitation.getRole());
        account.setStatus(1);
        account.setAuthVersion(0L);
        account.setRegisterTime(new java.util.Date());
        account.setUpdateTime(new java.util.Date());
        account.setDeleted(0);
        try {
            if (accountMapper.insert(account) != 1
                    || invitationMapper.markAccepted(tenantId, invitation.getId(), account.getId(), now) != 1) {
                throw new GeneralException(ResultEnum.INVITATION_INVALID);
            }
        } catch (DuplicateKeyException exception) {
            throw new GeneralException(ResultEnum.INVITATION_ACCOUNT_CONFLICT);
        }
        auditService.record(tenantId, account.getId(), account.getId(), invitation.getId(),
                "INVITATION_ACCEPTED", PENDING, "ACCEPTED", "Invitation accepted by recipient");
        return new TenantMemberVO(IdUtils.toExternalUserId(account.getId()), account.getUsername(), account.getEmail(),
                account.getNickname(), account.getRole(), account.getStatus(), account.getRegisterTime(), null);
    }

    /** Generates 256 bits of URL-safe entropy without padding. */
    private String newOpaqueToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Creates the deterministic lowercase digest used by the only global token lookup. */
    static String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    /** Normalizes globally unique account emails. */
    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Restricts invitations to tenant roles. */
    private void requireAssignableRole(String role) {
        if (!UserRole.getRole(role).isTenantRole()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
    }

    /** Serializes pending-invitation creation before MySQL range scans and insertion. */
    private void lockTenantForInvitationCreation(Long tenantId) {
        if (tenantId == null || tenantId < 0
                || !Objects.equals(tenantMapper.lockTenantForMemberMutation(tenantId), tenantId)) {
            throw new GeneralException(ResultEnum.TENANT_MEMBER_NOT_FOUND);
        }
    }

    /** Maps invitation metadata without raw or hashed token material. */
    private TenantInvitationVO toView(AccountInvitation invitation) {
        String status = invitation.getStatus();
        if (PENDING.equals(status) && invitation.getExpiresAt() != null
                && !invitation.getExpiresAt().isAfter(LocalDateTime.now())) {
            status = "EXPIRED";
        }
        return new TenantInvitationVO(IdUtils.toExternalId(invitation.getId()), invitation.getEmail(),
                invitation.getRole(), status, invitation.getExpiresAt(), invitation.getCreateTime());
    }
}
