package cn.flying.service.impl;

import cn.flying.dao.vo.file.DirectUploadCompletePartRequest;
import cn.flying.dao.vo.file.DirectUploadPartRequest;
import cn.flying.dao.vo.file.DirectUploadSessionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁定 direct complete ETag 的规范 JSON 名称和旧客户端兼容别名。
 */
@DisplayName("DirectUploadCompletePartRequest JSON Contract Tests")
class DirectUploadCompletePartRequestJsonTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("serialization should expose only canonical eTag")
    void shouldSerializeOnlyCanonicalEtagName() throws Exception {
        DirectUploadCompletePartRequest request = new DirectUploadCompletePartRequest();
        request.setIndex(3);
        request.setETag("\"etag-3\"");

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(request));

        assertThat(json.has("eTag")).isTrue();
        assertThat(json.get("eTag").asText()).isEqualTo("\"etag-3\"");
        assertThat(json.has("etag")).isFalse();
    }

    @Test
    @DisplayName("deserialization should accept canonical and legacy ETag names")
    void shouldDeserializeCanonicalAndLegacyEtagNames() throws Exception {
        DirectUploadCompletePartRequest canonical = objectMapper.readValue(
                "{\"index\":1,\"eTag\":\"canonical\"}",
                DirectUploadCompletePartRequest.class
        );
        DirectUploadCompletePartRequest legacy = objectMapper.readValue(
                "{\"index\":2,\"etag\":\"legacy\"}",
                DirectUploadCompletePartRequest.class
        );

        assertThat(canonical.getETag()).isEqualTo("canonical");
        assertThat(legacy.getETag()).isEqualTo("legacy");
    }

    /**
     * 验证 JSON 缺失完成分片 index 时保持 null，并由 Bean Validation 拒绝而不是静默映射为 0。
     */
    @Test
    void missingCompletePartIndexShouldFailValidation() throws Exception {
        DirectUploadCompletePartRequest request = objectMapper.readValue(
                "{\"eTag\":\"etag\"}",
                DirectUploadCompletePartRequest.class);

        assertThat(request.getIndex()).isNull();
        assertThat(validator.validate(request)).extracting(violation ->
                violation.getPropertyPath().toString()).contains("index");
    }

    /**
     * 验证创建请求所有必填数值缺失时均保留 null，并覆盖嵌套分片的 index/size 约束。
     */
    @Test
    void missingDirectSessionNumbersShouldFailValidation() throws Exception {
        String hash = "sha256:" + "a".repeat(64);
        DirectUploadSessionRequest request = objectMapper.readValue(
                "{\"fileName\":\"direct.pdf\",\"contentType\":\"application/pdf\","
                        + "\"parts\":[{\"plainHash\":\"" + hash + "\","
                        + "\"cipherHash\":\"" + hash + "\"}]}",
                DirectUploadSessionRequest.class);

        assertThat(request.getFileSize()).isNull();
        assertThat(request.getChunkSize()).isNull();
        assertThat(request.getTotalChunks()).isNull();
        DirectUploadPartRequest part = request.getParts().getFirst();
        assertThat(part.getIndex()).isNull();
        assertThat(part.getSize()).isNull();
        Set<String> violationPaths = validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
        assertThat(violationPaths).contains(
                "fileSize", "chunkSize", "totalChunks", "parts[0].index", "parts[0].size");
    }
}
