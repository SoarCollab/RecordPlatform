package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/** Public tenant-member representation without internal identifiers. */
@Schema(description = "租户成员")
public record TenantMemberVO(
        @Schema(description = "成员外部ID") String id,
        String username,
        String email,
        String nickname,
        @Schema(allowableValues = {"user", "admin", "monitor"}) String role,
        @Schema(description = "1-启用，0-禁用") Integer status,
        Date registerTime,
        Date lastLoginTime) {
}
