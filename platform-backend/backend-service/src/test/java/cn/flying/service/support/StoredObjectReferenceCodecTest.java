package cn.flying.service.support;

import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for StoredObjectReferenceCodec.
 */
@DisplayName("StoredObjectReferenceCodec")
class StoredObjectReferenceCodecTest {

    /**
     * 验证直传链上内容按数组保存，重复密文哈希不会被覆盖。
     */
    @Test
    void shouldPreserveDuplicateCipherHashesInOrderedContent() {
        String content = StoredObjectReferenceCodec.toChainContent(List.of(
                new DirectMultipartCompletedPartVO(
                        0,
                        "s3://node-a/final-0",
                        512L,
                        "\"etag-0\"",
                        "sha256:plain-0",
                        "sha256:same",
                        "SHA-256"
                ),
                new DirectMultipartCompletedPartVO(
                        1,
                        "s3://node-a/final-1",
                        512L,
                        "\"etag-1\"",
                        "sha256:plain-1",
                        "sha256:same",
                        "SHA-256"
                )
        ));

        List<StoredObjectReference> references = StoredObjectReferenceCodec.parseChainContent(content);

        assertEquals(2, references.size());
        assertEquals("sha256:same", references.get(0).cipherHash());
        assertEquals("s3://node-a/final-0", references.get(0).storagePath());
        assertEquals("sha256:same", references.get(1).cipherHash());
        assertEquals("s3://node-a/final-1", references.get(1).storagePath());
    }
}
