package cn.flying.monitor.common.security;

import cn.flying.monitor.common.exception.CertificateValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 证书认证服务
 */
@Slf4j
@Service
public class CertificateAuthenticationService {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final String CLIENT_CERT_PREFIX = "client:cert:";
    private static final String CERT_BLACKLIST_PREFIX = "cert:blacklist:";

    public CertificateAuthenticationService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 注册客户端证书
     */
    public void registerClientCertificate(String clientId, String certificatePem, String description) {
        // 输入验证
        validateInputParameters(clientId, certificatePem, "registerClientCertificate");
        
        try {
            X509Certificate cert = parseCertificate(certificatePem);
            
            // 全面的证书验证
            validateCertificateComprehensive(cert, clientId);
            
            // 检查是否已存在相同序列号的证书
            String serialNumber = cert.getSerialNumber().toString();
            if (isCertificateSerialNumberExists(serialNumber)) {
                Map<String, Object> context = new HashMap<>();
                context.put("clientId", clientId);
                context.put("serialNumber", serialNumber);
                throw new CertificateValidationException(
                    "Certificate with serial number already exists", 
                    "CERT_SERIAL_EXISTS", 
                    context
                );
            }
            
            ClientCertificateInfo certInfo = ClientCertificateInfo.builder()
                    .clientId(clientId)
                    .certificatePem(certificatePem)
                    .serialNumber(serialNumber)
                    .subject(cert.getSubjectX500Principal().getName())
                    .issuer(cert.getIssuerX500Principal().getName())
                    .notBefore(cert.getNotBefore().toInstant())
                    .notAfter(cert.getNotAfter().toInstant())
                    .description(description != null ? description : "")
                    .active(true)
                    .registeredAt(Instant.now())
                    .build();
            
            // 使用事务保存证书信息
            redisTemplate.opsForValue().set(CLIENT_CERT_PREFIX + clientId, certInfo);
            
            // 记录证书注册事件
            logSecurityEvent("CERT_REGISTERED", clientId, serialNumber, null);
            
            log.info("Client certificate registered successfully for client: {}, serial: {}, expires: {}", 
                    clientId, cert.getSerialNumber(), cert.getNotAfter());
            
        } catch (CertificateValidationException e) {
            log.error("Certificate validation failed for client: {}", clientId, e);
            throw e;
        } catch (Exception e) {
            log.error("Failed to register client certificate for client: {}", clientId, e);
            Map<String, Object> context = new HashMap<>();
            context.put("clientId", clientId);
            throw new CertificateValidationException(
                "Certificate registration failed: " + e.getMessage(), 
                "CERT_REGISTRATION_FAILED", 
                context, 
                e
            );
        }
    }

