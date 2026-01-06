package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.test.builders.AccountTestBuilder;
import cn.flying.test.builders.FileTestBuilder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;

/**
 * Tests for FileQueryServiceImpl.
 * Verifies access control, friend share permissions, and query operations.
 *
 * Note: Some tests that require MyBatis-Plus lambda expressions are covered
 * in integration tests (DatabaseIT) due to lambda cache requirements.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("FileQueryService Tests")
class FileQueryServiceTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private FriendFileShareService friendFileShareService;

    @InjectMocks
    private FileQueryServiceImpl fileQueryService;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long FILE_ID = 1L;
    private static final String FILE_HASH = "sha256_test_hash";

    @BeforeEach
    void setUp() {
        FileTestBuilder.resetIdCounter();
        AccountTestBuilder.resetIdCounter();
    }

    // ================== Get File By ID Tests ==================

    @Nested
    @DisplayName("Get File By ID")
    class GetFileById {

        @Test
        @DisplayName("should return file for owner")
        void shouldReturnFileForOwner() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(USER_ID);
                    f.setFileName("test.txt");
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);

                // When
                File result = fileQueryService.getFileById(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals(FILE_ID, result.getId());
                assertEquals("test.txt", result.getFileName());
            }
        }

        @Test
        @DisplayName("should allow admin to access any file")
        void shouldAllowAdminToAccessAnyFile() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(true);

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(OTHER_USER_ID); // Different owner
                    f.setFileName("other_user_file.txt");
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);

                // When
                File result = fileQueryService.getFileById(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals("other_user_file.txt", result.getFileName());
            }
        }

        @Test
        @DisplayName("should reject access to non-existent file")
        void shouldRejectAccessToNonExistentFile() {
            // Given
            when(fileMapper.selectById(FILE_ID)).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileQueryService.getFileById(USER_ID, FILE_ID));

            assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should reject access to others' file for non-admin")
        void shouldRejectAccessToOthersFile() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(OTHER_USER_ID); // Different owner
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileById(USER_ID, FILE_ID));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            }
        }

        @Test
        @DisplayName("should populate origin owner name for shared file")
        void shouldPopulateOriginOwnerName() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                Long originFileId = 999L;
                String expectedOwnerName = "OriginalOwner";

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(USER_ID);
                    f.setOrigin(originFileId);
                });

                File originFile = FileTestBuilder.aFile(f -> {
                    f.setId(originFileId);
                    f.setUid(OTHER_USER_ID);
                });

                Account originOwner = AccountTestBuilder.anAccount(a -> {
                    a.setId(OTHER_USER_ID);
                    a.setUsername(expectedOwnerName);
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);
                when(fileMapper.selectByIdIncludeDeleted(originFileId)).thenReturn(originFile);
                when(accountMapper.selectBatchIds(anyCollection())).thenReturn(List.of(originOwner));

                // When
                File result = fileQueryService.getFileById(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals(expectedOwnerName, result.getOriginOwnerName());
                assertEquals(originFileId, result.getOrigin());
            }
        }

        @Test
        @DisplayName("should populate shared from user name")
        void shouldPopulateSharedFromUserName() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                String expectedSharerName = "SharerUser";

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(USER_ID);
                    f.setSharedFromUserId(OTHER_USER_ID);
                });

                Account sharer = AccountTestBuilder.anAccount(a -> {
                    a.setId(OTHER_USER_ID);
                    a.setUsername(expectedSharerName);
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);
                when(accountMapper.selectBatchIds(anyCollection())).thenReturn(List.of(sharer));

                // When
                File result = fileQueryService.getFileById(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals(expectedSharerName, result.getSharedFromUserName());
                assertEquals(OTHER_USER_ID, result.getSharedFromUserId());
            }
        }
    }

    // ================== Get User Files List Tests ==================

    @Nested
    @DisplayName("Get User Files List")
    class GetUserFilesList {

        @Test
        @DisplayName("should return user files list")
        void shouldReturnUserFilesList() {
            // Given
            File file1 = FileTestBuilder.aFile(f -> f.setUid(USER_ID).setFileName("file1.txt"));
            File file2 = FileTestBuilder.aFile(f -> f.setUid(USER_ID).setFileName("file2.txt"));

            when(fileMapper.selectList(any())).thenReturn(List.of(file1, file2));

            // When
            List<File> result = fileQueryService.getUserFilesList(USER_ID);

            // Then
            assertNotNull(result);
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("should return empty list when no files")
        void shouldReturnEmptyListWhenNoFiles() {
            // Given
            when(fileMapper.selectList(any())).thenReturn(List.of());

            // When
            List<File> result = fileQueryService.getUserFilesList(USER_ID);

            // Then
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // ================== Friend Share Access Tests ==================

    @Nested
    @DisplayName("Friend Share Access")
    class FriendShareAccess {

        @Test
        @DisplayName("should allow access via friend share")
        void shouldAllowAccessViaFriendShare() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File sharerFile = FileTestBuilder.aFile(f -> {
                    f.setUid(OTHER_USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileParam("{\"initialKey\":\"dGVzdGtleQ==\",\"fileSize\":1024}");
                });

                // Use doAnswer to handle different query scenarios based on the wrapper content
                doAnswer((InvocationOnMock invocation) -> {
                    Object wrapper = invocation.getArgument(0);
                    // Return null for user's own file check, return sharer's file for sharer lookup
                    // The implementation uses LambdaQueryWrapper, so we check by invocation count
                    return null;
                }).doAnswer((InvocationOnMock invocation) -> sharerFile)
                        .when(fileMapper).selectOne(any());

                when(friendFileShareService.getSharerIdForFile(USER_ID, FILE_HASH)).thenReturn(OTHER_USER_ID);

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertNotNull(result);
                assertEquals("dGVzdGtleQ==", result.getInitialKey());
                assertEquals(1024L, result.getFileSize());
                assertEquals(FILE_HASH, result.getFileHash());

                // Verify friend share service was called
                verify(friendFileShareService).getSharerIdForFile(USER_ID, FILE_HASH);
            }
        }

        @Test
        @DisplayName("should reject access without friend share")
        void shouldRejectAccessWithoutFriendShare() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                // User doesn't own the file and has no friend share
                when(fileMapper.selectOne(any())).thenReturn(null);
                when(friendFileShareService.getSharerIdForFile(USER_ID, FILE_HASH)).thenReturn(null);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            }
        }

        @Test
        @DisplayName("should reject access when friend share exists but sharer file not found")
        void shouldRejectAccessWhenSharerFileNotFound() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                // User doesn't own the file
                // Friend share exists, but sharer's file is not found
                when(fileMapper.selectOne(any())).thenReturn(null);
                when(friendFileShareService.getSharerIdForFile(USER_ID, FILE_HASH)).thenReturn(OTHER_USER_ID);

                // When & Then - Sharer's file lookup also returns null
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            }
        }
    }

    // ================== Admin Access Tests ==================

    @Nested
    @DisplayName("Admin Access")
    class AdminAccess {

        @Test
        @DisplayName("admin should access any file decrypt info")
        void adminShouldAccessAnyFileDecryptInfo() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(true);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(OTHER_USER_ID); // Different user
                    f.setFileHash(FILE_HASH);
                    f.setFileParam("{\"initialKey\":\"YWRtaW5rZXk=\",\"fileSize\":2048}");
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertNotNull(result);
                assertEquals("YWRtaW5rZXk=", result.getInitialKey());
                // Should not call friendFileShareService for admin
                verify(friendFileShareService, never()).getSharerIdForFile(any(), any());
            }
        }

        @Test
        @DisplayName("admin should get unauthorized error for non-existent file hash")
        void adminShouldGetUnauthorizedForNonExistentFile() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given - Even admin cannot access a file that doesn't exist
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(true);
                when(fileMapper.selectOne(any())).thenReturn(null);

                // When & Then - Throws PERMISSION_UNAUTHORIZED because no file matches the hash
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            }
        }
    }

    // ================== File Decrypt Info Validation Tests ==================

    @Nested
    @DisplayName("File Decrypt Info Validation")
    class FileDecryptInfoValidation {

        @Test
        @DisplayName("should return complete decrypt info")
        void shouldReturnCompleteDecryptInfo() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileName("document.pdf");
                    f.setFileParam("{\"initialKey\":\"c2VjcmV0a2V5\",\"fileSize\":4096,\"contentType\":\"application/pdf\",\"chunkCount\":2}");
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertNotNull(result);
                assertEquals("c2VjcmV0a2V5", result.getInitialKey());
                assertEquals(4096L, result.getFileSize());
                assertEquals("application/pdf", result.getContentType());
                assertEquals(2, result.getChunkCount());
                assertEquals(FILE_HASH, result.getFileHash());
            }
        }

        @Test
        @DisplayName("should use file name from entity if not in params")
        void shouldUseFileNameFromEntity() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileName("entity_name.txt");
                    f.setFileParam("{\"initialKey\":\"a2V5\",\"fileSize\":100}"); // No fileName in params
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertEquals("entity_name.txt", result.getFileName());
            }
        }

        @Test
        @DisplayName("should reject file with missing initial key")
        void shouldRejectFileWithMissingInitialKey() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileParam("{\"fileSize\":1024}"); // Missing initialKey
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
            }
        }

        @Test
        @DisplayName("should reject file with empty file param")
        void shouldRejectFileWithEmptyFileParam() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileParam(""); // Empty param
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
            }
        }

        @Test
        @DisplayName("should reject file with invalid JSON file param")
        void shouldRejectFileWithInvalidJsonFileParam() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileParam("{invalid json}"); // Invalid JSON
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.JSON_PARSE_ERROR.getCode(), ex.getResultEnum().getCode());
            }
        }

        @Test
        @DisplayName("should reject file with null file param")
        void shouldRejectFileWithNullFileParam() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setUid(USER_ID);
                    f.setFileHash(FILE_HASH);
                    f.setFileParam(null); // Null param
                });

                when(fileMapper.selectOne(any())).thenReturn(file);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.FAIL.getCode(), ex.getResultEnum().getCode());
            }
        }
    }
}
