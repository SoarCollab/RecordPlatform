package cn.flying.monitor.client.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Enhanced connection configuration for certificate-based authentication
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConnectionConfig {
    private String address;
    private String token; // Kept for backward compatibility, will be deprecated
    private String clientId;
    private boolean certificateAuthEnabled = true;
    private String certificateFingerprint;
    private int connectionTimeout = 10000;
    private int socketTimeout = 30000;
    private boolean compressionEnabled = true;
    private int maxRetries = 3;
    private long retryDelayMs = 1000;
}
