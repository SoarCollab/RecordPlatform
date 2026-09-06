package cn.flying.dao.vo.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Creates a bounded tenant invitation. */
public record CreateTenantInvitationRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Pattern(regexp = "user|admin|monitor") String role,
        @NotNull @Min(1) @Max(168) Integer expiresInHours,
        @NotBlank @Size(max = 255) String reason) {
}
