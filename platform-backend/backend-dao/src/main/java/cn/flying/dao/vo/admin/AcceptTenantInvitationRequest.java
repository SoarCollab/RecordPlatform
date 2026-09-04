package cn.flying.dao.vo.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Public invitation acceptance payload. */
public record AcceptTenantInvitationRequest(
        @NotBlank @Size(min = 40, max = 128) String token,
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{3,50}$") String username,
        @Size(max = 50) String nickname,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
