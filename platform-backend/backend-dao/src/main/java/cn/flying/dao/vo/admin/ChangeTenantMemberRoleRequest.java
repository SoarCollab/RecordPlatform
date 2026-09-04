package cn.flying.dao.vo.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Tenant member role mutation request. */
public record ChangeTenantMemberRoleRequest(
        @NotBlank @Pattern(regexp = "user|admin|monitor") String role,
        @NotBlank @Size(max = 255) String reason) {
}
