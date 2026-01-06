package cn.flying.service.listener;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.Const;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.entity.ProcessedMessage;
import cn.flying.dao.mapper.ProcessedMessageMapper;
import cn.flying.service.outbox.OutboxPublisher;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * RabbitMQ listener for file events.
 * Ensures idempotent processing using processed_message table.
 * 支持分布式追踪，从消息 header 恢复 traceId 到 MDC。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileEventRabbitListener {

    private final ProcessedMessageMapper processedMessageMapper;
    private final CacheManager cacheManager;

    @RabbitListener(queues = "file.stored.queue")
    @Transactional
    public void handleFileStored(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        // 从消息 header 恢复 traceId 和 tenantId
        restoreContext(message);

        log.debug("Received file.stored event: messageId={}", messageId);

        if (isAlreadyProcessed(messageId)) {
            log.info("Message already processed, skipping: messageId={}", messageId);
            return;
        }

        try {
            Map<String, Object> event = JsonConverter.parse(payload, new TypeReference<>() {});
            processFileStoredEvent(event);
            markAsProcessed(messageId, "file.stored");
            log.info("File stored event processed: messageId={}", messageId);
        } catch (Exception ex) {
            log.error("Failed to process file.stored event: messageId={}", messageId, ex);
            throw new AmqpRejectAndDontRequeueException("Processing failed", ex);
        } finally {
            clearContext();
        }
    }

    @RabbitListener(queues = "file.deleted.queue")
    @Transactional
    public void handleFileDeleted(Message message) {
        String messageId = message.getMessageProperties().getMessageId();
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);

        // 从消息 header 恢复 traceId 和 tenantId
        restoreContext(message);

        log.debug("Received file.deleted event: messageId={}", messageId);

        if (isAlreadyProcessed(messageId)) {
            log.info("Message already processed, skipping: messageId={}", messageId);
            return;
        }

        try {
            Map<String, Object> event = JsonConverter.parse(payload, new TypeReference<>() {});
            processFileDeletedEvent(event);
            markAsProcessed(messageId, "file.deleted");
            log.info("File deleted event processed: messageId={}", messageId);
        } catch (Exception ex) {
            log.error("Failed to process file.deleted event: messageId={}", messageId, ex);
            throw new AmqpRejectAndDontRequeueException("Processing failed", ex);
        } finally {
            clearContext();
        }
    }

    /**
     * 从消息 header 恢复 traceId 和 tenantId
     */
    private void restoreContext(Message message) {
        // 恢复 traceId 到 MDC
        Object traceIdHeader = message.getMessageProperties().getHeader(OutboxPublisher.HEADER_TRACE_ID);
        if (traceIdHeader != null) {
            MDC.put(Const.TRACE_ID, traceIdHeader.toString());
        }

        // 恢复 tenantId 到 TenantContext
        Object tenantIdHeader = message.getMessageProperties().getHeader(OutboxPublisher.HEADER_TENANT_ID);
        if (tenantIdHeader != null) {
            TenantContext.setTenantId(((Number) tenantIdHeader).longValue());
        }
    }

    /**
     * 清理 MDC 和 TenantContext
     */
    private void clearContext() {
        MDC.remove(Const.TRACE_ID);
        TenantContext.clear();
    }

    private boolean isAlreadyProcessed(String messageId) {
        return processedMessageMapper.exists(messageId);
    }

    private void markAsProcessed(String messageId, String eventType) {
        ProcessedMessage pm = new ProcessedMessage();
        pm.setMessageId(messageId);
        pm.setEventType(eventType);
        pm.setProcessedAt(new Date());
        processedMessageMapper.insert(pm);
    }

    private void processFileStoredEvent(Map<String, Object> event) {
        Object userIdObj = event.get("userId");
        if (!(userIdObj instanceof Number)) {
            log.warn("Invalid or missing userId in file.stored event: {}", userIdObj);
            return;
        }
        Long userId = ((Number) userIdObj).longValue();
        String fileName = (String) event.get("fileName");
        String fileHash = (String) event.get("fileHash");
        String transactionHash = (String) event.get("transactionHash");

        log.info("Processing file stored: userId={}, fileName={}, fileHash={}, txHash={}",
                userId, fileName, fileHash, transactionHash);

        // 清理用户文件列表缓存，确保下次查询获取最新数据
        evictUserFilesCache(userId);
    }

    private void processFileDeletedEvent(Map<String, Object> event) {
        Object userIdObj = event.get("userId");
        if (!(userIdObj instanceof Number)) {
            log.warn("Invalid or missing userId in file.deleted event: {}", userIdObj);
            return;
        }
        Long userId = ((Number) userIdObj).longValue();
        String fileHash = (String) event.get("fileHash");

        log.info("Processing file deleted: userId={}, fileHash={}", userId, fileHash);

        // 清理用户文件列表缓存
        evictUserFilesCache(userId);
        // 清理文件元数据缓存
        evictFileMetaCache(fileHash);
    }

    /**
     * 清理用户文件列表缓存
     */
    private void evictUserFilesCache(Long userId) {
        var cache = cacheManager.getCache("userFiles");
        if (cache != null) {
            cache.evict(userId);
            log.debug("Evicted userFiles cache for userId={}", userId);
        }
    }

    /**
     * 清理文件元数据缓存
     */
    private void evictFileMetaCache(String fileHash) {
        var cache = cacheManager.getCache("fileMeta");
        if (cache != null) {
            cache.evict(fileHash);
            log.debug("Evicted fileMeta cache for fileHash={}", fileHash);
        }
    }
}
