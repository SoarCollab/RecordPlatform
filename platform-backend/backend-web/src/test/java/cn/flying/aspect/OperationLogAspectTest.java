package cn.flying.aspect;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OperationLogAspect Tests")
class OperationLogAspectTest {

    /**
     * 验证操作日志切面会脱敏路径中的分享码和文件哈希。
     */
    @Test
    @DisplayName("Should mask sensitive identifiers in operation log path")
    void shouldMaskSensitiveIdentifiersInOperationLogPath() {
        OperationLogAspect aspect = new OperationLogAspect();

        String sanitized = ReflectionTestUtils.invokeMethod(
                aspect,
                "sanitizePathForLog",
                "/api/v1/public/shares/ABC123/files/hash-secret/chunks"
        );

        assertThat(sanitized).isEqualTo("/api/v1/public/shares/***/files/***/chunks");
    }

    /**
     * 验证文件接口会被标记为敏感操作，避免请求参数和响应体落库。
     */
    @Test
    @DisplayName("Should classify file operations as sensitive")
    void shouldClassifyFileOperationsAsSensitive() {
        OperationLogAspect aspect = new OperationLogAspect();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/transactions/0xtxhash");

        Boolean result = ReflectionTestUtils.invokeMethod(aspect, "isSensitiveFileOperation", request);

        assertThat(result).isTrue();
    }
}
