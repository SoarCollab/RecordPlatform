package cn.flying.monitor.common.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MFA设置响应DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class MfaSetupResponse {
    
    /**
     * MFA密钥
     */
    private String secret;
    
    /**
     * QR码URL
     */
    private String qrCodeUrl;
    
    /**
     * 备份码列表
     */
    private List<String> backupCodes;
    
    /**
     * 是否已确认设置
     */
    private boolean confirmed;
}