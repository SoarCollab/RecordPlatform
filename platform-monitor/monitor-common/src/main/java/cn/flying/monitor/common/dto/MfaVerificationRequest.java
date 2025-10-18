package cn.flying.monitor.common.dto;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;

/**
 * MFA verification request DTO
 */
@Data
public class MfaVerificationRequest {
    
    @NotBlank(message = "Code is required")
    private String code;
    
    private boolean isBackupCode = false;
}