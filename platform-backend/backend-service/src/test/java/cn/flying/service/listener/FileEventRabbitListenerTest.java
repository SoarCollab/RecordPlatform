package cn.flying.service.listener;

import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.entity.ProcessedMessage;
import cn.flying.dao.mapper.ProcessedMessageMapper;
import cn.flying.service.outbox.OutboxPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FileEventRabbitListener 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FileEventRabbitListenerTest {

    @Mock
    private ProcessedMessageMapper processedMessageMapper;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache userFilesCache;

    /**
     * 清理租户上下文，避免消息测试间污染。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证文件存储事件按消息租户驱逐 tenantId:userId 缓存。
     */
    @Test
    void shouldEvictTenantScopedUserFilesCacheForStoredEvent() {
        FileEventRabbitListener listener = new FileEventRabbitListener(processedMessageMapper, cacheManager);
        when(processedMessageMapper.exists("msg-stored")).thenReturn(false);
        when(cacheManager.getCache("userFiles")).thenReturn(userFilesCache);

        listener.handleFileStored(message(
                "msg-stored",
                8L,
                "{\"userId\":42,\"fileName\":\"a.pdf\",\"fileHash\":\"hash-a\",\"transactionHash\":\"tx-a\"}"
        ));

        verify(userFilesCache).evict("8:42");
        verify(userFilesCache, never()).evict(42L);
        assertProcessedEvent("file.stored");
    }

    /**
     * 验证文件删除事件按消息租户驱逐 tenantId:userId 缓存。
     */
    @Test
    void shouldEvictTenantScopedUserFilesCacheForDeletedEvent() {
        FileEventRabbitListener listener = new FileEventRabbitListener(processedMessageMapper, cacheManager);
        when(processedMessageMapper.exists("msg-deleted")).thenReturn(false);
        when(cacheManager.getCache("userFiles")).thenReturn(userFilesCache);

        listener.handleFileDeleted(message(
                "msg-deleted",
                9L,
                "{\"userId\":43,\"fileHash\":\"hash-b\"}"
        ));

        verify(userFilesCache).evict("9:43");
        verify(userFilesCache, never()).evict(43L);
        assertProcessedEvent("file.deleted");
    }

    /**
     * 构造带租户上下文 header 的 RabbitMQ 消息。
     */
    private Message message(String messageId, Long tenantId, String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setHeader(OutboxPublisher.HEADER_TENANT_ID, tenantId);
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }

    /**
     * 验证消息处理记录已落库。
     */
    private void assertProcessedEvent(String eventType) {
        ArgumentCaptor<ProcessedMessage> captor = ArgumentCaptor.forClass(ProcessedMessage.class);
        verify(processedMessageMapper).insert(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo(eventType);
    }
}
