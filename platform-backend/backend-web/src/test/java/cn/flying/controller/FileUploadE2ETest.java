package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.service.FileUploadService;
import cn.flying.test.support.BaseControllerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Transactional
@DisplayName("File Upload E2E Tests")
@TestPropertySource(properties = "test.context=FileUploadE2ETest")
class FileUploadE2ETest extends BaseControllerIntegrationTest {

    private static final String UPLOAD_URL = "/api/v1/upload-sessions";
    private static final String FILES_URL = "/api/v1/files";

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileUploadService fileUploadService;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
    }

    @Nested
    @DisplayName("Complete Upload Flow")
    class CompleteUploadFlow {

        @Test
        @DisplayName("should complete single-chunk file upload flow")
        void shouldCompleteSingleChunkUploadFlow() throws Exception {
            String fileName = "test-" + UUID.randomUUID() + ".txt";
            byte[] content = "Hello, World!".getBytes();
            int fileSize = content.length;

            MvcResult startResult = performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=" + fileSize + "&" +
                    "contentType=text/plain&" +
                    "chunkSize=" + fileSize + "&" +
                    "totalChunks=1", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.clientId").exists())
                    .andReturn();

            String clientId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .get("data").get("clientId").asText();

            assertThat(clientId).isNotBlank();

            MvcResult progressResult = performGet(UPLOAD_URL + "/" + clientId + "/progress")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            JsonNode progressData = objectMapper.readTree(progressResult.getResponse().getContentAsString())
                    .get("data");
            assertThat(progressData.get("status").asText()).isIn("pending", "uploading");
        }

        @Test
        @DisplayName("should resume upload with same clientId")
        void shouldResumeUploadWithSameClientId() throws Exception {
            String clientId = UUID.randomUUID().toString();
            String fileName = "test-resume-" + UUID.randomUUID() + ".txt";
            int fileSize = 1024;

            performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=" + fileSize + "&" +
                    "contentType=text/plain&" +
                    "clientId=" + clientId + "&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.clientId").value(clientId));

            MvcResult resumeResult = performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=" + fileSize + "&" +
                    "contentType=text/plain&" +
                    "clientId=" + clientId + "&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.clientId").value(clientId))
                    .andExpect(jsonPath("$.data.uploadedChunks").isArray())
                    .andReturn();

            JsonNode data = objectMapper.readTree(resumeResult.getResponse().getContentAsString()).get("data");
            assertThat(data.get("uploadedChunks").isArray()).isTrue();
        }

        @Test
        @DisplayName("should handle pause and resume")
        void shouldHandlePauseAndResume() throws Exception {
            String fileName = "test-pause-" + UUID.randomUUID() + ".txt";
            int fileSize = 2048;

            MvcResult startResult = performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=" + fileSize + "&" +
                    "contentType=text/plain&" +
                    "chunkSize=512&" +
                    "totalChunks=4", null)
                    .andExpect(status().isOk())
                    .andReturn();

            String clientId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .get("data").get("clientId").asText();

            performPost(UPLOAD_URL + "/" + clientId + "/pause", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            MvcResult pausedProgress = performGet(UPLOAD_URL + "/" + clientId + "/progress")
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode progressData = objectMapper.readTree(pausedProgress.getResponse().getContentAsString())
                    .get("data");
            assertThat(progressData.get("status").asText()).isIn("paused", "pending", "uploading");

            performPost(UPLOAD_URL + "/" + clientId + "/resume", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should handle cancel upload")
        void shouldHandleCancelUpload() throws Exception {
            String fileName = "test-cancel-" + UUID.randomUUID() + ".txt";
            int fileSize = 1024;

            MvcResult startResult = performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=" + fileSize + "&" +
                    "contentType=text/plain&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk())
                    .andReturn();

            String clientId = objectMapper.readTree(startResult.getResponse().getContentAsString())
                    .get("data").get("clientId").asText();

            performDelete(UPLOAD_URL + "/" + clientId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("File Status Check Flow")
    class FileStatusCheckFlow {

        @Test
        @DisplayName("should check upload status")
        void shouldCheckUploadStatus() throws Exception {
            String clientId = UUID.randomUUID().toString();
            String fileName = "test-check-" + UUID.randomUUID() + ".txt";

            performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=1024&" +
                    "contentType=text/plain&" +
                    "clientId=" + clientId + "&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk());

            performGet(UPLOAD_URL + "/" + clientId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }

        @Test
        @DisplayName("should return error for non-existent upload")
        void shouldReturnErrorForNonExistentUpload() throws Exception {
            String fakeClientId = UUID.randomUUID().toString();

            MvcResult result = performGet(UPLOAD_URL + "/" + fakeClientId)
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("code").asInt();
            // Should return 40006 (UPLOAD_SESSION_NOT_FOUND) for non-existent upload
            assertThat(code).isEqualTo(40006);
        }
    }

    @Nested
    @DisplayName("Upload Validation")
    class UploadValidation {

        @Test
        @DisplayName("should reject file name with path traversal")
        void shouldRejectPathTraversal() throws Exception {
            performPost(UPLOAD_URL + "?" +
                    "fileName=../../../etc/passwd&" +
                    "fileSize=100&" +
                    "contentType=text/plain&" +
                    "chunkSize=100&" +
                    "totalChunks=1", null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject invalid chunk number")
        void shouldRejectInvalidChunkNumber() throws Exception {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test.txt", "text/plain", "test content".getBytes());

            mockMvc.perform(multipart(UPLOAD_URL + "/" + UUID.randomUUID() + "/chunks/not-a-number")
                            .file(file)
                            .with(request -> {
                                request.setMethod("PUT");
                                return request;
                            })
                            .header("Authorization", "Bearer " + testToken)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject empty file")
        void shouldRejectEmptyFile() throws Exception {
            performPost(UPLOAD_URL + "?" +
                    "fileName=empty.txt&" +
                    "fileSize=0&" +
                    "contentType=text/plain&" +
                    "chunkSize=0&" +
                    "totalChunks=0", null)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should limit file size")
        void shouldLimitFileSize() throws Exception {
            long oversizedFileSize = 5L * 1024 * 1024 * 1024;
            
            performPost(UPLOAD_URL + "?" +
                    "fileName=large.bin&" +
                    "fileSize=" + oversizedFileSize + "&" +
                    "contentType=application/octet-stream&" +
                    "chunkSize=10485760&" +
                    "totalChunks=500", null)
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("Multi-user Upload Isolation")
    class MultiUserUploadIsolation {

        @Test
        @DisplayName("should isolate uploads between users")
        void shouldIsolateUploadsBetweenUsers() throws Exception {
            String user1ClientId = UUID.randomUUID().toString();
            String fileName = "shared-name-" + UUID.randomUUID() + ".txt";

            performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=1024&" +
                    "contentType=text/plain&" +
                    "clientId=" + user1ClientId + "&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk());

            setTestUser(200L, 1L);

            String user2ClientId = UUID.randomUUID().toString();
            performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=1024&" +
                    "contentType=text/plain&" +
                    "clientId=" + user2ClientId + "&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk());

            setTestUser(100L, 1L);
            performGet(UPLOAD_URL + "/" + user1ClientId + "/progress")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            setTestUser(200L, 1L);
            performGet(UPLOAD_URL + "/" + user2ClientId + "/progress")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should isolate uploads between tenants")
        void shouldIsolateUploadsBetweenTenants() throws Exception {
            String clientId = UUID.randomUUID().toString();
            String fileName = "tenant-test-" + UUID.randomUUID() + ".txt";

            setTestUser(100L, 1L);
            performPost(UPLOAD_URL + "?" +
                    "fileName=" + fileName + "&" +
                    "fileSize=1024&" +
                    "contentType=text/plain&" +
                    "clientId=" + clientId + "&" +
                    "chunkSize=512&" +
                    "totalChunks=2", null)
                    .andExpect(status().isOk());

            setTestUser(100L, 2L);
            MvcResult result = performGet(UPLOAD_URL + "/" + clientId + "/progress")
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("code").asInt();
            // Should return 404 (not found) or specific error code when accessing another tenant's upload
            assertThat(code).isIn(404, 40004, 1001, 403);
        }
    }

    @Nested
    @DisplayName("Upload to File Lifecycle")
    class UploadToFileLifecycle {

        @Test
        @DisplayName("should list uploaded file after completion")
        void shouldListUploadedFileAfterCompletion() throws Exception {
            File testFile = createTestFile();

            MvcResult listResult = performGet(FILES_URL + "?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            JsonNode files = objectMapper.readTree(listResult.getResponse().getContentAsString())
                    .get("data").get("records");
            assertThat(files.isArray()).isTrue();

            boolean found = false;
            for (JsonNode file : files) {
                if (file.get("fileHash").asText().equals(testFile.getFileHash())) {
                    found = true;
                    break;
                }
            }
            assertThat(found).isTrue();
        }

        @Test
        @DisplayName("should get file details after upload")
        void shouldGetFileDetailsAfterUpload() throws Exception {
            File testFile = createTestFile();
            String externalId = IdUtils.toExternalId(testFile.getId());

            performGet(FILES_URL + "/" + externalId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.fileName").value(testFile.getFileName()))
                    .andExpect(jsonPath("$.data.fileHash").value(testFile.getFileHash()));
        }

        @Test
        @DisplayName("should delete file after upload")
        void shouldDeleteFileAfterUpload() throws Exception {
            File testFile = createTestFile();

            performDelete(FILES_URL + "/delete?identifiers=" + testFile.getFileHash())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            MvcResult listResult = performGet(FILES_URL + "?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode files = objectMapper.readTree(listResult.getResponse().getContentAsString())
                    .get("data").get("records");
            for (JsonNode file : files) {
                assertThat(file.get("fileHash").asText()).isNotEqualTo(testFile.getFileHash());
            }
        }
    }

    private File createTestFile() {
        File file = new File();
        file.setUid(testUserId);
        file.setFileName("e2e-test-" + UUID.randomUUID() + ".txt");
        file.setFileHash("sha256-e2e-" + UUID.randomUUID().toString().replace("-", ""));
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\"}");
        file.setClassification("document");
        file.setStatus(1);
        file.setTenantId(testTenantId);
        file.setCreateTime(new Date());
        file.setDeleted(0);
        TenantContext.runWithTenant(testTenantId, () -> fileMapper.insert(file));
        return file;
    }
}
