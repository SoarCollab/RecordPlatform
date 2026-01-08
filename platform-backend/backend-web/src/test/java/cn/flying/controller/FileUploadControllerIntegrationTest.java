package cn.flying.controller;

import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("FileUploadController Integration Tests")
@TestPropertySource(properties = "test.context=FileUploadControllerIntegrationTest")
class FileUploadControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/files/upload";

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
    }

    @Nested
    @DisplayName("POST /start")
    class StartUploadTests {

        @Test
        @DisplayName("should start upload successfully with valid parameters")
        void shouldStartUploadSuccessfully() throws Exception {
            performPost(BASE_URL + "/start?" +
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

        @Test
        @DisplayName("should start upload with provided clientId")
        void shouldStartUploadWithProvidedClientId() throws Exception {
            String providedClientId = UUID.randomUUID().toString();

            performPost(BASE_URL + "/start?" +
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

        @Test
        @DisplayName("should return 400 for missing fileName")
        void shouldReturn400ForMissingFileName() throws Exception {
            performPost(BASE_URL + "/start?" +
                    "fileSize=1024&" +
                    "contentType=application/pdf&" +
                    "chunkSize=1024&" +
                    "totalChunks=1", null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 for invalid fileSize (zero)")
        void shouldReturn400ForInvalidFileSize() throws Exception {
            performPost(BASE_URL + "/start?" +
                    "fileName=test.pdf&" +
                    "fileSize=0&" +
                    "contentType=application/pdf&" +
                    "chunkSize=1024&" +
                    "totalChunks=1", null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 400 for fileSize exceeding limit")
        void shouldReturn400ForFileSizeExceedingLimit() throws Exception {
            performPost(BASE_URL + "/start?" +
                    "fileName=test.pdf&" +
                    "fileSize=5000000000&" +
                    "contentType=application/pdf&" +
                    "chunkSize=1024&" +
                    "totalChunks=1", null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(post(BASE_URL + "/start?" +
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
    @DisplayName("POST /chunk")
    class UploadChunkTests {

        @Test
        @DisplayName("should return 400 for missing clientId")
        void shouldReturn400ForMissingClientId() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "test content".getBytes());

            mockMvc.perform(multipart(BASE_URL + "/chunk")
                            .file(file)
                            .param("chunkNumber", "0")
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.pdf", "application/pdf", "test content".getBytes());

            mockMvc.perform(multipart(BASE_URL + "/chunk")
                            .file(file)
                            .param("clientId", UUID.randomUUID().toString())
                            .param("chunkNumber", "0")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /complete")
    class CompleteUploadTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(post(BASE_URL + "/complete")
                            .param("clientId", UUID.randomUUID().toString())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /pause")
    class PauseUploadTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(post(BASE_URL + "/pause")
                            .param("clientId", UUID.randomUUID().toString())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /resume")
    class ResumeUploadTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(post(BASE_URL + "/resume")
                            .param("clientId", UUID.randomUUID().toString())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /cancel")
    class CancelUploadTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(post(BASE_URL + "/cancel")
                            .param("clientId", UUID.randomUUID().toString())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /check")
    class CheckFileStatusTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/check")
                            .param("clientId", UUID.randomUUID().toString())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /progress")
    class GetUploadProgressTests {

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(get(BASE_URL + "/progress")
                            .param("clientId", UUID.randomUUID().toString())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }
}
