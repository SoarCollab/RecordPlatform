package cn.flying.controller;

import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Transactional
@DisplayName("UploadSessionController Integration Tests")
@TestPropertySource(properties = "test.context=UploadSessionControllerIntegrationTest")
class FileUploadControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/upload-sessions";

    /**
     * 为每个用例注入默认测试用户。
     */
    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
    }

    @Nested
    @DisplayName("POST /upload-sessions")
    class StartUploadTests {

        /**
         * 验证上传会话创建成功分支。
         */
        @Test
        @DisplayName("should start upload successfully with valid parameters")
        void shouldStartUploadSuccessfully() throws Exception {
            performPost(BASE_URL + "?" +
                    "fileName=test.pdf&" +
                    "fileSize=1024&" +
                    "contentType=application/pdf&" +
                    "chunkSize=1024&" +
                    "totalChunks=1", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.clientId").exists())
                    .andExpect(jsonPath("$.data.uploadedChunks").isArray());
        }

        /**
         * 验证创建上传会话时可指定 clientId。
         */
        @Test
        @DisplayName("should start upload with provided clientId")
        void shouldStartUploadWithProvidedClientId() throws Exception {
            String providedClientId = UUID.randomUUID().toString();

            performPost(BASE_URL + "?" +
                    "fileName=test.pdf&" +
                    "fileSize=1024&" +
                    "contentType=application/pdf&" +
                    "clientId=" + providedClientId + "&" +
                    "chunkSize=1024&" +
                    "totalChunks=1", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.clientId").value(providedClientId));
        }

        /**
         * 验证 fileName 缺失时参数校验失败。
         */
        @Test
        @DisplayName("should return 400 for missing fileName")
        void shouldReturn400ForMissingFileName() throws Exception {
            performPost(BASE_URL + "?" +
                    "fileSize=1024&" +
                    "contentType=application/pdf&" +
                    "chunkSize=1024&" +
                    "totalChunks=1", null)
                    .andExpect(status().isBadRequest());
        }

        /**
         * 验证匿名请求会被鉴权拒绝。
         */
        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(post(BASE_URL + "?" +
                            "fileName=test.pdf&" +
                            "fileSize=1024&" +
                            "contentType=application/pdf&" +
                            "chunkSize=1024&" +
                            "totalChunks=1")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("PUT /upload-sessions/{clientId}/chunks/{chunkNumber}")
    class UploadChunkTests {

        /**
         * 验证分片文件缺失时返回 400。
         */
        @Test
        @DisplayName("should return 400 for missing file part")
        void shouldReturn400ForMissingFilePart() throws Exception {
            mockMvc.perform(put(BASE_URL + "/client-1/chunks/0")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        /**
         * 验证匿名分片上传会被鉴权拒绝。
         */
        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "test content".getBytes());

            mockMvc.perform(multipart(BASE_URL + "/client-1/chunks/0")
                            .file(file)
                            .with(request -> {
                                request.setMethod("PUT");
                                return request;
                            })
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("session action endpoints")
    class SessionActionTests {

        /**
         * 验证 complete/pause/resume/cancel 匿名请求均返回 401。
         */
        @Test
        @DisplayName("should return 401 for unauthenticated action requests")
        void shouldReturn401ForUnauthenticatedActionRequests() throws Exception {
            String clientId = UUID.randomUUID().toString();

            mockMvc.perform(post(BASE_URL + "/" + clientId + "/complete")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post(BASE_URL + "/" + clientId + "/pause")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(post(BASE_URL + "/" + clientId + "/resume")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(BASE_URL + "/" + clientId)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /upload-sessions/{clientId} and /progress")
    class StatusTests {

        /**
         * 验证状态查询匿名请求会被鉴权拒绝。
         */
        @Test
        @DisplayName("should return 401 for unauthenticated query requests")
        void shouldReturn401ForUnauthenticatedQueryRequests() throws Exception {
            String clientId = UUID.randomUUID().toString();

            mockMvc.perform(get(BASE_URL + "/" + clientId)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());

            mockMvc.perform(get(BASE_URL + "/" + clientId + "/progress")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
