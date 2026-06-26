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

    /**
     * 验证生产 context-path 下的文件接口仍会被标记为敏感操作。
     */
    @Test
    @DisplayName("Should classify sensitive operations behind context path")
    void shouldClassifySensitiveOperationsBehindContextPath() {
        OperationLogAspect aspect = new OperationLogAspect();
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/record-platform/api/v1/public/shares/ABC123/files/hash-secret/chunks"
        );
        request.setContextPath("/record-platform");

        Boolean result = ReflectionTestUtils.invokeMethod(aspect, "isSensitiveFileOperation", request);

        assertThat(result).isTrue();
    }

    /**
     * 验证系统审计接口本身也会进入操作日志，避免敏感管理动作缺失审计记录。
     */
    @Test
    @DisplayName("Should not ignore system audit API paths")
    void shouldNotIgnoreSystemAuditApiPaths() {
        OperationLogAspect aspect = new OperationLogAspect();

        Boolean result = ReflectionTestUtils.invokeMethod(
                aspect,
                "isIgnoreUrl",
                "/api/v1/system/audit/logs/export"
        );

        assertThat(result).isFalse();
    }

    /**
     * 验证非业务文档路由仍然会被操作日志切面忽略。
     */
    @Test
    @DisplayName("Should still ignore API documentation paths")
    void shouldStillIgnoreApiDocumentationPaths() {
        OperationLogAspect aspect = new OperationLogAspect();

        Boolean result = ReflectionTestUtils.invokeMethod(
                aspect,
                "isIgnoreUrl",
                "/swagger-ui/index.html"
        );

        assertThat(result).isTrue();
    }
}
