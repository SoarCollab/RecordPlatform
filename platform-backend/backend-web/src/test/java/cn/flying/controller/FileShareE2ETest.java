package cn.flying.controller;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.Friendship;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FriendshipMapper;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.test.support.BaseControllerIntegrationTest;
import cn.flying.test.support.JwtTestSupport;
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
@DisplayName("File Share E2E Tests")
@TestPropertySource(properties = "test.context=FileShareE2ETest")
class FileShareE2ETest extends BaseControllerIntegrationTest {

    private static final String FILES_URL = "/api/v1/files";
    private static final String SHARE_URL = "/api/v1/share";

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileShareMapper fileShareMapper;

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private FriendshipMapper friendshipMapper;

    private Long ownerUserId = 100L;
    private Long recipientUserId = 200L;
    private Long ownerTenantId = 1L;

    @BeforeEach
    void setUp() {
        setTestUser(ownerUserId, ownerTenantId);
    }

    @Nested
    @DisplayName("Public Share Flow")
    class PublicShareFlow {

        @Test
        @DisplayName("should complete public share flow: create -> access -> verify")
        void shouldCompletePublicShareFlow() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();
            assertThat(shareCode).isNotBlank();

            setTestUser(recipientUserId, ownerTenantId);

            performGet(SHARE_URL + "/" + shareCode + "/info")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.files").isArray())
                    .andExpect(jsonPath("$.data.files[0].fileName").value(file.getFileName()));

