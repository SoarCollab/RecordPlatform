package cn.flying.service.impl;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.entity.FriendFileShare;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.vo.file.FileVersionVO;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
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
import java.util.Optional;

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

    @Mock
    private ChunkManifestService chunkManifestService;

    @Mock
    private FileKeyEnvelopeService fileKeyEnvelopeService;

    @InjectMocks
    private FileQueryServiceImpl fileQueryService;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long FILE_ID = 1L;
    private static final String FILE_HASH = "sha256_test_hash";
    private static final String TRANSACTION_HASH = "0xtxhash";

    @BeforeEach
    void setUp() {
        FileTestBuilder.resetIdCounter();
        AccountTestBuilder.resetIdCounter();
    }

    @Nested
    @DisplayName("Download Metadata")
    class DownloadMetadata {

        /**
         * 验证下载 metadata 从已授权文件的 active manifest 生成，并按 manifest 顺序签发 URL。
         */
        @Test
        @DisplayName("should build presigned download metadata from manifest")
        void shouldBuildPresignedDownloadMetadataFromManifest() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class);
                 MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                idUtilsMock.when(() -> IdUtils.toExternalId(FILE_ID)).thenReturn("ext-file-1");
                File ownedFile = new File()
                        .setId(FILE_ID)
                        .setUid(USER_ID)
                        .setFileName("report.pdf")
                        .setFileHash(FILE_HASH)
                        .setFileSize(2048L)
                        .setContentType("application/pdf")
                        .setFileParam("""
                                {"keyEnvelopeStatus":"ENVELOPED","encryptionAlgorithm":"AES-GCM","fileName":"report.pdf","fileSize":2048,"contentType":"application/pdf","chunkCount":2}
                                """);
                ChunkManifestView manifest = new ChunkManifestView(
                        10L,
                        FILE_ID,
                        1,
                        "cn.flying.chunk-manifest.v1",
                        FILE_HASH,
                        "sha256:manifest",
                        "SHA-256",
                        1024L,
                        2048L,
                        null,
                        "AES-GCM",
                        "S3",
                        List.of(
                                new ChunkManifestChunk(0, "plain-0", "cipher-0", 1024L,
                                        "chunks/0", "S3", "etag-0", "SHA-256"),
                                new ChunkManifestChunk(1, "plain-1", "cipher-1", 1024L,
                                        "chunks/1", "S3", "etag-1", "SHA-256")
                        )
                );

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                        ownedFile,
                        FILE_HASH,
                        USER_ID,
                        USER_ID,
                        "OWNER_DECRYPT"
                )).thenReturn(Optional.of("k1"));
                when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.of(manifest));
                when(fileRemoteClient.getFileUrlListByHash(List.of("chunks/0", "chunks/1"),
                        List.of("cipher-0", "cipher-1"))).thenReturn(Result.success(List.of("url-0", "url-1")));

                var metadata = fileQueryService.getDownloadMetadata(USER_ID, FILE_HASH);

                assertEquals("ext-file-1", metadata.fileId());
                assertEquals(FILE_HASH, metadata.fileHash());
                assertEquals("sha256:manifest", metadata.manifestHash());
                assertEquals(2, metadata.totalChunks());
                assertEquals("url-0", metadata.parts().get(0).downloadUrl());
                assertEquals("chunks/1", metadata.parts().get(1).storagePath());
                verify(fileRemoteClient).getFileUrlListByHash(
                        List.of("chunks/0", "chunks/1"),
                        List.of("cipher-0", "cipher-1")
                );
                verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
            }
        }

        /**
         * 验证未加密直传文件的下载 metadata 不要求 initialKey。
         */
        @Test
        @DisplayName("should build download metadata for unencrypted direct upload without key envelope")
        void shouldBuildDownloadMetadataForUnencryptedDirectUploadWithoutKeyEnvelope() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class);
                 MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                idUtilsMock.when(() -> IdUtils.toExternalId(FILE_ID)).thenReturn("ext-file-1");
                File ownedFile = new File()
                        .setId(FILE_ID)
                        .setUid(USER_ID)
                        .setFileName("direct.pdf")
                        .setFileHash(FILE_HASH)
                        .setFileSize(512L)
                        .setContentType("application/pdf")
                        .setFileParam("""
                                {"uploadMode":"DIRECT_MULTIPART","encryptionAlgorithm":"NONE","fileName":"direct.pdf","fileSize":512,"contentType":"application/pdf","chunkCount":1}
                                """);
                ChunkManifestView manifest = new ChunkManifestView(
                        10L,
                        FILE_ID,
                        1,
                        "cn.flying.chunk-manifest.v1",
                        FILE_HASH,
                        "sha256:manifest",
                        "SHA-256",
                        512L,
                        512L,
                        null,
                        "NONE",
                        "S3",
                        List.of(new ChunkManifestChunk(0, "sha256:chunk", "sha256:chunk", 512L,
                                "chunks/0", "S3", "etag-0", "SHA-256"))
                );

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.of(manifest));
                when(fileRemoteClient.getFileUrlListByHash(List.of("chunks/0"), List.of("sha256:chunk")))
                        .thenReturn(Result.success(List.of("url-0")));

                var metadata = fileQueryService.getDownloadMetadata(USER_ID, FILE_HASH);

                assertNull(metadata.initialKey());
                assertEquals("NONE", metadata.encryptionAlgorithm());
                assertEquals("url-0", metadata.parts().getFirst().downloadUrl());
                verify(fileKeyEnvelopeService, never()).unwrapActiveOwnerInitialKey(
                        any(File.class),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString()
                );
            }
        }

        /**
         * 验证缺少 active manifest 时不会继续向对象存储签发 URL。
         */
        @Test
        @DisplayName("should fail when active manifest is missing")
        void shouldFailWhenActiveManifestIsMissing() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                File ownedFile = new File()
                        .setId(FILE_ID)
                        .setUid(USER_ID)
                        .setFileName("report.pdf")
                        .setFileHash(FILE_HASH)
                        .setFileSize(1024L)
                        .setFileParam("""
                                {"keyEnvelopeStatus":"ENVELOPED","encryptionAlgorithm":"AES-GCM","fileName":"report.pdf","fileSize":1024,"contentType":"application/pdf","chunkCount":1}
                                """);

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                        ownedFile,
                        FILE_HASH,
                        USER_ID,
                        USER_ID,
                        "OWNER_DECRYPT"
                )).thenReturn(Optional.of("k1"));
                when(chunkManifestService.findActiveManifest(USER_ID, FILE_ID)).thenReturn(Optional.empty());

                GeneralException ex = assertThrows(GeneralException.class,
                        () -> fileQueryService.getDownloadMetadata(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.FILE_RECORD_ERROR.getCode(), ex.getResultEnum().getCode());
                verify(fileRemoteClient, never()).getFileUrlListByHash(anyList(), anyList());
            }
        }

        /**
         * 验证授权失败时不会读取 manifest 或签发对象存储 URL。
         */
        @Test
        @DisplayName("should not generate urls before authorization")
        void shouldNotGenerateUrlsBeforeAuthorization() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectOne(any())).thenReturn(null);
                when(friendFileShareService.getActiveShareForFile(USER_ID, FILE_HASH)).thenReturn(null);

                GeneralException ex = assertThrows(GeneralException.class,
                        () -> fileQueryService.getDownloadMetadata(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.FILE_NOT_EXIST.getCode(), ex.getResultEnum().getCode());
                verify(chunkManifestService, never()).findActiveManifest(any(), any());
                verify(fileRemoteClient, never()).getFileUrlListByHash(anyList(), anyList());
            }
        }
    }

    @Nested
    @DisplayName("Get Transaction By Hash")
    class GetTransactionByHash {

        /**
         * 验证普通用户只有在本地存在自己的文件交易记录时才能查询链上交易详情。
         */
        @Test
        @DisplayName("should return transaction for owner")
        void shouldReturnTransactionForOwner() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectCount(any())).thenReturn(1L);
                TransactionVO transactionVO = new TransactionVO(
                        TRANSACTION_HASH, "chain", "group", "abi", "from", "to", "input", "sig", "1", 1L);
                when(fileRemoteClient.getTransactionByHash(TRANSACTION_HASH))
                        .thenReturn(Result.success(transactionVO));

                TransactionVO result = fileQueryService.getTransactionByHash(USER_ID, TRANSACTION_HASH);

                assertEquals(transactionVO, result);
                verify(fileRemoteClient).getTransactionByHash(TRANSACTION_HASH);
            }
        }

        /**
         * 验证没有本地文件授权时不会调用链服务，避免按任意 hash 探测他人交易。
         */
        @Test
        @DisplayName("should reject transaction without local file authorization")
        void shouldRejectTransactionWithoutLocalFileAuthorization() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectCount(any())).thenReturn(0L);

                GeneralException ex = assertThrows(GeneralException.class,
                        () -> fileQueryService.getTransactionByHash(USER_ID, TRANSACTION_HASH));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
                verify(fileRemoteClient, never()).getTransactionByHash(anyString());
            }
        }
    }

    @Nested
    @DisplayName("Get File Content")
    class GetFileContent {

        /**
         * 验证本地所有权校验通过后，查询服务会按链上内容映射读取文件分片。
         */
        @Test
        @DisplayName("should return file content for owned file")
        void shouldReturnFileContentForOwnedFile() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                File ownedFile = new File()
                        .setUid(USER_ID)
                        .setFileHash(FILE_HASH)
                        .setFileSize(1024L);
                FileDetailVO detail = new FileDetailVO(
                        String.valueOf(USER_ID),
                        "owned.txt",
                        "{}",
                        "{\"node-a\":\"hash-a\"}",
                        FILE_HASH,
                        "2026-06-27T00:00:00Z",
                        1L,
                        1024L,
                        "text/plain");
                byte[] payload = "payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH)).thenReturn(Result.success(detail));
                when(fileRemoteClient.getFileListByHash(List.of("hash-a"), List.of("node-a")))
                        .thenReturn(Result.success(List.of(payload)));

                List<byte[]> result = fileQueryService.getFile(USER_ID, FILE_HASH);

                assertEquals(1, result.size());
                assertArrayEquals(payload, result.get(0));
                verify(fileRemoteClient).getFileListByHash(List.of("hash-a"), List.of("node-a"));
            }
        }

        /**
         * 验证有序链上内容可以保留重复分片哈希，并按 storagePath 顺序读取。
         */
        @Test
        @DisplayName("should keep duplicate chunk hashes from ordered chain content")
        void shouldKeepDuplicateChunkHashesFromOrderedChainContent() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                File ownedFile = new File()
                        .setUid(USER_ID)
                        .setFileHash(FILE_HASH)
                        .setFileSize(1024L);
                FileDetailVO detail = new FileDetailVO(
                        String.valueOf(USER_ID),
                        "owned.txt",
                        "{}",
                        """
                                [
                                  {"index":0,"cipherHash":"sha256:same","storagePath":"s3://node-a/final-0"},
                                  {"index":1,"cipherHash":"sha256:same","storagePath":"s3://node-a/final-1"}
                                ]
                                """,
                        FILE_HASH,
                        "2026-06-27T00:00:00Z",
                        1L,
                        1024L,
                        "text/plain");
                byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] second = "second".getBytes(java.nio.charset.StandardCharsets.UTF_8);

                when(fileMapper.selectOne(any())).thenReturn(ownedFile);
                when(fileRemoteClient.getFile(String.valueOf(USER_ID), FILE_HASH)).thenReturn(Result.success(detail));
                when(fileRemoteClient.getFileListByHash(
                        List.of("s3://node-a/final-0", "s3://node-a/final-1"),
                        List.of("sha256:same", "sha256:same")))
                        .thenReturn(Result.success(List.of(first, second)));

                List<byte[]> result = fileQueryService.getFile(USER_ID, FILE_HASH);

                assertEquals(2, result.size());
                assertArrayEquals(first, result.get(0));
                assertArrayEquals(second, result.get(1));
                verify(fileRemoteClient).getFileListByHash(
                        List.of("s3://node-a/final-0", "s3://node-a/final-1"),
                        List.of("sha256:same", "sha256:same"));
            }
        }

        /**
         * 验证超出当前内存型下载上限的文件不会进入远端 byte[] 聚合接口。
         */
        @Test
        @DisplayName("should reject oversized in-memory download before remote fetch")
        void shouldRejectOversizedInMemoryDownloadBeforeRemoteFetch() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                when(fileMapper.selectOne(any())).thenReturn(new File()
                        .setUid(USER_ID)
                        .setFileHash(FILE_HASH)
                        .setFileSize(81L * 1024 * 1024));

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFile(USER_ID, FILE_HASH));

                assertEquals(ResultEnum.PARAM_ERROR.getCode(), ex.getResultEnum().getCode());
                verify(fileRemoteClient, never()).getFile(anyString(), anyString());
                verify(fileRemoteClient, never()).getFileListByHash(anyList(), anyList());
            }
        }
    }

    @Nested
    @DisplayName("Public Share File Lookup")
    class PublicShareFileLookup {

        /**
         * 验证公开分享文件列表只读取分享租户内的源文件，并填充展示所有者。
         */
        @Test
        @DisplayName("should return public shared files in share tenant")
        void shouldReturnPublicSharedFilesInShareTenant() {
            FileShare share = new FileShare()
                    .setTenantId(1L)
                    .setUserId(OTHER_USER_ID)
                    .setShareCode("PUBLIC1")
                    .setShareType(cn.flying.common.constant.ShareType.PUBLIC.getCode())
                    .setFileHashes("[\"" + FILE_HASH + "\"]")
                    .setExpireTime(new java.util.Date(System.currentTimeMillis() + 60_000))
                    .setStatus(FileShare.STATUS_ACTIVE);
            File sourceFile = new File()
                    .setId(7L)
                    .setTenantId(1L)
                    .setUid(OTHER_USER_ID)
                    .setFileName("public.txt")
                    .setFileHash(FILE_HASH)
                    .setFileSize(1024L)
                    .setContentType("text/plain");
            Account owner = AccountTestBuilder.anAccount(a -> {
                a.setId(OTHER_USER_ID);
                a.setUsername("owner");
            });

            when(fileShareMapper.markAsExpiredIfNecessary("PUBLIC1")).thenReturn(0);
            when(fileShareMapper.selectByShareCode("PUBLIC1")).thenReturn(share);
            when(fileMapper.selectList(any())).thenReturn(List.of(sourceFile));
            when(accountMapper.selectById(OTHER_USER_ID)).thenReturn(owner);

            List<ShareFileVO> result;
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.toExternalId(7L)).thenReturn("ext_7");
                result = fileQueryService.getShareFile("PUBLIC1");
            }

            assertEquals(1, result.size());
            assertEquals("ext_7", result.get(0).id());
            assertEquals("public.txt", result.get(0).fileName());
            assertEquals("owner", result.get(0).ownerName());
        }

        /**
         * 验证公开分享文件列表入口拒绝空分享码。
         */
        @Test
        @DisplayName("should reject blank public sharing code")
        void shouldRejectBlankPublicSharingCode() {
            GeneralException ex = assertThrows(GeneralException.class,
                    () -> fileQueryService.getShareFile(" "));

            assertEquals(ResultEnum.PARAM_IS_INVALID.getCode(), ex.getResultEnum().getCode());
            verify(fileShareMapper, never()).selectByShareCode(anyString());
        }
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
                    f.setFileParam("{\"fileSize\":1024}");
                });
                FriendFileShare friendShare = createFriendShare();

                // Use doAnswer to handle different query scenarios based on the wrapper content
                doAnswer((InvocationOnMock invocation) -> {
                    Object wrapper = invocation.getArgument(0);
                    // Return null for user's own file check, return sharer's file for sharer lookup
                    // The implementation uses LambdaQueryWrapper, so we check by invocation count
                    return null;
                }).doAnswer((InvocationOnMock invocation) -> sharerFile)
                        .when(fileMapper).selectOne(any());

                when(friendFileShareService.getActiveShareForFile(USER_ID, FILE_HASH)).thenReturn(friendShare);
                when(fileKeyEnvelopeService.unwrapActiveFriendShareInitialKey(
                        sharerFile,
                        FILE_HASH,
                        friendShare,
                        USER_ID,
                        "FRIEND_SHARE_DECRYPT"
                )).thenReturn(Optional.of("friend-envelope-key"));

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertNotNull(result);
                assertEquals("friend-envelope-key", result.initialKey());
                assertEquals(1024L, result.fileSize());
                assertEquals(FILE_HASH, result.fileHash());

                // Verify friend share service was called
                verify(friendFileShareService).getActiveShareForFile(USER_ID, FILE_HASH);
                verify(fileKeyEnvelopeService).unwrapActiveFriendShareInitialKey(
                        sharerFile,
                        FILE_HASH,
                        friendShare,
                        USER_ID,
                        "FRIEND_SHARE_DECRYPT"
                );
                verify(fileKeyEnvelopeService, never()).unwrapActiveOwnerInitialKey(
                        any(File.class),
                        anyString(),
                        anyLong(),
                        anyLong(),
                        anyString()
                );
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
                when(friendFileShareService.getActiveShareForFile(USER_ID, FILE_HASH)).thenReturn(null);

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
                when(friendFileShareService.getActiveShareForFile(USER_ID, FILE_HASH)).thenReturn(createFriendShare());

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
                    f.setFileParam("{\"keyEnvelopeStatus\":\"ENVELOPED\",\"encryptionAlgorithm\":\"AES-GCM\",\"fileSize\":2048}");
                });

                when(fileMapper.selectOne(any())).thenReturn(file);
                when(fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                        file,
                        FILE_HASH,
                        OTHER_USER_ID,
                        USER_ID,
                        "OWNER_DECRYPT"
                )).thenReturn(Optional.of("YWRtaW5rZXk="));

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertNotNull(result);
                assertEquals("YWRtaW5rZXk=", result.initialKey());
                // Should not call friendFileShareService for admin
                verify(friendFileShareService, never()).getActiveShareForFile(any(), any());
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
                    f.setFileParam("{\"keyEnvelopeStatus\":\"ENVELOPED\",\"encryptionAlgorithm\":\"AES-GCM\",\"fileSize\":4096,\"contentType\":\"application/pdf\",\"chunkCount\":2}");
                });

                when(fileMapper.selectOne(any())).thenReturn(file);
                when(fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                        file,
                        FILE_HASH,
                        USER_ID,
                        USER_ID,
                        "OWNER_DECRYPT"
                )).thenReturn(Optional.of("c2VjcmV0a2V5"));

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertNotNull(result);
                assertEquals("c2VjcmV0a2V5", result.initialKey());
                assertEquals(4096L, result.fileSize());
                assertEquals("application/pdf", result.contentType());
                assertEquals(2, result.chunkCount());
                assertEquals(FILE_HASH, result.fileHash());
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
                    f.setFileParam("{\"keyEnvelopeStatus\":\"ENVELOPED\",\"encryptionAlgorithm\":\"AES-GCM\",\"fileSize\":100}"); // No fileName in params
                });

                when(fileMapper.selectOne(any())).thenReturn(file);
                when(fileKeyEnvelopeService.unwrapActiveOwnerInitialKey(
                        file,
                        FILE_HASH,
                        USER_ID,
                        USER_ID,
                        "OWNER_DECRYPT"
                )).thenReturn(Optional.of("a2V5"));

                // When
                var result = fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH);

                // Then
                assertEquals("entity_name.txt", result.fileName());
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

    // ================== File Version History Tests ==================

    @Nested
    @DisplayName("File Version History")
    class FileVersionHistory {

        @Test
        @DisplayName("owner should view version history")
        void ownerShouldViewVersionHistory() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class);
                 MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                idUtilsMock.when(() -> IdUtils.toExternalId(any())).thenReturn("ext_id");

                Long versionGroupId = 10L;
                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(USER_ID);
                    f.setTenantId(1L);
                    f.setVersionGroupId(versionGroupId);
                    f.setVersion(2);
                    f.setIsLatest(1);
                });

                File v1 = FileTestBuilder.aFile(f -> {
                    f.setId(2L);
                    f.setUid(USER_ID);
                    f.setTenantId(1L);
                    f.setVersionGroupId(versionGroupId);
                    f.setVersion(1);
                    f.setIsLatest(0);
                    f.setFileName("v1.txt");
                    f.setStatus(FileUploadStatus.SUCCESS.getCode());
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);
                when(fileMapper.selectVersionChain(versionGroupId, 1L)).thenReturn(List.of(file, v1));

                // When
                List<FileVersionVO> result = fileQueryService.getFileVersionHistory(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals(2, result.size());
                assertEquals(2, result.get(0).version());
                assertEquals(1, result.get(1).version());
            }
        }

        @Test
        @DisplayName("admin should view any user's version history")
        void adminShouldViewAnyVersionHistory() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class);
                 MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(true);
                idUtilsMock.when(() -> IdUtils.toExternalId(any())).thenReturn("ext_id");

                Long versionGroupId = 10L;
                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(OTHER_USER_ID); // Different user
                    f.setTenantId(1L);
                    f.setVersionGroupId(versionGroupId);
                    f.setVersion(1);
                    f.setIsLatest(1);
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);
                when(fileMapper.selectVersionChain(versionGroupId, 1L)).thenReturn(List.of(file));

                // When
                List<FileVersionVO> result = fileQueryService.getFileVersionHistory(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals(1, result.size());
            }
        }

        @Test
        @DisplayName("non-owner non-admin should be rejected")
        void nonOwnerNonAdminShouldBeRejected() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(OTHER_USER_ID); // Different user
                    f.setVersionGroupId(10L);
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);

                // When & Then
                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileVersionHistory(USER_ID, FILE_ID));

                assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
            }
        }

        @Test
        @DisplayName("legacy file without versionGroupId should return single entry")
        void legacyFileShouldReturnSingleEntry() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class);
                 MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                // Given
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);
                idUtilsMock.when(() -> IdUtils.toExternalId(any())).thenReturn("ext_id");

                File file = FileTestBuilder.aFile(f -> {
                    f.setId(FILE_ID);
                    f.setUid(USER_ID);
                    f.setVersionGroupId(null); // Legacy file
                    f.setVersion(null);
                    f.setIsLatest(null);
                });

                when(fileMapper.selectById(FILE_ID)).thenReturn(file);

                // When
                List<FileVersionVO> result = fileQueryService.getFileVersionHistory(USER_ID, FILE_ID);

                // Then
                assertNotNull(result);
                assertEquals(1, result.size());
                assertEquals(1, result.get(0).version()); // Default to 1
                assertEquals(1, result.get(0).isLatest()); // Default to 1
                // Should not call selectVersionChain
                verify(fileMapper, never()).selectVersionChain(any(), any());
            }
        }

        @Test
        @DisplayName("should throw FILE_NOT_EXIST when file not found")
        void shouldThrowWhenFileNotFound() {
            // Given
            when(fileMapper.selectById(999L)).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileQueryService.getFileVersionHistory(USER_ID, 999L));

            assertEquals(ResultEnum.FILE_NOT_EXIST.getCode(), ex.getResultEnum().getCode());
        }
    }

    /**
     * Creates an active friend-share fixture for decrypt-boundary tests.
     */
    private FriendFileShare createFriendShare() {
        return new FriendFileShare()
                .setId(900L)
                .setTenantId(1L)
                .setSharerId(OTHER_USER_ID)
                .setFriendId(USER_ID)
                .setFileHashes("[\"" + FILE_HASH + "\"]")
                .setStatus(FriendFileShare.STATUS_ACTIVE)
                .setIsRead(0);
    }
}
