package cn.flying.service.key;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.entity.FileKeyEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("LocalKeyWrappingService")
class LocalKeyWrappingServiceTest {

    private FileKeyEnvelopeProperties properties;
    private LocalKeyWrappingService wrappingService;

    @BeforeEach
    void setUp() {
        properties = new FileKeyEnvelopeProperties();
        properties.setLocalMasterKey("test-master-key-with-enough-entropy");
        wrappingService = new LocalKeyWrappingService(properties);
    }

    /**
     * Verifies that wrapping and unwrapping preserves the serialized file data key.
     */
    @Test
    @DisplayName("should wrap and unwrap serialized data key")
    void shouldWrapAndUnwrapSerializedDataKey() {
        byte[] aad = "tenant|file|hash|OWNER|100|1|suite".getBytes();

        WrappedDataKey wrapped = wrappingService.wrap("base64-initial-key", aad);
        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv());

        assertNotEquals("base64-initial-key", wrapped.encryptedDataKey());
        assertEquals("base64-initial-key", wrappingService.unwrap(envelope, aad));
    }

    /**
     * Verifies that AES-GCM AAD binds the envelope to its file context.
     */
    @Test
    @DisplayName("should reject unwrap when aad is tampered")
    void shouldRejectUnwrapWhenAadIsTampered() {
        WrappedDataKey wrapped = wrappingService.wrap("base64-initial-key", "aad-1".getBytes());
        FileKeyEnvelope envelope = new FileKeyEnvelope()
                .setEncryptedDataKey(wrapped.encryptedDataKey())
                .setWrappingIv(wrapped.wrappingIv());

        assertThrows(GeneralException.class, () -> wrappingService.unwrap(envelope, "aad-2".getBytes()));
    }
}
