package cn.flying.common.event;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证文件存证事件始终携带可追踪的稳定 PREPARE 身份与上传上下文。
 */
class FileStorageEventTest {

    /**
     * 验证完整构造器和兼容访问器返回同一稳定 PREPARE 文件ID。
     */
    @Test
    void fullConstructorShouldExposeStablePreparedFileIdentity() {
        Object source = new Object();
        File part = new File("/tmp/contract.part");

        FileStorageEvent event = new FileStorageEvent(
                source,
                1L,
                100L,
                9527L,
                "contract.pdf",
                "session-1",
                "client-1",
                List.of(part),
                List.of("hash-1"),
                "{\"contentType\":\"application/pdf\"}");

        assertThat(event.getSource()).isSameAs(source);
        assertThat(event.getTenantId()).isEqualTo(1L);
        assertThat(event.getUid()).isEqualTo(100L);
        assertThat(event.getPreparedFileId()).isEqualTo(9527L);
        assertThat(event.getFileId()).isEqualTo(9527L);
        assertThat(event.getFileName()).isEqualTo("contract.pdf");
        assertThat(event.getSessionId()).isEqualTo("session-1");
        assertThat(event.getClientId()).isEqualTo("client-1");
        assertThat(event.getProcessedFiles()).containsExactly(part);
        assertThat(event.getFileHashes()).containsExactly("hash-1");
        assertThat(event.getFileParam())
                .isEqualTo("{\"contentType\":\"application/pdf\"}");
    }

    /**
     * 验证兼容构造器明确保留空 PREPARE 身份，供监听器失败关闭而非误认新记录。
     */
    @Test
    void compatibilityConstructorShouldLeavePreparedFileIdentityEmpty() {
        FileStorageEvent event = new FileStorageEvent(
                this,
                1L,
                100L,
                "contract.pdf",
                "session-1",
                "client-1",
                List.of(new File("/tmp/contract.part")),
                List.of("hash-1"),
                "{}");

        assertThat(event.getPreparedFileId()).isNull();
        assertThat(event.getFileId()).isNull();
    }
}
