package cn.flying.service.impl;

import cn.flying.common.exception.GeneralException;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("FileQueryService Edge Cases and Error Paths")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileQueryServiceEdgeCaseTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private AccountMapper accountMapper;

    @InjectMocks
    private FileQueryServiceImpl fileQueryService;

    private static final Long USER_ID = 100L;
    private static final Long FILE_ID = 1L;

    @Nested
    @DisplayName("getFileById Edge Cases")
    class GetFileByIdEdgeCases {

        @Test
        @DisplayName("should throw exception when file not found")
        void shouldThrowExceptionWhenFileNotFound() {
            when(fileMapper.selectById(FILE_ID)).thenReturn(null);

            assertThatThrownBy(() -> fileQueryService.getFileById(USER_ID, FILE_ID))
                    .isInstanceOf(GeneralException.class);
        }

        @Test
        @DisplayName("should return file when user is owner")
        void shouldReturnFileWhenUserIsOwner() {
            File file = new File();
            file.setId(FILE_ID);
            file.setUid(USER_ID);
            when(fileMapper.selectById(FILE_ID)).thenReturn(file);

            File result = fileQueryService.getFileById(USER_ID, FILE_ID);
            
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(FILE_ID);
        }
    }

    @Nested
    @DisplayName("getUserFilesList Edge Cases")
    class GetUserFilesListEdgeCases {

        @Test
        @DisplayName("should return empty list when user has no files")
        void shouldReturnEmptyListWhenUserHasNoFiles() {
            when(fileMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<File> result = fileQueryService.getUserFilesList(USER_ID);

            assertThat(result).isEmpty();
        }

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

        @Test
        @DisplayName("should handle negative userId")
        void shouldHandleNegativeUserId() {
            when(fileMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<File> result = fileQueryService.getUserFilesList(-1L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should handle zero userId")
        void shouldHandleZeroUserId() {
            when(fileMapper.selectList(any())).thenReturn(Collections.emptyList());

            List<File> result = fileQueryService.getUserFilesList(0L);

            assertThat(result).isEmpty();
        }
    }
}
