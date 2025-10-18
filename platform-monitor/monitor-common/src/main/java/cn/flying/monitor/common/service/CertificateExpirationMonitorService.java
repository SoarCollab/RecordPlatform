package cn.flying.monitor.common.service;

import cn.flying.monitor.common.security.CertificateAuthenticationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 证书过期监控服务
 */
@Slf4j
@Service
public class CertificateExpirationMonitorService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final NotificationService notificationService;
    
    private static final String CLIENT_CERT_PREFIX = "client:cert:";
    private static final String CERT_EXPIRY_ALERT_PREFIX = "cert:expiry:alert:";
    
    // 证书过期提醒阈值（天）
    private static final int[] EXPIRY_WARNING_DAYS = {30, 14, 7, 3, 1};
    
    public CertificateExpirationMonitorService(RedisTemplate<String, Object> redisTemplate,
                                             NotificationService notificationService) {
        this.redisTemplate = redisTemplate;
        this.notificationService = notificationService;
    }

    /**
     * 定期检查证书过期情况
     * 每天凌晨2点执行
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void checkCertificateExpiration() {
        log.info("Starting certificate expiration check");
        
        try {
            Set<String> certKeys = redisTemplate.keys(CLIENT_CERT_PREFIX + "*");
            if (certKeys == null || certKeys.isEmpty()) {
                log.info("No certificates found for expiration check");
                return;
            }
            
            int checkedCount = 0;
            int alertsSent = 0;
            
            for (String key : certKeys) {
                try {
                    CertificateAuthenticationService.ClientCertificateInfo certInfo = 
                        (CertificateAuthenticationService.ClientCertificateInfo) redisTemplate.opsForValue().get(key);
                    
                    if (certInfo != null && certInfo.isActive()) {
                        checkedCount++;
                        if (checkAndSendExpirationAlert(certInfo)) {
                            alertsSent++;
                        }
                    }
                } catch (Exception e) {
                    log.error("Error checking certificate expiration for key: {}", key, e);
                }
            }
            
            log.info("Certificate expiration check completed. Checked: {}, Alerts sent: {}", 
                    checkedCount, alertsSent);
            
        } catch (Exception e) {
            log.error("Error during certificate expiration check", e);
        }
    }

    /**
     * 检查单个证书并发送过期提醒
     */
    private boolean checkAndSendExpirationAlert(CertificateAuthenticationService.ClientCertificateInfo certInfo) {
        Instant now = Instant.now();
        Instant notAfter = certInfo.getNotAfter();
        
        if (notAfter.isBefore(now)) {
            // 证书已过期
            sendExpirationAlert(certInfo, 0, true);
            return true;
        }
        
        long daysUntilExpiry = ChronoUnit.DAYS.between(now, notAfter);
        
        // 检查是否需要发送提醒
        for (int warningDays : EXPIRY_WARNING_DAYS) {
            if (daysUntilExpiry <= warningDays && daysUntilExpiry > 0) {
                String alertKey = CERT_EXPIRY_ALERT_PREFIX + certInfo.getClientId() + ":" + warningDays;
                
                // 检查是否已经发送过此阶段的提醒
                if (!redisTemplate.hasKey(alertKey)) {
                    sendExpirationAlert(certInfo, (int) daysUntilExpiry, false);
                    
                    // 标记已发送提醒，24小时内不重复发送
                    redisTemplate.opsForValue().set(alertKey, true, Duration.ofHours(24));
                    return true;
                }
                break; // 只发送最接近的一个提醒
            }
        }
        
        return false;
    }

    /**
     * 发送证书过期提醒
     */
    private void sendExpirationAlert(CertificateAuthenticationService.ClientCertificateInfo certInfo, 
                                   int daysUntilExpiry, boolean isExpired) {
        try {
            Map<String, Object> variables = new HashMap<>();
            variables.put("clientId", certInfo.getClientId());
            variables.put("subject", certInfo.getSubject());
            variables.put("serialNumber", certInfo.getSerialNumber());
            variables.put("notAfter", certInfo.getNotAfter());
            variables.put("daysUntilExpiry", daysUntilExpiry);
            variables.put("isExpired", isExpired);
            
            String title;
            String content;
            
            if (isExpired) {
                title = "证书已过期告警 - " + certInfo.getClientId();
                content = String.format(
                    "客户端 %s 的证书已过期！\n" +
                    "证书主题: %s\n" +
                    "序列号: %s\n" +
                    "过期时间: %s\n" +
                    "请立即更新证书以确保服务正常运行。",
                    certInfo.getClientId(),
                    certInfo.getSubject(),
                    certInfo.getSerialNumber(),
                    certInfo.getNotAfter()
                );
            } else {
                title = String.format("证书即将过期提醒 - %s (%d天)", certInfo.getClientId(), daysUntilExpiry);
                content = String.format(
                    "客户端 %s 的证书将在 %d 天后过期。\n" +
                    "证书主题: %s\n" +
                    "序列号: %s\n" +
                    "过期时间: %s\n" +
                    "请及时更新证书以避免服务中断。",
                    certInfo.getClientId(),
                    daysUntilExpiry,
                    certInfo.getSubject(),
                    certInfo.getSerialNumber(),
                    certInfo.getNotAfter()
                );
            }
            
            // 发送邮件通知（假设有默认的管理员邮箱配置）
            Map<String, Object> config = new HashMap<>();
            config.put("priority", isExpired ? "HIGH" : "MEDIUM");
            
            notificationService.sendEmailNotification("admin@monitor.system", title, content, config);
            
            log.info("Certificate expiration alert sent for client: {}, days until expiry: {}, expired: {}", 
                    certInfo.getClientId(), daysUntilExpiry, isExpired);
            
        } catch (Exception e) {
            log.error("Failed to send certificate expiration alert for client: {}", 
                    certInfo.getClientId(), e);
        }
    }

    /**
     * 手动检查指定客户端的证书过期情况
     */
    public void checkClientCertificateExpiration(String clientId) {
        try {
            CertificateAuthenticationService.ClientCertificateInfo certInfo = 
                (CertificateAuthenticationService.ClientCertificateInfo) redisTemplate.opsForValue()
                    .get(CLIENT_CERT_PREFIX + clientId);
            
            if (certInfo != null && certInfo.isActive()) {
                checkAndSendExpirationAlert(certInfo);
                log.info("Manual certificate expiration check completed for client: {}", clientId);
            } else {
                log.warn("No active certificate found for client: {}", clientId);
            }
        } catch (Exception e) {
            log.error("Error during manual certificate expiration check for client: {}", clientId, e);
        }
    }

    /**
     * 获取即将过期的证书列表
     */
    public Map<String, Integer> getExpiringCertificates(int withinDays) {
        Map<String, Integer> expiringCerts = new HashMap<>();
        
        try {
            Set<String> certKeys = redisTemplate.keys(CLIENT_CERT_PREFIX + "*");
            if (certKeys == null) {
                return expiringCerts;
            }
            
            Instant now = Instant.now();
            Instant threshold = now.plus(withinDays, ChronoUnit.DAYS);
            
            for (String key : certKeys) {
                try {
                    CertificateAuthenticationService.ClientCertificateInfo certInfo = 
                        (CertificateAuthenticationService.ClientCertificateInfo) redisTemplate.opsForValue().get(key);
                    
                    if (certInfo != null && certInfo.isActive()) {
                        Instant notAfter = certInfo.getNotAfter();
                        if (notAfter.isBefore(threshold)) {
                            long daysUntilExpiry = ChronoUnit.DAYS.between(now, notAfter);
                            expiringCerts.put(certInfo.getClientId(), (int) daysUntilExpiry);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error checking certificate for key: {}", key, e);
                }
            }
            
        } catch (Exception e) {
            log.error("Error getting expiring certificates", e);
        }
        
        return expiringCerts;
    }
}