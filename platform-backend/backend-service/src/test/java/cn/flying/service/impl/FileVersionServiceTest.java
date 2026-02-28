package cn.flying.service.impl;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.test.builders.FileTestBuilder;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for file version chain feature in FileServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("File Version Service Tests")
class FileVersionServiceTest {

    @Mock
    private FileMapper fileMapper;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock rLock;

    @InjectMocks
    private FileServiceImpl fileService;

    private static final Long USER_ID = 100L;
    private static final Long TENANT_ID = 1L;

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, File.class);
    }

    @BeforeEach
    void setUp() {
        FileTestBuilder.resetIdCounter();
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);
    }

    @Nested
    @DisplayName("Create New Version")
    class CreateNewVersion {

        @Test
        @DisplayName("should create new version successfully")
        void shouldCreateNewVersionSuccessfully() throws Exception {
            try (MockedStatic<IdUtils> idUtilsMock = mockStatic(IdUtils.class)) {
                // Given
                File parentFile = FileTestBuilder.aFile(f -> {
                    f.setId(1L);
                    f.setUid(USER_ID);
                    f.setTenantId(TENANT_ID);
                    f.setVersion(1);
                    f.setVersionGroupId(1L);
                    f.setStatus(FileUploadStatus.SUCCESS.getCode());
                });

                idUtilsMock.when(IdUtils::nextEntityId).thenReturn(100L);

                when(fileMapper.selectById(1L)).thenReturn(parentFile);
                when(redissonClient.getLock(anyString())).thenReturn(rLock);
                when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
                when(rLock.isHeldByCurrentThread()).thenReturn(true);
                when(fileMapper.clearLatestInChain(1L, TENANT_ID)).thenReturn(1);
                when(fileMapper.insert(any(File.class))).thenReturn(1);

                // When
                File result = fileService.createNewVersion(USER_ID, 1L, "new_version.txt", 2048, "text/plain");

                // Then
                assertNotNull(result);
                assertEquals(2, result.getVersion());
                assertEquals(1L, result.getParentVersionId());
                assertEquals(1, result.getIsLatest());
                assertEquals(1L, result.getVersionGroupId());
                assertEquals(FileUploadStatus.PREPARE.getCode(), result.getStatus());
                assertEquals("new_version.txt", result.getFileName());

                // Verify clearLatestInChain was called
                verify(fileMapper).clearLatestInChain(1L, TENANT_ID);
                // Verify lock was acquired and released
                verify(rLock).tryLock(5, 30, TimeUnit.SECONDS);
                verify(rLock).unlock();
            }
        }

        @Test
        @DisplayName("should throw FILE_NOT_EXIST when parent file not found")
        void shouldThrowWhenParentNotFound() {
            // Given
            when(fileMapper.selectById(999L)).thenReturn(null);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.createNewVersion(USER_ID, 999L, "new.txt", 1024, "text/plain"));

            assertEquals(ResultEnum.FILE_NOT_EXIST.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should throw PERMISSION_UNAUTHORIZED when not owner")
        void shouldThrowWhenNotOwner() {
            // Given
            File parentFile = FileTestBuilder.aFile(f -> {
                f.setId(1L);
                f.setUid(200L); // Different user
                f.setStatus(FileUploadStatus.SUCCESS.getCode());
            });
            when(fileMapper.selectById(1L)).thenReturn(parentFile);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.createNewVersion(USER_ID, 1L, "new.txt", 1024, "text/plain"));

            assertEquals(ResultEnum.PERMISSION_UNAUTHORIZED.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should throw VERSION_SOURCE_INVALID when parent not SUCCESS")
        void shouldThrowWhenParentNotSuccess() {
            // Given
            File parentFile = FileTestBuilder.aFile(f -> {
                f.setId(1L);
                f.setUid(USER_ID);
                f.setStatus(FileUploadStatus.PREPARE.getCode());
            });
            when(fileMapper.selectById(1L)).thenReturn(parentFile);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.createNewVersion(USER_ID, 1L, "new.txt", 1024, "text/plain"));

            assertEquals(ResultEnum.VERSION_SOURCE_INVALID.getCode(), ex.getResultEnum().getCode());
        }

        @Test
        @DisplayName("should throw VERSION_CONFLICT when lock not acquired")
        void shouldThrowWhenLockNotAcquired() throws Exception {
            // Given
            File parentFile = FileTestBuilder.aFile(f -> {
                f.setId(1L);
                f.setUid(USER_ID);
                f.setTenantId(TENANT_ID);
                f.setVersion(1);
                f.setVersionGroupId(1L);
                f.setStatus(FileUploadStatus.SUCCESS.getCode());
            });

            when(fileMapper.selectById(1L)).thenReturn(parentFile);
            when(redissonClient.getLock(anyString())).thenReturn(rLock);
            when(rLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(false);

            // When & Then
            GeneralException ex = assertThrows(GeneralException.class, () ->
                    fileService.createNewVersion(USER_ID, 1L, "new.txt", 1024, "text/plain"));

            assertEquals(ResultEnum.VERSION_CONFLICT.getCode(), ex.getResultEnum().getCode());
        }
    }
}
