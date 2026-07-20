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
    private Cache fileMetaCache;

    /**
     * 清理租户上下文，避免消息测试间污染。
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * 验证文件存储事件不再维护已移除的用户文件列表缓存。
     */
    @Test
    void shouldProcessStoredEventWithoutUserFilesCacheMaintenance() {
        FileEventRabbitListener listener = new FileEventRabbitListener(processedMessageMapper, cacheManager);
        when(processedMessageMapper.exists("msg-stored")).thenReturn(false);

        listener.handleFileStored(message(
                "msg-stored",
                8L,
                "{\"userId\":42,\"fileName\":\"a.pdf\",\"fileHash\":\"hash-a\",\"transactionHash\":\"tx-a\"}"
        ));

        verify(cacheManager, never()).getCache("userFiles");
        assertProcessedEvent("file.stored");
    }

    /**
     * 验证文件删除事件只清理仍在使用的文件元数据缓存。
     */
    @Test
    void shouldEvictOnlyFileMetadataCacheForDeletedEvent() {
        FileEventRabbitListener listener = new FileEventRabbitListener(processedMessageMapper, cacheManager);
        when(processedMessageMapper.exists("msg-deleted")).thenReturn(false);
        when(cacheManager.getCache("fileMeta")).thenReturn(fileMetaCache);

        listener.handleFileDeleted(message(
                "msg-deleted",
                9L,
                "{\"userId\":43,\"fileHash\":\"hash-b\"}"
        ));

        verify(cacheManager, never()).getCache("userFiles");
        verify(fileMetaCache).evict("hash-b");
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
