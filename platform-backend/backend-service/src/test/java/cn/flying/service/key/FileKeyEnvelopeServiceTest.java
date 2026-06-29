package cn.flying.service.key;

import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileKeyEnvelope;
import cn.flying.dao.mapper.FileKeyEnvelopeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FileKeyEnvelopeService")
@ExtendWith(MockitoExtension.class)
class FileKeyEnvelopeServiceTest {

    @Mock
    private FileKeyEnvelopeMapper fileKeyEnvelopeMapper;

    private FileKeyEnvelopeService envelopeService;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, FileKeyEnvelope.class);
    }

    @BeforeEach
    void setUp() {
        FileKeyEnvelopeProperties properties = new FileKeyEnvelopeProperties();
        properties.setLocalMasterKey("test-master-key-with-enough-entropy");
        LocalKeyWrappingService wrappingService = new LocalKeyWrappingService(properties);
        envelopeService = new FileKeyEnvelopeService(fileKeyEnvelopeMapper, wrappingService, properties);
    }

    /**
     * Verifies that raw initialKey is removed before file_param persistence.
     */
    @Test
    @DisplayName("should sanitize file param and return envelope input")
    void shouldSanitizeFileParamAndReturnEnvelopeInput() {
        String fileParam = """
                {"fileName":"a.txt","fileSize":10,"initialKey":"serialized-key"}
                """;

        FileParamEnvelopeResult result = envelopeService.prepareFileParam(fileParam);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = JsonConverter.parse(result.sanitizedFileParam(), Map.class);

        assertTrue(result.requiresEnvelope());
        assertEquals("serialized-key", result.initialKey());
        assertFalse(sanitized.containsKey("initialKey"));
        assertEquals("ENVELOPED", sanitized.get("keyEnvelopeStatus"));
        assertEquals("RP-AES256-GCM-CHUNK-CHAIN-V1", sanitized.get("algorithmSuite"));
        assertEquals(1, ((Number) sanitized.get("keyVersion")).intValue());
    }

    /**
     * Verifies that unencrypted direct-upload metadata does not create an envelope.
     */
    @Test
    @DisplayName("should skip envelope for direct multipart none encryption")
    void shouldSkipEnvelopeForDirectMultipartNoneEncryption() {
        String fileParam = """
                {"uploadMode":"DIRECT_MULTIPART","encryptionAlgorithm":"NONE","fileName":"a.txt","initialKey":"stale-key"}
                """;

        FileParamEnvelopeResult result = envelopeService.prepareFileParam(fileParam);
        @SuppressWarnings("unchecked")
        Map<String, Object> sanitized = JsonConverter.parse(result.sanitizedFileParam(), Map.class);

        assertFalse(result.requiresEnvelope());
        assertFalse(sanitized.containsKey("initialKey"));
        assertEquals("NONE", sanitized.get("encryptionAlgorithm"));
    }

    /**
     * Verifies that an owner envelope can be saved and unwrapped later.
     */
    @Test
    @DisplayName("should save and unwrap owner envelope")
    void shouldSaveAndUnwrapOwnerEnvelope() {
        File file = new File()
                .setId(10L)
                .setTenantId(1L)
                .setUid(100L)
                .setFileHash("hash-1");
        FileParamEnvelopeResult result = envelopeService.prepareFileParam("""
                {"fileName":"a.txt","initialKey":"serialized-key"}
                """);
        ArgumentCaptor<FileKeyEnvelope> envelopeCaptor = ArgumentCaptor.forClass(FileKeyEnvelope.class);
        when(fileKeyEnvelopeMapper.insert(any(FileKeyEnvelope.class))).thenReturn(1);

        envelopeService.saveOwnerEnvelope(file, "hash-1", 100L, result);

        verify(fileKeyEnvelopeMapper).insert(envelopeCaptor.capture());
        FileKeyEnvelope envelope = envelopeCaptor.getValue();
        assertThat(envelope.getEncryptedDataKey()).isNotBlank();
        assertEquals(FileKeyEnvelopeService.STATUS_ACTIVE, envelope.getStatus());

        when(fileKeyEnvelopeMapper.selectOne(any())).thenReturn(envelope);
        Optional<String> unwrapped = envelopeService.unwrapActiveOwnerInitialKey(file, "hash-1", 100L);

        assertTrue(unwrapped.isPresent());
        assertEquals("serialized-key", unwrapped.get());
    }
}
