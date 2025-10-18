package cn.flying.monitor.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * MFA setup request DTO
 */
@Data
public class MfaSetupRequest {
    
    @NotBlank(message = "Password is required")
    private String password;
    
    @NotBlank(message = "TOTP code is required for verification")
    private String totpCode;
}