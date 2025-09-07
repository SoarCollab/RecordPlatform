package cn.flying.identity.event;

import cn.flying.identity.dto.AuditLog;
import cn.flying.identity.dto.TokenMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 审计事件发布器
 * 用于发布审计日志和Token监控事件
 *
 * @author flying
 * @date 2024
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 发布审计日志事件
     *
     * @param auditLog 审计日志
     */
    public void publishAuditLogEvent(AuditLog auditLog) {
        try {
            AuditLogEvent event = new AuditLogEvent(auditLog);
            eventPublisher.publishEvent(event);
            log.debug("发布审计日志事件: {}", auditLog.getId());
        } catch (Exception e) {
            log.error("发布审计日志事件失败", e);
        }
    }

    /**
     * 发布审计日志事件
     *
     * @param auditLog  审计日志
     * @param eventType 事件类型
     */
    public void publishAuditLogEvent(AuditLog auditLog, String eventType) {
        try {
            AuditLogEvent event = new AuditLogEvent(auditLog, eventType);
            eventPublisher.publishEvent(event);
            log.debug("发布审计日志事件: {}, 类型: {}", auditLog.getId(), eventType);
        } catch (Exception e) {
            log.error("发布审计日志事件失败", e);
        }
    }

    /**
     * 发布Token监控事件
     *
     * @param tokenMonitor Token监控记录
     */
    public void publishTokenMonitorEvent(TokenMonitor tokenMonitor) {
        try {
            TokenMonitorEvent event = new TokenMonitorEvent(tokenMonitor);
            eventPublisher.publishEvent(event);
            log.debug("发布Token监控事件: {}", tokenMonitor.getId());
        } catch (Exception e) {
            log.error("发布Token监控事件失败", e);
        }
    }

    /**
     * 发布Token监控事件
     *
     * @param tokenMonitor Token监控记录
     * @param eventType    事件类型
     */
    public void publishTokenMonitorEvent(TokenMonitor tokenMonitor, String eventType) {
        try {
            TokenMonitorEvent event = new TokenMonitorEvent(tokenMonitor, eventType);
            eventPublisher.publishEvent(event);
            log.debug("发布Token监控事件: {}, 类型: {}", tokenMonitor.getId(), eventType);
        } catch (Exception e) {
            log.error("发布Token监控事件失败", e);
        }
    }

    /**
     * 发布登录事件
     *
     * @param userId    用户ID
     * @param clientIp  客户端IP
     * @param userAgent 用户代理
     * @param success   是否成功
     */
    public void publishLoginEvent(String userId, String clientIp, String userAgent, boolean success) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(Long.valueOf(userId));
        auditLog.setOperationType("LOGIN");
        auditLog.setModule("AUTH");
        auditLog.setOperationDesc(success ? "用户登录成功" : "用户登录失败");
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        auditLog.setIsSuccess(success);

        publishAuditLogEvent(auditLog, "LOGIN");
    }

    /**
     * 发布登出事件
     *
     * @param userId    用户ID
     * @param clientIp  客户端IP
     * @param userAgent 用户代理
     */
    public void publishLogoutEvent(String userId, String clientIp, String userAgent) {
        AuditLog auditLog = new AuditLog();
        auditLog.setUserId(Long.valueOf(userId));
        auditLog.setOperationType("LOGOUT");
        auditLog.setModule("AUTH");
        auditLog.setOperationDesc("用户登出");
        auditLog.setClientIp(clientIp);
        auditLog.setUserAgent(userAgent);
        auditLog.setIsSuccess(true);

        publishAuditLogEvent(auditLog, "LOGOUT");
    }

    /**
     * 发布Token创建事件
     *
     * @param tokenId   Token ID
     * @param tokenType Token类型
     * @param userId    用户ID
     * @param clientId  客户端ID
     * @param clientIp  客户端IP
     * @param userAgent 用户代理
     */
    public void publishTokenCreatedEvent(String tokenId, String tokenType, String userId,
                                         String clientId, String clientIp, String userAgent) {
        TokenMonitor tokenMonitor = new TokenMonitor();
        tokenMonitor.setTokenId(tokenId);
        tokenMonitor.setTokenType(tokenType);
        tokenMonitor.setUserId(Long.valueOf(userId));
        tokenMonitor.setClientId(clientId);
        tokenMonitor.setEventType("CREATED");
        tokenMonitor.setEventDesc("Token创建");
        tokenMonitor.setClientIp(clientIp);
        tokenMonitor.setUserAgent(userAgent);
        tokenMonitor.setIsAbnormal(false);

        publishTokenMonitorEvent(tokenMonitor, "TOKEN_CREATED");
    }

    /**
     * 发布Token使用事件
     *
     * @param tokenId       Token ID
     * @param tokenType     Token类型
     * @param userId        用户ID
     * @param clientId      客户端ID
     * @param clientIp      客户端IP
     * @param userAgent     用户代理
     * @param requestUrl    请求URL
     * @param requestMethod 请求方法
     */
    public void publishTokenUsedEvent(String tokenId, String tokenType, String userId, String clientId,
                                      String clientIp, String userAgent, String requestUrl, String requestMethod) {
        TokenMonitor tokenMonitor = new TokenMonitor();
        tokenMonitor.setTokenId(tokenId);
        tokenMonitor.setTokenType(tokenType);
        tokenMonitor.setUserId(Long.valueOf(userId));
        tokenMonitor.setClientId(clientId);
        tokenMonitor.setEventType("USED");
        tokenMonitor.setEventDesc("Token使用");
        tokenMonitor.setClientIp(clientIp);
        tokenMonitor.setUserAgent(userAgent);
        tokenMonitor.setRequestUrl(requestUrl);
        tokenMonitor.setRequestMethod(requestMethod);
        tokenMonitor.setIsAbnormal(false);

        publishTokenMonitorEvent(tokenMonitor, "TOKEN_USED");
    }

    /**
     * 发布Token撤销事件
     *
     * @param tokenId   Token ID
     * @param tokenType Token类型
     * @param userId    用户ID
     * @param clientId  客户端ID
     * @param clientIp  客户端IP
     * @param reason    撤销原因
     */
    public void publishTokenRevokedEvent(String tokenId, String tokenType, String userId,
                                         String clientId, String clientIp, String reason) {
        TokenMonitor tokenMonitor = new TokenMonitor();
        tokenMonitor.setTokenId(tokenId);
        tokenMonitor.setTokenType(tokenType);
        tokenMonitor.setUserId(Long.valueOf(userId));
        tokenMonitor.setClientId(clientId);
        tokenMonitor.setEventType("REVOKED");
        tokenMonitor.setEventDesc("Token撤销: " + reason);
        tokenMonitor.setClientIp(clientIp);
        tokenMonitor.setIsAbnormal(false);

        publishTokenMonitorEvent(tokenMonitor, "TOKEN_REVOKED");
    }

    /**
     * 发布Token异常事件
     *
     * @param tokenId      Token ID
     * @param tokenType    Token类型
     * @param userId       用户ID
     * @param clientId     客户端ID
     * @param clientIp     客户端IP
     * @param abnormalType 异常类型
     * @param reason       异常原因
     */
    public void publishTokenAbnormalEvent(String tokenId, String tokenType, String userId, String clientId,
                                          String clientIp, String abnormalType, String reason) {
        TokenMonitor tokenMonitor = new TokenMonitor();
        tokenMonitor.setTokenId(tokenId);
        tokenMonitor.setTokenType(tokenType);
        tokenMonitor.setUserId(Long.valueOf(userId));
        tokenMonitor.setClientId(clientId);
        tokenMonitor.setEventType("ABNORMAL");
        tokenMonitor.setEventDesc("Token异常: " + reason);
        tokenMonitor.setClientIp(clientIp);
        tokenMonitor.setIsAbnormal(true);
        tokenMonitor.setAbnormalType(abnormalType);

        publishTokenMonitorEvent(tokenMonitor, "TOKEN_ABNORMAL");
    }
}