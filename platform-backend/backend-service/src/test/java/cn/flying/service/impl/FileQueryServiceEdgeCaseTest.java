package cn.flying.service.impl;

import cn.flying.common.constant.ShareType;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.service.FriendFileShareService;
import cn.flying.service.key.FileKeyEnvelopeService;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.remote.FileRemoteClient;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FileQueryService Edge Cases and Error Paths")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileQueryServiceEdgeCaseTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private FileShareMapper fileShareMapper;

    @Mock
    private AccountMapper accountMapper;

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
    private static final String FILE_HASH = "sha256_edge_hash";
    private static final String SHARE_CODE = "EDGE01";

    /**
     * 构造本地分享记录，供公开分享元数据路径测试使用。
     */
    private FileShare createShare(Integer status, Date expireTime, Integer shareType) {
        return new FileShare()
                .setId(1L)
                .setTenantId(1L)
                .setUserId(100L)
                .setShareCode(SHARE_CODE)
                .setShareType(shareType)
                .setFileHashes("[\"" + FILE_HASH + "\"]")
                .setStatus(status)
                .setExpireTime(expireTime)
                .setAccessCount(0);
    }

    @Nested
    @DisplayName("getFileById Edge Cases")
    class GetFileByIdEdgeCases {

        /**
         * 验证文件不存在时会抛出业务异常。
         */
        @Test
        @DisplayName("should throw exception when file not found")
        void shouldThrowExceptionWhenFileNotFound() {
            when(fileMapper.selectById(FILE_ID)).thenReturn(null);

            assertThrows(GeneralException.class, () -> fileQueryService.getFileById(USER_ID, FILE_ID));
        }

        /**
         * 验证文件属于当前用户时可正常返回。
         */
        @Test
        @DisplayName("should return file when user is owner")
        void shouldReturnFileWhenUserIsOwner() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = new File();
                file.setId(FILE_ID);
                file.setUid(USER_ID);
                when(fileMapper.selectById(FILE_ID)).thenReturn(file);

                File result = fileQueryService.getFileById(USER_ID, FILE_ID);

                assertThat(result).isNotNull();
                assertThat(result.getId()).isEqualTo(FILE_ID);
            }
        }
    }

    @Nested
    @DisplayName("getUserFilesList Edge Cases")
    class GetUserFilesListEdgeCases {

        /**
         * 验证用户无文件时返回空列表。
         */
        @Test
        @DisplayName("should return empty list when user has no files")
        void shouldReturnEmptyListWhenUserHasNoFiles() {
            when(fileMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<File> result = fileQueryService.getUserFilesList(USER_ID);

            assertThat(result).isEmpty();
        }

        /**
         * 验证大量文件场景下仍可正常返回。
         */
        @Test
        @DisplayName("should handle large number of files")
        void shouldHandleLargeNumberOfFiles() {
            List<File> manyFiles = new java.util.ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                File file = new File();
                file.setId((long) i);
                file.setUid(USER_ID);
                manyFiles.add(file);
            }
            when(fileMapper.selectList(any())).thenReturn(manyFiles);

            List<File> result = fileQueryService.getUserFilesList(USER_ID);

            assertThat(result).hasSize(1000);
        }
    }

    @Nested
    @DisplayName("Input Validation Tests")
    class InputValidationTests {

        /**
         * 验证负数 userId 在查询列表时不会抛错并返回空集合。
         */
        @Test
        @DisplayName("should handle negative userId")
        void shouldHandleNegativeUserId() {
            when(fileMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<File> result = fileQueryService.getUserFilesList(-1L);

            assertThat(result).isEmpty();
        }

        /**
         * 验证零值 userId 在查询列表时不会抛错并返回空集合。
         */
        @Test
        @DisplayName("should handle zero userId")
        void shouldHandleZeroUserId() {
            when(fileMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<File> result = fileQueryService.getUserFilesList(0L);

            assertThat(result).isEmpty();
        }

        /**
         * 验证时间范围非法（startTime > endTime）时会抛出参数异常。
         */
        @Test
        @DisplayName("should throw when startTime is after endTime")
        void shouldThrowWhenStartTimeIsAfterEndTime() {
            Date startTime = new Date(2000L);
            Date endTime = new Date(1000L);

            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileQueryService.getUserFilesPage(
                            USER_ID,
                            new Page<>(1, 10),
                            null,
                            null,
                            null,
                            startTime,
                            endTime
                    ));

            assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.PARAM_IS_INVALID);
            verify(fileMapper, never()).selectPage(any(), any());
        }

        /**
         * 验证 AUTO 模式可被正常解析并继续执行查询。
         */
        @Test
        @DisplayName("should accept auto keyword mode")
        void shouldAcceptAutoKeywordMode() {
            assertDoesNotThrow(() ->
                    fileQueryService.getUserFilesPage(
                            USER_ID,
                            new Page<>(1, 10),
                            "abc1234",
                            "AUTO",
                            null,
                            null,
                            null
                    ));

            verify(fileMapper).selectPage(any(), any());
        }

        /**
         * 验证非法 keywordMode 会抛出参数异常并阻止查询落库。
         */
        @Test
        @DisplayName("should throw when keywordMode is invalid")
        void shouldThrowWhenKeywordModeIsInvalid() {
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileQueryService.getUserFilesPage(
                            USER_ID,
                            new Page<>(1, 10),
                            "report",
                            "UNKNOWN_MODE",
                            null,
                            null,
                            null
                    ));

            assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.PARAM_IS_INVALID);
            verify(fileMapper, never()).selectPage(any(), any());
        }
    }

    @Nested
    @DisplayName("Share Query Status Edge Cases")
    class ShareQueryStatusEdgeCases {

        /**
         * 验证分享过期时间为负值时会抛出“分享已取消”异常。
         */
        @Test
        @DisplayName("should throw cancelled when share expiration is negative")
        void shouldThrowCancelledWhenShareExpirationIsNegative() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(createShare(FileShare.STATUS_CANCELLED, null, ShareType.PUBLIC.getCode()));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileQueryService.getShareFile(SHARE_CODE));

            assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.SHARE_CANCELLED);
        }

        /**
         * 验证分享过期时间已过时会抛出“分享已过期”异常。
         */
        @Test
        @DisplayName("should throw expired when share expiration has passed")
        void shouldThrowExpiredWhenShareExpirationHasPassed() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(createShare(
                            FileShare.STATUS_ACTIVE,
                            new Date(System.currentTimeMillis() - 2000),
                            ShareType.PUBLIC.getCode()));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileQueryService.getShareFile(SHARE_CODE));

            assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.SHARE_EXPIRED);
        }

        /**
         * 验证 isValid=false 时会抛出“分享已取消”异常。
         */
        @Test
        @DisplayName("should throw cancelled when share valid flag is false")
        void shouldThrowCancelledWhenShareValidFlagIsFalse() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(createShare(FileShare.STATUS_CANCELLED, null, ShareType.PUBLIC.getCode()));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileQueryService.getShareFile(SHARE_CODE));

            assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.SHARE_CANCELLED);
        }

        /**
         * 验证公开分享文件列表不会返回私密分享数据。
         */
        @Test
        @DisplayName("should reject private share on public metadata lookup")
        void shouldRejectPrivateShareOnPublicMetadataLookup() {
            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(createShare(FileShare.STATUS_ACTIVE, null, ShareType.PRIVATE.getCode()));

            GeneralException ex = assertThrows(GeneralException.class, () -> fileQueryService.getShareFile(SHARE_CODE));

            assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("FileDecryptInfo Edge Cases")
    class FileDecryptInfoEdgeCases {

        /**
         * 验证好友分享存在但分享者文件不存在时会抛出无权限异常。
         */
        @Test
        @DisplayName("should reject when friend share exists but sharer file is missing")
        void shouldRejectWhenFriendShareExistsButSharerFileIsMissing() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                when(fileMapper.selectOne(any())).thenReturn(null);
                when(friendFileShareService.getSharerIdForFile(USER_ID, FILE_HASH)).thenReturn(OTHER_USER_ID);

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.PERMISSION_UNAUTHORIZED);
            }
        }

        /**
         * 验证 fileParam 非法 JSON 时会抛出解析失败异常。
         */
        @Test
        @DisplayName("should throw when fileParam is invalid json")
        void shouldThrowWhenFileParamIsInvalidJson() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = new File();
                file.setId(FILE_ID);
                file.setUid(USER_ID);
                file.setFileHash(FILE_HASH);
                file.setFileName("edge.txt");
                file.setFileParam("{invalid-json}");

                when(fileMapper.selectOne(any())).thenReturn(file);

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.JSON_PARSE_ERROR);
                assertThat(ex.getData()).isNotNull();
            }
        }

        /**
         * 验证 fileParam 缺少 initialKey 时会抛出缺少解密密钥异常。
         */
        @Test
        @DisplayName("should throw when initialKey is missing from fileParam")
        void shouldThrowWhenInitialKeyIsMissingFromFileParam() {
            try (MockedStatic<SecurityUtils> securityUtilsMock = mockStatic(SecurityUtils.class)) {
                securityUtilsMock.when(SecurityUtils::isAdmin).thenReturn(false);

                File file = new File();
                file.setId(FILE_ID);
                file.setUid(USER_ID);
                file.setFileHash(FILE_HASH);
                file.setFileName("edge.txt");
                file.setFileParam("{\"fileName\":\"edge.txt\",\"contentType\":\"text/plain\"}");

                when(fileMapper.selectOne(any())).thenReturn(file);

                GeneralException ex = assertThrows(GeneralException.class, () ->
                        fileQueryService.getFileDecryptInfo(USER_ID, FILE_HASH));

                assertThat(ex.getResultEnum()).isEqualTo(ResultEnum.FAIL);
                assertThat(ex.getData()).isEqualTo("文件解密密钥不存在");
            }
        }
    }

    @Nested
    @DisplayName("Share Owner Name Fill")
    class ShareOwnerNameFill {

        /**
         * 验证公开分享查询成功时，会填充 ownerName 字段。
         */
        @Test
        @DisplayName("should fill ownerName when shared files are found")
        void shouldFillOwnerNameWhenSharedFilesAreFound() {
            File file = new File();
            file.setId(FILE_ID);
            file.setUid(100L);
            file.setFileHash(FILE_HASH);
            file.setFileName("shared.txt");
            file.setFileParam("{\"initialKey\":\"secret\"}");

            Account owner = new Account();
            owner.setId(100L);
            owner.setUsername("owner-name");

            when(fileShareMapper.selectByShareCode(SHARE_CODE))
                    .thenReturn(createShare(FileShare.STATUS_ACTIVE, null, ShareType.PUBLIC.getCode()));
            when(fileMapper.selectList(any())).thenReturn(List.of(file));
            when(accountMapper.selectById(100L)).thenReturn(owner);

            List<ShareFileVO> result;
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                idUtilsMock.when(() -> IdUtils.toExternalId(FILE_ID)).thenReturn("ext_1");
                result = fileQueryService.getShareFile(SHARE_CODE);
            }

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("ext_1");
            assertThat(result.get(0).ownerName()).isEqualTo("owner-name");
            assertThat(result.get(0).fileName()).isEqualTo("shared.txt");
        }
    }
}
