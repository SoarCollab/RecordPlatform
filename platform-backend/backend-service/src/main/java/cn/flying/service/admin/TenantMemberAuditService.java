package cn.flying.service.admin;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SensitiveDataMasker;
import cn.flying.dao.entity.AccountMemberAudit;
import cn.flying.dao.mapper.AccountMemberAuditMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.regex.Pattern;

/** Persists redacted tenant-member mutation evidence. */
@Service
@RequiredArgsConstructor
public class TenantMemberAuditService {

    private static final Pattern INLINE_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|authorization|credential|code|otp)"
                    + "\\s*[:=]\\s*(?:\\\"[^\\\"]*\\\"|'[^']*'|\\S+)");
    private final AccountMemberAuditMapper auditMapper;

    /** Writes a sanitized audit record without request bodies or secrets. */
    public void record(Long tenantId, Long actorId, Long targetAccountId, Long invitationId,
                       String action, String oldValue, String newValue, String reason) {
        AccountMemberAudit audit = new AccountMemberAudit()
                .setId(IdUtils.nextEntityId())
                .setTenantId(tenantId)
                .setActorId(actorId)
                .setTargetAccountId(targetAccountId)
                .setInvitationId(invitationId)
                .setAction(action)
                .setOldValue(sanitizeValue(oldValue))
                .setNewValue(sanitizeValue(newValue))
                .setReason(sanitizeReason(reason))
                .setCreateTime(LocalDateTime.now());
        if (auditMapper.insert(audit) != 1) {
            throw new IllegalStateException("Tenant member audit persistence failed");
        }
    }

    /** Normalizes a mandatory reason and removes control characters from audit storage. */
    public String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        String sanitized = INLINE_SECRET_ASSIGNMENT.matcher(SensitiveDataMasker.maskSensitiveFields(reason))
                .replaceAll("$1=******")
                .replaceAll("[\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (sanitized.isEmpty()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        return sanitized.length() <= 255 ? sanitized : sanitized.substring(0, 255);
    }

    /** Bounds old/new audit values to their schema without exposing arbitrary content. */
    private String sanitizeValue(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replaceAll("[\\p{Cntrl}]", " ").trim();
        return sanitized.length() <= 64 ? sanitized : sanitized.substring(0, 64);
    }
}
