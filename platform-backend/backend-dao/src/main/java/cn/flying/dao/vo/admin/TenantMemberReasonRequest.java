package cn.flying.dao.vo.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Mandatory sanitized reason for member administration. */
public record TenantMemberReasonRequest(@NotBlank @Size(max = 255) String reason) {
}
