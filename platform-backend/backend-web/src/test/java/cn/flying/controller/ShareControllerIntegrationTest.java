package cn.flying.controller;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.test.support.BaseControllerIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * ShareController 集成测试
 *
 * 测试分享详情查询接口的各种场景
 */
@Transactional
@DisplayName("ShareController Integration Tests")
@TestPropertySource(properties = "test.context=ShareControllerIntegrationTest")
class ShareControllerIntegrationTest extends BaseControllerIntegrationTest {

    private static final String BASE_URL = "/api/v1/share";

    @Autowired
    private AccountMapper accountMapper;

    @Autowired
    private FileMapper fileMapper;

    @Autowired
    private FileShareMapper fileShareMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Account testAccount;

    @BeforeEach
    void setUp() {
        setTestUser(100L, 1L);
        testAccount = createTestAccount(testUserId, testTenantId, "shareuser");
    }

    private Account createTestAccount(Long userId, Long tenantId, String username) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername(username + "_" + userId);
        account.setEmail(username + "_" + userId + "@test.com");
        account.setPassword(passwordEncoder.encode("password123"));
        account.setRole("user");
        account.setAvatar("https://example.com/avatar.png");
        account.setNickname("Test " + username);
        account.setTenantId(tenantId);
        account.setRegisterTime(new Date());
        account.setUpdateTime(new Date());
        account.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> accountMapper.insert(account));
        return account;
    }

    private File createTestFile(Long userId, Long tenantId, String fileHash) {
        File file = new File();
        file.setUid(userId);
        file.setFileName("test_file_" + fileHash + ".txt");
        file.setFileHash(fileHash);
        file.setFileParam("{\"fileSize\":1024,\"contentType\":\"text/plain\"}");
        file.setClassification("document");
        file.setStatus(1);
        file.setTenantId(tenantId);
        file.setCreateTime(new Date());
        file.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> fileMapper.insert(file));
        return file;
    }

    private FileShare createFileShare(Long userId, Long tenantId, String shareCode, String fileHashes,
                                       Integer status, Date expireTime) {
        FileShare share = new FileShare();
        share.setUserId(userId);
        share.setTenantId(tenantId);
        share.setShareCode(shareCode);
        share.setShareType(0); // public
        share.setFileHashes(fileHashes);
        share.setStatus(status);
        share.setExpireTime(expireTime);
        share.setAccessCount(0);
        share.setCreateTime(new Date());
        share.setUpdateTime(new Date());
        share.setDeleted(0);
        TenantContext.runWithTenant(tenantId, () -> fileShareMapper.insert(share));
        return share;
    }

    private String generateShareCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    @Nested
    @DisplayName("GET /{shareCode}/info - Get Share Info")
    class GetShareInfoTests {

        @Test
        @DisplayName("should return share info successfully for valid share")
        void shouldReturnShareInfoSuccessfully() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_test_hash_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.shareCode").value(shareCode))
                    .andExpect(jsonPath("$.data.shareType").value(0))
                    .andExpect(jsonPath("$.data.files").isArray())
                    .andExpect(jsonPath("$.data.files[0].fileHash").value(fileHash));
        }

        @Test
        @DisplayName("should return share info for public share without authentication")
        void shouldReturnShareInfoWithoutAuth() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_public_hash_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            // Request without Authorization header
            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.shareCode").value(shareCode));
        }

        @Test
        @DisplayName("should return multiple files in share")
        void shouldReturnMultipleFilesInShare() throws Exception {
            String shareCode = generateShareCode();
            String fileHash1 = "sha256_multi_1_" + System.currentTimeMillis();
            String fileHash2 = "sha256_multi_2_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash1);
            createTestFile(testUserId, testTenantId, fileHash2);
            createFileShare(testUserId, testTenantId, shareCode,
                    "[\"" + fileHash1 + "\",\"" + fileHash2 + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.files").isArray())
                    .andExpect(jsonPath("$.data.files.length()").value(2));
        }
    }

    @Nested
    @DisplayName("Share Not Found Scenarios")
    class ShareNotFoundTests {

        @Test
        @DisplayName("should return 404 for non-existent share code")
        void shouldReturn404ForNonExistentShareCode() throws Exception {
            mockMvc.perform(get(BASE_URL + "/NOTFOUND/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404))
                    .andExpect(jsonPath("$.message").value("分享不存在"));
        }

        @Test
        @DisplayName("should return error for empty share code")
        void shouldReturnErrorForEmptyShareCode() throws Exception {
            mockMvc.perform(get(BASE_URL + "//info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("Share Status Validation")
    class ShareStatusTests {

        @Test
        @DisplayName("should return cancelled status for cancelled share")
        void shouldReturnCancelledStatusForCancelledShare() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_cancelled_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_CANCELLED, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50011))
                    .andExpect(jsonPath("$.message").value("分享链接已被取消"));
        }

        @Test
        @DisplayName("should return expired status for expired share (status=2)")
        void shouldReturnExpiredStatusForExpiredShareByStatus() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_expired_status_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_EXPIRED, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50012))
                    .andExpect(jsonPath("$.message").value("分享已过期"));
        }

        @Test
        @DisplayName("should return expired status for share with past expire time")
        void shouldReturnExpiredStatusForPastExpireTime() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_time_expired_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            // Status is active but expire time is in the past
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() - 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50012))
                    .andExpect(jsonPath("$.message").value("分享已过期"));
        }
    }

    @Nested
    @DisplayName("Share Code Validation")
    class ShareCodeValidationTests {

        @Test
        @DisplayName("should return error for share code exceeding 64 characters")
        void shouldReturnErrorForTooLongShareCode() throws Exception {
            String longShareCode = "A".repeat(65);

            mockMvc.perform(get(BASE_URL + "/" + longShareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(ResultEnum.PARAM_IS_INVALID.getCode()))
                    .andExpect(jsonPath("$.message").value("分享码格式错误"));
        }

        @Test
        @DisplayName("should accept share code with exactly 64 characters")
        void shouldAcceptShareCodeWith64Characters() throws Exception {
            String shareCode64 = "A".repeat(64);

            // Will return 404 (not found) rather than validation error
            mockMvc.perform(get(BASE_URL + "/" + shareCode64 + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(404));
        }
    }

    @Nested
    @DisplayName("Empty Files Handling")
    class EmptyFilesTests {

        @Test
        @DisplayName("should return error when share has empty file hashes")
        void shouldReturnErrorForEmptyFileHashes() throws Exception {
            String shareCode = generateShareCode();

            createFileShare(testUserId, testTenantId, shareCode, "[]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分享文件为空"));
        }

        @Test
        @DisplayName("should return error when share has null file hashes")
        void shouldReturnErrorForNullFileHashes() throws Exception {
            String shareCode = generateShareCode();

            createFileShare(testUserId, testTenantId, shareCode, null,
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分享文件为空"));
        }

        @Test
        @DisplayName("should handle invalid JSON in file hashes gracefully")
        void shouldHandleInvalidJsonInFileHashes() throws Exception {
            String shareCode = generateShareCode();

            // Invalid JSON format
            createFileShare(testUserId, testTenantId, shareCode, "not-valid-json",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("分享文件为空"));
        }
    }

    @Nested
    @DisplayName("File Database Lookup")
    class FileDatabaseLookupTests {

        @Test
        @DisplayName("should return empty files when referenced files do not exist in database")
        void shouldReturnEmptyFilesWhenFilesNotInDatabase() throws Exception {
            String shareCode = generateShareCode();
            String nonExistentHash = "sha256_nonexistent_" + System.currentTimeMillis();

            // Don't create the file, only create the share
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + nonExistentHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.shareCode").value(shareCode))
                    .andExpect(jsonPath("$.data.files").isArray())
                    .andExpect(jsonPath("$.data.files").isEmpty());
        }

        @Test
        @DisplayName("should only return files that exist for partial matches")
        void shouldReturnOnlyExistingFilesForPartialMatches() throws Exception {
            String shareCode = generateShareCode();
            String existingHash = "sha256_exists_" + System.currentTimeMillis();
            String nonExistingHash = "sha256_not_exists_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, existingHash);
            // Don't create file for nonExistingHash

            createFileShare(testUserId, testTenantId, shareCode,
                    "[\"" + existingHash + "\",\"" + nonExistingHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.files.length()").value(1))
                    .andExpect(jsonPath("$.data.files[0].fileHash").value(existingHash));
        }
    }

    @Nested
    @DisplayName("Share Type Tests")
    class ShareTypeTests {

        @Test
        @DisplayName("should return correct share type for public share")
        void shouldReturnCorrectShareTypeForPublic() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_public_type_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            FileShare share = createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));
            share.setShareType(0); // public
            TenantContext.runWithTenant(testTenantId, () -> fileShareMapper.updateById(share));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.shareType").value(0));
        }

        @Test
        @DisplayName("should return correct share type for private share")
        void shouldReturnCorrectShareTypeForPrivate() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_private_type_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            FileShare share = createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, new Date(System.currentTimeMillis() + 3600000));
            share.setShareType(1); // private
            TenantContext.runWithTenant(testTenantId, () -> fileShareMapper.updateById(share));

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.shareType").value(1));
        }
    }

    @Nested
    @DisplayName("Expiration Time Edge Cases")
    class ExpirationTimeEdgeCasesTests {

        @Test
        @DisplayName("should accept share with null expire time as non-expired")
        void shouldAcceptShareWithNullExpireTime() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_null_expire_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, null);

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.shareCode").value(shareCode));
        }

        @Test
        @DisplayName("should handle share expiring exactly now")
        void shouldHandleShareExpiringNow() throws Exception {
            String shareCode = generateShareCode();
            String fileHash = "sha256_expire_now_" + System.currentTimeMillis();

            createTestFile(testUserId, testTenantId, fileHash);
            // Use an explicit past time (1 second ago) to ensure deterministic expiration
            Date pastExpireTime = new Date(System.currentTimeMillis() - 1000);
            createFileShare(testUserId, testTenantId, shareCode, "[\"" + fileHash + "\"]",
                    FileShare.STATUS_ACTIVE, pastExpireTime);

            mockMvc.perform(get(BASE_URL + "/" + shareCode + "/info")
                            .header(HEADER_TENANT_ID, testTenantId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(50012))
                    .andExpect(jsonPath("$.message").value("分享已过期"));
        }
    }
}
