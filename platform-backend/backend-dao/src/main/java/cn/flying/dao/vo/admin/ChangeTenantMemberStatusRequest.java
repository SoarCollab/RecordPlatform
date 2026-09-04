package cn.flying.dao.vo.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Tenant member status mutation request. */
public record ChangeTenantMemberStatusRequest(
        @NotNull @Min(0) @Max(1) Integer status,
        @NotBlank @Size(max = 255) String reason) {
}