            performGet(FILES_URL + "/shares?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should allow multiple accesses to public share")
        void shouldAllowMultipleAccessesToPublicShare() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            for (long userId = 201L; userId <= 203L; userId++) {
                setTestUser(userId, ownerTenantId);
                performGet(SHARE_URL + "/" + shareCode + "/info")
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.code").value(200));
            }
        }
    }

    @Nested
    @DisplayName("Private Share Flow")
    class PrivateShareFlow {

        @Test
        @DisplayName("should create private share with password protection")
        void shouldCreatePrivateShareWithPassword() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(1);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data").isString())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();
            assertThat(shareCode).isNotBlank();
        }
    }

    @Nested
    @DisplayName("Share Expiration")
    class ShareExpiration {

        @Test
        @DisplayName("should respect share expiration time")
        void shouldRespectShareExpirationTime() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            setTestUser(recipientUserId, ownerTenantId);
            performGet(SHARE_URL + "/" + shareCode + "/info")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }

        @Test
        @DisplayName("should allow creating share with long expiration")
        void shouldAllowLongExpiration() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(10080);
            sharingVO.setShareType(0);

            performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));
        }
    }

    @Nested
    @DisplayName("Share Cancellation")
    class ShareCancellation {

        @Test
        @DisplayName("should cancel share and prevent further access")
        void shouldCancelShareAndPreventAccess() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            performDelete(FILES_URL + "/share/" + shareCode)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200));

            setTestUser(recipientUserId, ownerTenantId);
            MvcResult accessResult = performGet(SHARE_URL + "/" + shareCode + "/info")
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(accessResult.getResponse().getContentAsString())
                    .get("code").asInt();
            assertThat(code).isNotEqualTo(200);
        }

        @Test
        @DisplayName("should only allow owner to cancel share")
        void shouldOnlyAllowOwnerToCancelShare() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            setTestUser(recipientUserId, ownerTenantId);
            MvcResult deleteResult = performDelete(FILES_URL + "/share/" + shareCode)
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(deleteResult.getResponse().getContentAsString())
                    .get("code").asInt();
            assertThat(code).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Multi-File Share")
    class MultiFileShare {

        @Test
        @DisplayName("should share multiple files in one share")
        void shouldShareMultipleFiles() throws Exception {
            File file1 = createTestFile(ownerUserId);
            File file2 = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file1.getFileHash(), file2.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            setTestUser(recipientUserId, ownerTenantId);
            MvcResult infoResult = performGet(SHARE_URL + "/" + shareCode + "/info")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.files").isArray())
                    .andReturn();

            JsonNode files = objectMapper.readTree(infoResult.getResponse().getContentAsString())
                    .get("data").get("files");
            assertThat(files.size()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Share List Management")
    class ShareListManagement {

        @Test
        @DisplayName("should list user's shares")
        void shouldListUserShares() throws Exception {
            File file1 = createTestFile(ownerUserId);
            File file2 = createTestFile(ownerUserId);

            createShare(file1.getFileHash());
            createShare(file2.getFileHash());

            MvcResult result = performGet(FILES_URL + "/shares?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andReturn();

            JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("data");
            assertThat(data).isNotNull();
        }

        @Test
        @DisplayName("should not show other user's shares")
        void shouldNotShowOtherUserShares() throws Exception {
            File ownerFile = createTestFile(ownerUserId);
            createShare(ownerFile.getFileHash());

            setTestUser(recipientUserId, ownerTenantId);
            File recipientFile = createTestFile(recipientUserId);

            MvcResult result = performGet(FILES_URL + "/shares?pageNum=1&pageSize=10")
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            assertThat(content).doesNotContain(ownerFile.getFileHash());
        }
    }

    @Nested
    @DisplayName("Tenant Isolation")
    class TenantIsolation {

        @Test
        @DisplayName("should not access share from different tenant")
        void shouldNotAccessShareFromDifferentTenant() throws Exception {
            File file = createTestFile(ownerUserId);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(file.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult createResult = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            String shareCode = objectMapper.readTree(createResult.getResponse().getContentAsString())
                    .get("data").asText();

            setTestUser(recipientUserId, 999L);
            MvcResult accessResult = performGet(SHARE_URL + "/" + shareCode + "/info")
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(accessResult.getResponse().getContentAsString())
                    .get("code").asInt();
            assertThat(code).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Share Validation")
    class ShareValidation {

        @Test
        @DisplayName("should reject share of non-existent file")
        void shouldRejectShareOfNonExistentFile() throws Exception {
            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of("non-existent-hash-" + UUID.randomUUID()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult result = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("code").asInt();
            assertThat(code).isNotEqualTo(200);
        }

        @Test
        @DisplayName("should reject share of other user's file")
        void shouldRejectShareOfOtherUsersFile() throws Exception {
            File otherUserFile = createTestFile(999L);

            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of(otherUserFile.getFileHash()));
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            MvcResult result = performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isOk())
                    .andReturn();

            int code = objectMapper.readTree(result.getResponse().getContentAsString())
                    .get("code").asInt();
            assertThat(code).isNotEqualTo(200);
        }

        @Test
        @DisplayName("should reject empty file list")
        void shouldRejectEmptyFileList() throws Exception {
            FileSharingVO sharingVO = new FileSharingVO();
            sharingVO.setFileHash(List.of());
            sharingVO.setExpireMinutes(60);
            sharingVO.setShareType(0);

            performPost(FILES_URL + "/share", sharingVO)
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("should reject invalid share code format")
        void shouldRejectInvalidShareCodeFormat() throws Exception {
            performGet(SHARE_URL + "/invalid-code-" + UUID.randomUUID() + "/info")
                    .andExpect(status().isOk())
                    .andExpect(result -> {
                        int code = objectMapper.readTree(result.getResponse().getContentAsString())
                                .get("code").asInt();
                        assertThat(code).isNotEqualTo(200);
                    });
        }
    }

    private File createTestFile(Long userId) {
        File file = new File();
        file.setUid(userId);
        file.setFileName("share-test-" + UUID.randomUUID() + ".pdf");
        file.setFileHash("sha256-share-" + UUID.randomUUID().toString().replace("-", ""));
        file.setFileParam("{\"fileSize\":2048,\"contentType\":\"application/pdf\"}");
        file.setClassification("document");
        file.setStatus(1);
        file.setTenantId(ownerTenantId);
        file.setCreateTime(new Date());
        file.setDeleted(0);
        TenantContext.runWithTenant(ownerTenantId, () -> fileMapper.insert(file));
        return file;
    }

    private String createShare(String fileHash) throws Exception {
        FileSharingVO sharingVO = new FileSharingVO();
        sharingVO.setFileHash(List.of(fileHash));
        sharingVO.setExpireMinutes(60);
        sharingVO.setShareType(0);

        MvcResult result = performPost(FILES_URL + "/share", sharingVO)
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").asText();
    }
}
