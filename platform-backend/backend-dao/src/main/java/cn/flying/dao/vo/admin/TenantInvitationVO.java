package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** Invitation metadata that never includes the raw token or digest. */
@Schema(description = "租户成员邀请")
public record TenantInvitationVO(
        String id,
        String email,
        String role,
        String status,
        LocalDateTime expiresAt,
        LocalDateTime createTime) {
}