    /**
     * 验证客户端证书
     */
    public boolean validateClientCertificate(String clientId, String certificatePem) {
        // 输入验证
        try {
            validateInputParameters(clientId, certificatePem, "validateClientCertificate");
        } catch (CertificateValidationException e) {
            log.warn("Invalid input parameters for certificate validation: {}", e.getMessage());
            return false;
        }
        
        try {
            ClientCertificateInfo storedCert = (ClientCertificateInfo) redisTemplate.opsForValue()
                    .get(CLIENT_CERT_PREFIX + clientId);
            
            if (storedCert == null) {
                log.warn("No certificate found for client: {}", clientId);
                logSecurityEvent("CERT_NOT_FOUND", clientId, null, "Certificate not registered");
                return false;
            }
            
            if (!storedCert.isActive()) {
                log.warn("Certificate is inactive for client: {}", clientId);
                logSecurityEvent("CERT_INACTIVE", clientId, storedCert.getSerialNumber(), "Certificate is inactive");
                return false;
            }
            
            // 检查证书是否在黑名单中
            if (isCertificateBlacklisted(storedCert.getSerialNumber())) {
                log.warn("Certificate is blacklisted for client: {}, serial: {}", 
                        clientId, storedCert.getSerialNumber());
                logSecurityEvent("CERT_BLACKLISTED", clientId, storedCert.getSerialNumber(), "Certificate is blacklisted");
                return false;
            }
            
            X509Certificate providedCert = parseCertificate(certificatePem);
            X509Certificate storedX509Cert = parseCertificate(storedCert.getCertificatePem());
            
            // 验证证书有效期
            try {
                providedCert.checkValidity();
            } catch (CertificateExpiredException e) {
                log.warn("Certificate expired for client: {}, serial: {}", clientId, providedCert.getSerialNumber());
                logSecurityEvent("CERT_EXPIRED", clientId, providedCert.getSerialNumber().toString(), "Certificate expired");
                return false;
            } catch (CertificateNotYetValidException e) {
                log.warn("Certificate not yet valid for client: {}, serial: {}", clientId, providedCert.getSerialNumber());
                logSecurityEvent("CERT_NOT_YET_VALID", clientId, providedCert.getSerialNumber().toString(), "Certificate not yet valid");
                return false;
            }
            
            // 验证证书是否匹配
            boolean serialMatches = providedCert.getSerialNumber().equals(storedX509Cert.getSerialNumber());
            boolean subjectMatches = providedCert.getSubjectX500Principal().equals(storedX509Cert.getSubjectX500Principal());
            boolean issuerMatches = providedCert.getIssuerX500Principal().equals(storedX509Cert.getIssuerX500Principal());
            
            if (serialMatches && subjectMatches && issuerMatches) {
                log.info("Certificate validation successful for client: {}", clientId);
                logSecurityEvent("CERT_VALIDATION_SUCCESS", clientId, storedCert.getSerialNumber(), "Certificate validation successful");
                
                // 更新最后验证时间
                updateLastValidationTime(clientId);
                return true;
            } else {
                log.warn("Certificate validation failed for client: {} - serial match: {}, subject match: {}, issuer match: {}", 
                        clientId, serialMatches, subjectMatches, issuerMatches);
                logSecurityEvent("CERT_VALIDATION_FAILED", clientId, providedCert.getSerialNumber().toString(), 
                    "Certificate details do not match stored certificate");
                return false;
            }
            
        } catch (CertificateException e) {
            log.error("Certificate parsing error for client: {}", clientId, e);
            logSecurityEvent("CERT_PARSING_ERROR", clientId, null, "Certificate parsing failed: " + e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Unexpected error validating certificate for client: {}", clientId, e);
            logSecurityEvent("CERT_VALIDATION_ERROR", clientId, null, "Unexpected validation error: " + e.getMessage());
            return false;
        }
    }

    /**
     * 吊销客户端证书
     */
    public void revokeCertificate(String clientId, String reason) {
        if (!StringUtils.hasText(clientId)) {
            throw new CertificateValidationException("Client ID cannot be null or empty", "INVALID_CLIENT_ID");
        }
        
        if (!StringUtils.hasText(reason)) {
            throw new CertificateValidationException("Revocation reason cannot be null or empty", "INVALID_REASON");
        }
        
        try {
            ClientCertificateInfo certInfo = (ClientCertificateInfo) redisTemplate.opsForValue()
                    .get(CLIENT_CERT_PREFIX + clientId);
            
            if (certInfo == null) {
                log.warn("Attempted to revoke non-existent certificate for client: {}", clientId);
                throw new CertificateValidationException("Certificate not found for client: " + clientId, "CERT_NOT_FOUND");
            }
            
            if (!certInfo.isActive()) {
                log.warn("Attempted to revoke already inactive certificate for client: {}", clientId);
                throw new CertificateValidationException("Certificate is already inactive for client: " + clientId, "CERT_ALREADY_INACTIVE");
            }
            
            // 添加到黑名单
            addToBlacklist(certInfo.getSerialNumber(), reason);
            
            // 标记为非活跃并记录吊销信息
            certInfo.setActive(false);
            certInfo.setRevokedAt(Instant.now());
            certInfo.setRevocationReason(reason);
            redisTemplate.opsForValue().set(CLIENT_CERT_PREFIX + clientId, certInfo);
            
            // 记录安全事件
            logSecurityEvent("CERT_REVOKED", clientId, certInfo.getSerialNumber(), reason);
            
            log.info("Certificate revoked successfully for client: {}, serial: {}, reason: {}", 
                    clientId, certInfo.getSerialNumber(), reason);
            
        } catch (CertificateValidationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error revoking certificate for client: {}", clientId, e);
            throw new CertificateValidationException("Failed to revoke certificate: " + e.getMessage(), "CERT_REVOCATION_FAILED", e);
        }
    }

    /**
     * 添加证书到黑名单
     */
    public void addToBlacklist(String serialNumber, String reason) {
        CertificateBlacklistEntry entry = CertificateBlacklistEntry.builder()
                .serialNumber(serialNumber)
                .reason(reason)
                .blacklistedAt(java.time.Instant.now())
                .build();
        
        redisTemplate.opsForValue().set(CERT_BLACKLIST_PREFIX + serialNumber, entry, Duration.ofDays(365));
        log.info("Certificate added to blacklist: {}, reason: {}", serialNumber, reason);
    }

    /**
     * 检查证书是否在黑名单中
     */
    public boolean isCertificateBlacklisted(String serialNumber) {
        if (!StringUtils.hasText(serialNumber)) {
            return false;
        }
        return redisTemplate.hasKey(CERT_BLACKLIST_PREFIX + serialNumber);
    }

    /**
     * 获取客户端证书信息
     */
    public ClientCertificateInfo getClientCertificateInfo(String clientId) {
        if (!StringUtils.hasText(clientId)) {
            throw new CertificateValidationException("Client ID cannot be null or empty", "INVALID_CLIENT_ID");
        }
        
        return (ClientCertificateInfo) redisTemplate.opsForValue().get(CLIENT_CERT_PREFIX + clientId);
    }

    /**
     * 获取所有活跃的客户端证书
     */
    public Map<String, ClientCertificateInfo> getAllActiveCertificates() {
        Map<String, ClientCertificateInfo> activeCerts = new HashMap<>();
        
        try {
            Set<String> keys = redisTemplate.keys(CLIENT_CERT_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    ClientCertificateInfo certInfo = (ClientCertificateInfo) redisTemplate.opsForValue().get(key);
                    if (certInfo != null && certInfo.isActive()) {
                        activeCerts.put(certInfo.getClientId(), certInfo);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error retrieving active certificates", e);
        }
        
        return activeCerts;
    }

    /**
     * 检查证书序列号是否已存在
     */
    private boolean isCertificateSerialNumberExists(String serialNumber) {
        try {
            Set<String> keys = redisTemplate.keys(CLIENT_CERT_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    ClientCertificateInfo certInfo = (ClientCertificateInfo) redisTemplate.opsForValue().get(key);
                    if (certInfo != null && serialNumber.equals(certInfo.getSerialNumber())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error checking certificate serial number existence", e);
        }
        return false;
    }

    /**
     * 输入参数验证
     */
    private void validateInputParameters(String clientId, String certificatePem, String operation) {
        if (!StringUtils.hasText(clientId)) {
            throw new CertificateValidationException("Client ID cannot be null or empty", "INVALID_CLIENT_ID");
        }
        
        if (!StringUtils.hasText(certificatePem)) {
            throw new CertificateValidationException("Certificate PEM cannot be null or empty", "INVALID_CERTIFICATE_PEM");
        }
        
        // 基本的PEM格式检查
        if (!certificatePem.contains("-----BEGIN CERTIFICATE-----") || 
            !certificatePem.contains("-----END CERTIFICATE-----")) {
            throw new CertificateValidationException("Invalid PEM format", "INVALID_PEM_FORMAT");
        }
    }

    /**
     * 全面的证书验证
     */
    private void validateCertificateComprehensive(X509Certificate cert, String clientId) throws CertificateValidationException {
        try {
            // 检查证书有效期
            cert.checkValidity();
            
            // 检查证书是否即将过期（30天内）
            Instant now = Instant.now();
            Instant notAfter = cert.getNotAfter().toInstant();
            long daysUntilExpiry = ChronoUnit.DAYS.between(now, notAfter);
            
            if (daysUntilExpiry <= 30) {
                log.warn("Certificate for client {} will expire in {} days", clientId, daysUntilExpiry);
            }
            
            // 验证证书基本信息
            if (cert.getSerialNumber() == null) {
                throw new CertificateValidationException("Certificate serial number is null", "INVALID_SERIAL_NUMBER");
            }
            
            if (cert.getSubjectX500Principal() == null) {
                throw new CertificateValidationException("Certificate subject is null", "INVALID_SUBJECT");
            }
            
            if (cert.getIssuerX500Principal() == null) {
                throw new CertificateValidationException("Certificate issuer is null", "INVALID_ISSUER");
            }
            
        } catch (CertificateExpiredException e) {
            throw new CertificateValidationException("Certificate has expired", "CERT_EXPIRED", e);
        } catch (CertificateNotYetValidException e) {
            throw new CertificateValidationException("Certificate is not yet valid", "CERT_NOT_YET_VALID", e);
        }
    }

    /**
     * 更新最后验证时间
     */
    private void updateLastValidationTime(String clientId) {
        try {
            ClientCertificateInfo certInfo = (ClientCertificateInfo) redisTemplate.opsForValue()
                    .get(CLIENT_CERT_PREFIX + clientId);
            if (certInfo != null) {
                certInfo.setLastValidatedAt(Instant.now());
                redisTemplate.opsForValue().set(CLIENT_CERT_PREFIX + clientId, certInfo);
            }
        } catch (Exception e) {
            log.error("Error updating last validation time for client: {}", clientId, e);
        }
    }

    /**
     * 记录安全事件
     */
    private void logSecurityEvent(String eventType, String clientId, String serialNumber, String details) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("eventType", eventType);
            event.put("clientId", clientId);
            event.put("serialNumber", serialNumber);
            event.put("details", details);
            event.put("timestamp", Instant.now());
            
            // 存储安全事件到Redis（可选择持久化到数据库）
            String eventKey = "security:event:" + eventType + ":" + clientId + ":" + System.currentTimeMillis();
            redisTemplate.opsForValue().set(eventKey, event, Duration.ofDays(30));
            
            log.info("Security event logged: {} for client: {}", eventType, clientId);
        } catch (Exception e) {
            log.error("Error logging security event", e);
        }
    }

    /**
     * 解析证书
     */
    private X509Certificate parseCertificate(String certificatePem) throws CertificateException {
        // 移除PEM头尾标记
        String certData = certificatePem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
        
        byte[] certBytes = Base64.getDecoder().decode(certData);
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(certBytes));
    }

    /**
     * 客户端证书信息
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ClientCertificateInfo {
        private String clientId;
        private String certificatePem;
        private String serialNumber;
        private String subject;
        private String issuer;
        private java.time.Instant notBefore;
        private java.time.Instant notAfter;
        private String description;
        private boolean active;
        private java.time.Instant registeredAt;
        private java.time.Instant lastValidatedAt;
        private java.time.Instant revokedAt;
        private String revocationReason;
    }

    /**
     * 证书黑名单条目
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class CertificateBlacklistEntry {
        private String serialNumber;
        private String reason;
        private java.time.Instant blacklistedAt;
    }
}