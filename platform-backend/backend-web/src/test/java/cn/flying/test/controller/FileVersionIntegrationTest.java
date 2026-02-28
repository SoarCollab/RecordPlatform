package cn.flying.test.controller;

import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("File Version Integration Tests")
@Transactional
class FileVersionIntegrationTest extends BaseControllerIntegrationTest {

    @Autowired
    private FileMapper fileMapper;

    private static final String API_PREFIX = "/api/v1/files";

    private File createAndInsertFile(Long userId, Long tenantId, Long versionGroupId,
                                      int version, int isLatest, Long parentVersionId) {
        File file = new File();
        file.setId(IdUtils.nextEntityId());
        file.setUid(userId);
        file.setTenantId(tenantId);
        file.setFileName("version_" + version + "_" + UUID.randomUUID() + ".txt");
        file.setFileHash("sha256-" + UUID.randomUUID().toString().replace("-", ""));
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\",\"initialKey\":\"dGVzdA==\"}");
        file.setClassification("document");
        file.setStatus(1); // SUCCESS
        file.setDeleted(0);
        file.setVersion(version);
        file.setIsLatest(isLatest);
        file.setVersionGroupId(versionGroupId);
        file.setParentVersionId(parentVersionId);
        file.setCreateTime(new Date());
        fileMapper.insert(file);
        return file;
    }

    @Nested
    @DisplayName("GET /{id}/versions")
    class GetVersionHistory {

        @Test
        @DisplayName("should return complete version chain with 3 versions")
        void shouldReturnCompleteVersionChain() throws Exception {
            Long versionGroupId = IdUtils.nextEntityId();

            File v1 = createAndInsertFile(testUserId, testTenantId, versionGroupId, 1, 0, null);
            File v2 = createAndInsertFile(testUserId, testTenantId, versionGroupId, 2, 0, v1.getId());
            File v3 = createAndInsertFile(testUserId, testTenantId, versionGroupId, 3, 1, v2.getId());

            String externalId = IdUtils.toExternalId(v3.getId());

            expectOk(performGet(API_PREFIX + "/{id}/versions", externalId))
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    .andExpect(jsonPath("$.data[0].version", is(3)))
                    .andExpect(jsonPath("$.data[1].version", is(2)))
                    .andExpect(jsonPath("$.data[2].version", is(1)));
        }

        @Test
        @DisplayName("should return single entry for single-version file")
        void shouldReturnSingleEntryForSingleVersionFile() throws Exception {
            Long versionGroupId = IdUtils.nextEntityId();
            File file = createAndInsertFile(testUserId, testTenantId, versionGroupId, 1, 1, null);

            String externalId = IdUtils.toExternalId(file.getId());

            expectOk(performGet(API_PREFIX + "/{id}/versions", externalId))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].version", is(1)))
                    .andExpect(jsonPath("$.data[0].isLatest", is(1)));
        }

        @Test
        @DisplayName("should reject access to other user's file versions")
        void shouldRejectOtherUserFile() throws Exception {
            Long otherUserId = 999L;
            Long versionGroupId = IdUtils.nextEntityId();
            File file = createAndInsertFile(otherUserId, testTenantId, versionGroupId, 1, 1, null);

            String externalId = IdUtils.toExternalId(file.getId());

            MvcResult result = performGet(API_PREFIX + "/{id}/versions", externalId)
                    .andExpect(status().isOk())
                    .andReturn();

            int code = extractCode(result);
            // Should be a permission error code (70001)
            org.junit.jupiter.api.Assertions.assertNotEquals(200, code);
        }
    }

    @Nested
    @DisplayName("POST /{id}/versions")
    class CreateNewVersion {

        @Test
        @DisplayName("should create new version and flip is_latest correctly")
        void shouldCreateNewVersionAndFlipIsLatest() throws Exception {
            Long versionGroupId = IdUtils.nextEntityId();
            File v1 = createAndInsertFile(testUserId, testTenantId, versionGroupId, 1, 1, null);
            String externalId = IdUtils.toExternalId(v1.getId());

            String body = """
                    {"fileName":"v2.txt","fileSize":2048,"contentType":"text/plain"}
                    """;

            mockMvc.perform(withAuth(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post(API_PREFIX + "/{id}/versions", externalId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.version").value(2))
                    .andExpect(jsonPath("$.data.fileId").isNotEmpty())
                    .andExpect(jsonPath("$.data.versionGroupId").isNotEmpty())
                    .andReturn();

            // Verify is_latest was flipped in DB
            File reloadedV1 = fileMapper.selectById(v1.getId());
            org.junit.jupiter.api.Assertions.assertEquals(0, reloadedV1.getIsLatest());
        }

        @Test
        @DisplayName("should reject when parent file not found")
        void shouldRejectWhenParentNotFound() throws Exception {
            String fakeExternalId = IdUtils.toExternalId(999999L);

            String body = """
                    {"fileName":"v2.txt","fileSize":2048,"contentType":"text/plain"}
                    """;

            MvcResult result = mockMvc.perform(withAuth(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post(API_PREFIX + "/{id}/versions", fakeExternalId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk())
                    .andReturn();

            int code = extractCode(result);
            org.junit.jupiter.api.Assertions.assertNotEquals(200, code);
        }

        @Test
        @DisplayName("should reject non-owner creating version")
        void shouldRejectNonOwner() throws Exception {
            Long otherUserId = 999L;
            Long versionGroupId = IdUtils.nextEntityId();
            File file = createAndInsertFile(otherUserId, testTenantId, versionGroupId, 1, 1, null);
            String externalId = IdUtils.toExternalId(file.getId());

            String body = """
                    {"fileName":"v2.txt","fileSize":2048,"contentType":"text/plain"}
                    """;

            MvcResult result = mockMvc.perform(withAuth(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post(API_PREFIX + "/{id}/versions", externalId))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isOk())
                    .andReturn();

            int code = extractCode(result);
            org.junit.jupiter.api.Assertions.assertNotEquals(200, code);
        }
    }
}
