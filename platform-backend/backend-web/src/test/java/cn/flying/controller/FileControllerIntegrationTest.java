package cn.flying.controller;

import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.common.tenant.TenantContext;
import cn.flying.test.support.BaseControllerIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
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
@DisplayName("FileController Integration Tests")
@TestPropertySource(properties = "test.context=FileControllerIntegrationTest")
class FileControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/files";

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileShareMapper fileShareMapper;

    private File testFile;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testFile = createTestFile(testUserId, testTenantId);
    }

    private File createTestFile(Long userId, Long tenantId) {
        File file = new File();
        file.setUid(userId);
        file.setFileName("test-file-" + UUID.randomUUID() + ".pdf");
        file.setFileHash("sha256-" + UUID.randomUUID().toString().replace("-", ""));
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"application/pdf\"}");
        file.setClassification("document");
        file.setStatus(1);
        file.setTenantId(tenantId);
        file.setCreateTime(new Date());
        file.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> fileMapper.insert(file));
        return file;
    }

    @Nested
    @DisplayName("GET /files/{id}")
    class GetFileByIdTests {

        @Test
        @DisplayName("should return file details for owner")
        void shouldReturnFileDetailsForOwner() throws Exception {
            String externalId = cn.flying.common.util.IdUtils.toExternalId(testFile.getId());
            
            performGet(BASE_URL + "/" + externalId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.fileName").value(testFile.getFileName()))
                    .andExpect(jsonPath("$.data.fileHash").value(testFile.getFileHash()));
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            String externalId = cn.flying.common.util.IdUtils.toExternalId(testFile.getId());
            
            mockMvc.perform(get(BASE_URL + "/" + externalId)
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /files/list")
    class GetUserFilesListTests {

        @Test
        @DisplayName("should return user files list")
        void shouldReturnUserFilesList() throws Exception {
            performGet(BASE_URL + "/list")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("should only return files owned by current user")
        void shouldOnlyReturnFilesOwnedByCurrentUser() throws Exception {
            File otherUserFile = createTestFile(999L, testTenantId);

            MvcResult result = performGet(BASE_URL + "/list")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(content).get("data");

            assertThat(data.isArray()).isTrue();
            for (JsonNode fileNode : data) {
                String fileHash = fileNode.get("fileHash").asText();
                assertThat(fileHash).isNotEqualTo(otherUserFile.getFileHash());
            }
        }
    }

    @Nested
    @DisplayName("GET /files/page")
    class GetUserFilesPageTests {

        @Test
        @DisplayName("should return paginated files")
        void shouldReturnPaginatedFiles() throws Exception {
            performGet(BASE_URL + "/page?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.records").isArray());
        }
    }

    @Nested
    @DisplayName("GET /files/stats")
    class GetUserFileStatsTests {

        @Test
        @DisplayName("should return user file statistics")
        void shouldReturnUserFileStats() throws Exception {
            performGet(BASE_URL + "/stats")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("DELETE /files/delete")
    class DeleteFilesTests {

        @Test
        @DisplayName("should delete files by identifiers")
        void shouldDeleteFilesByIdentifiers() throws Exception {
            performDelete(BASE_URL + "/delete?identifiers=" + testFile.getFileHash())
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticatedRequest() throws Exception {
            mockMvc.perform(delete(BASE_URL + "/delete?identifiers=" + testFile.getFileHash())
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("POST /files/share")
    class GenerateSharingCodeTests {

        @Test
        @DisplayName("should generate sharing code for public share")
        void shouldGenerateSharingCodeForPublicShare() throws Exception {
            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(testFile.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult result = performPost(BASE_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            String shareCode = objectMapper.readTree(content).get("data").asText();
            assertThat(shareCode).isNotBlank();
        }

        @Test
        @DisplayName("should generate sharing code for private share")
        void shouldGenerateSharingCodeForPrivateShare() throws Exception {
            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(testFile.getFileHash()));
            sharingVO.setExpireMinutes(120);
            sharingVO.setShareType(1);

            performPost(BASE_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString());
        }
    }

    @Nested
    @DisplayName("DELETE /files/share/{shareCode}")
    class CancelShareTests {

        @Test
        @DisplayName("should cancel share successfully")
        void shouldCancelShareSuccessfully() throws Exception {
            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(testFile.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(BASE_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            performDelete(BASE_URL + "/share/" + shareCode)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("GET /files/shares")
    class GetMySharesTests {

        @Test
        @DisplayName("should return user's share list")
        void shouldReturnUserShareList() throws Exception {
            performGet(BASE_URL + "/shares?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").exists());
        }
    }

    @Nested
    @DisplayName("Admin Endpoints")
    class AdminEndpointTests {

        @Test
        @DisplayName("GET /files/{id}/provenance - should require admin role")
        void getProvenanceShouldRequireAdmin() throws Exception {
            String externalId = cn.flying.common.util.IdUtils.toExternalId(testFile.getId());

            performGet(BASE_URL + "/" + externalId + "/provenance")
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /files/{id}/provenance - should return provenance for admin")
        void getProvenanceShouldReturnForAdmin() throws Exception {
            setTestAdmin(100L, 1L);
            String externalId = cn.flying.common.util.IdUtils.toExternalId(testFile.getId());

            performGet(BASE_URL + "/" + externalId + "/provenance")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("GET /files/share/{shareCode}/access-logs - should require admin role")
        void getAccessLogsShouldRequireAdmin() throws Exception {
            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(testFile.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(BASE_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            performGet(BASE_URL + "/share/" + shareCode + "/access-logs?pageNum=1&pageSize=10")
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation Tests")
    class TenantIsolationTests {

        @Test
        @DisplayName("should not access files from different tenant")
        void shouldNotAccessFilesFromDifferentTenant() throws Exception {
            File otherTenantFile = createTestFile(testUserId, 999L);

            MvcResult result = performGet(BASE_URL + "/list")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(content).get("data");
            
            for (JsonNode fileNode : data) {
                String fileHash = fileNode.get("fileHash").asText();
                assertThat(fileHash).isNotEqualTo(otherTenantFile.getFileHash());
            }
        }
    }
}
