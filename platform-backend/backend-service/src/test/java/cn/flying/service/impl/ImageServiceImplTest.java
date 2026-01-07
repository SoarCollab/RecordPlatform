package cn.flying.service.impl;

import cn.flying.common.util.Const;
import cn.flying.common.util.FlowUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.ImageStore;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.ImageStoreMapper;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import okhttp3.Headers;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ImageServiceImpl Unit Tests
 *
 * Tests for image service including:
 * - Avatar upload and replacement
 * - Cache image upload with rate limiting
 * - Image fetching from S3
 * - Old image deletion
 */
@DisplayName("ImageServiceImpl Tests")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageServiceImplTest {

    @Mock
    private MinioClient s3Client;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private ImageStoreMapper imageStoreMapper;

    @Mock
    private FlowUtils flowUtils;

    @Spy
    @InjectMocks
    private ImageServiceImpl imageService;

    private static final String BUCKET_NAME = "test-bucket";
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "bucketName", BUCKET_NAME);
    }

    @Nested
    @DisplayName("uploadAvatar Tests")
    class UploadAvatarTests {

        @Test
        @DisplayName("should upload avatar and update user account")
        void uploadAvatar_success() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, "/avatar/old-avatar");

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);

            String result = imageService.uploadAvatar(file, USER_ID);

            assertThat(result).isNotNull();
            assertThat(result).startsWith("/avatar/");
            verify(s3Client).putObject(any(PutObjectArgs.class));
            verify(accountMapper).update(isNull(), any());
        }

        @Test
        @DisplayName("should delete old avatar when uploading new one")
        void uploadAvatar_deletesOldAvatar() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, "/avatar/old-avatar");

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);

            imageService.uploadAvatar(file, USER_ID);

            ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
            verify(s3Client).removeObject(captor.capture());
            assertThat(captor.getValue().object()).isEqualTo("/avatar/old-avatar");
        }

        @Test
        @DisplayName("should not delete if old avatar is null")
        void uploadAvatar_noDeleteWhenOldAvatarNull() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, null);

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);

            imageService.uploadAvatar(file, USER_ID);

            verify(s3Client, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("should not delete if old avatar is empty string")
        void uploadAvatar_noDeleteWhenOldAvatarEmpty() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, "");

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);

            imageService.uploadAvatar(file, USER_ID);

            verify(s3Client, never()).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("should return null when database update fails")
        void uploadAvatar_returnNullWhenUpdateFails() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, "/avatar/old-avatar");

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(0);

            String result = imageService.uploadAvatar(file, USER_ID);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null when S3 upload fails")
        void uploadAvatar_returnNullWhenS3Fails() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");

            doThrow(new RuntimeException("S3 error")).when(s3Client).putObject(any(PutObjectArgs.class));

            String result = imageService.uploadAvatar(file, USER_ID);

            assertThat(result).isNull();
            verify(accountMapper, never()).update(any(), any());
        }

        @Test
        @DisplayName("should generate unique avatar path with UUID")
        void uploadAvatar_generatesUniquePath() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, null);

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);

            String result1 = imageService.uploadAvatar(file, USER_ID);
            String result2 = imageService.uploadAvatar(file, USER_ID);

            assertThat(result1).isNotEqualTo(result2);
        }
    }

    @Nested
    @DisplayName("uploadImage Tests")
    class UploadImageTests {

        @Test
        @DisplayName("should upload image with rate limiting check")
        void uploadImage_success() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            String rateKey = Const.IMAGE_COUNTER + USER_ID;

            when(flowUtils.limitPeriodCountCheck(rateKey, 20, 3600)).thenReturn(true);
            doReturn(true).when(imageService).save(any(ImageStore.class));

            String result = imageService.uploadImage(file, USER_ID);

            assertThat(result).isNotNull();
            assertThat(result).startsWith("/cache");
            verify(s3Client).putObject(any(PutObjectArgs.class));
            verify(imageService).save(any(ImageStore.class));
        }

        @Test
        @DisplayName("should return null when rate limit exceeded")
        void uploadImage_returnNullWhenRateLimited() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            String rateKey = Const.IMAGE_COUNTER + USER_ID;

            when(flowUtils.limitPeriodCountCheck(rateKey, 20, 3600)).thenReturn(false);

            String result = imageService.uploadImage(file, USER_ID);

            assertThat(result).isNull();
            verify(s3Client, never()).putObject(any(PutObjectArgs.class));
        }

        @Test
        @DisplayName("should delete uploaded image when database save fails")
        void uploadImage_deletesOnSaveFailure() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            String rateKey = Const.IMAGE_COUNTER + USER_ID;

            when(flowUtils.limitPeriodCountCheck(rateKey, 20, 3600)).thenReturn(true);
            doReturn(false).when(imageService).save(any(ImageStore.class));

            String result = imageService.uploadImage(file, USER_ID);

            assertThat(result).isNull();
            verify(s3Client).removeObject(any(RemoveObjectArgs.class));
        }

        @Test
        @DisplayName("should return null when S3 upload fails")
        void uploadImage_returnNullWhenS3Fails() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            String rateKey = Const.IMAGE_COUNTER + USER_ID;

            when(flowUtils.limitPeriodCountCheck(rateKey, 20, 3600)).thenReturn(true);
            doThrow(new RuntimeException("S3 error")).when(s3Client).putObject(any(PutObjectArgs.class));

            String result = imageService.uploadImage(file, USER_ID);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should include date in image path")
        void uploadImage_includesDateInPath() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            String rateKey = Const.IMAGE_COUNTER + USER_ID;

            when(flowUtils.limitPeriodCountCheck(rateKey, 20, 3600)).thenReturn(true);
            doReturn(true).when(imageService).save(any(ImageStore.class));

            String result = imageService.uploadImage(file, USER_ID);

            assertThat(result).matches("/cache\\d{8}/[a-f0-9]+");
        }
    }

    @Nested
    @DisplayName("fetchImage Tests")
    class FetchImageTests {

        @Test
        @DisplayName("should fetch image and write to output stream")
        void fetchImage_success() throws Exception {
            String imagePath = "/avatar/test-image";
            byte[] imageData = "fake image data".getBytes();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            GetObjectResponse mockResponse = mock(GetObjectResponse.class);
            when(mockResponse.read(any(byte[].class))).thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                System.arraycopy(imageData, 0, buffer, 0, Math.min(imageData.length, buffer.length));
                return imageData.length;
            }).thenReturn(-1);
            when(mockResponse.read(any(byte[].class), anyInt(), anyInt())).thenAnswer(invocation -> {
                byte[] buffer = invocation.getArgument(0);
                int offset = invocation.getArgument(1);
                int length = invocation.getArgument(2);
                int toCopy = Math.min(imageData.length, length);
                System.arraycopy(imageData, 0, buffer, offset, toCopy);
                return toCopy;
            }).thenReturn(-1);

            when(s3Client.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

            imageService.fetchImage(outputStream, imagePath);

            ArgumentCaptor<GetObjectArgs> captor = ArgumentCaptor.forClass(GetObjectArgs.class);
            verify(s3Client).getObject(captor.capture());
            assertThat(captor.getValue().object()).isEqualTo(imagePath);
            assertThat(captor.getValue().bucket()).isEqualTo(BUCKET_NAME);
        }

        @Test
        @DisplayName("should throw exception when S3 object not found")
        void fetchImage_throwsWhenNotFound() throws Exception {
            String imagePath = "/avatar/nonexistent";
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            doThrow(new RuntimeException("Object not found"))
                    .when(s3Client).getObject(any(GetObjectArgs.class));

            Assertions.assertThrows(Exception.class, () ->
                    imageService.fetchImage(outputStream, imagePath));
        }
    }

    @Nested
    @DisplayName("Rate Limiting Tests")
    class RateLimitingTests {

        @Test
        @DisplayName("should check rate limit with correct parameters")
        void checkRateLimit_correctParams() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            String expectedKey = Const.IMAGE_COUNTER + USER_ID;

            when(flowUtils.limitPeriodCountCheck(expectedKey, 20, 3600)).thenReturn(false);

            imageService.uploadImage(file, USER_ID);

            verify(flowUtils).limitPeriodCountCheck(expectedKey, 20, 3600);
        }

        @Test
        @DisplayName("should use unique rate limit key per user")
        void checkRateLimit_uniqueKeyPerUser() throws Exception {
            MultipartFile file = createMockFile("image.png", "image/png");
            Long user1 = 100L;
            Long user2 = 200L;

            when(flowUtils.limitPeriodCountCheck(anyString(), anyInt(), anyInt())).thenReturn(false);

            imageService.uploadImage(file, user1);
            imageService.uploadImage(file, user2);

            verify(flowUtils).limitPeriodCountCheck(Const.IMAGE_COUNTER + user1, 20, 3600);
            verify(flowUtils).limitPeriodCountCheck(Const.IMAGE_COUNTER + user2, 20, 3600);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty file gracefully")
        void uploadAvatar_emptyFile() throws Exception {
            MultipartFile file = new MockMultipartFile("file", "empty.png", "image/png", new byte[0]);
            Account existingAccount = createAccount(USER_ID, null);

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);

            String result = imageService.uploadAvatar(file, USER_ID);

            // Should still attempt upload
            verify(s3Client).putObject(any(PutObjectArgs.class));
        }

        @Test
        @DisplayName("should handle deletion error gracefully")
        void deleteOldImage_handlesErrorGracefully() throws Exception {
            MultipartFile file = createMockFile("avatar.png", "image/png");
            Account existingAccount = createAccount(USER_ID, "/avatar/old-avatar");

            when(accountMapper.selectById(USER_ID)).thenReturn(existingAccount);
            when(accountMapper.update(isNull(), any())).thenReturn(1);
            doThrow(new RuntimeException("Delete error")).when(s3Client).removeObject(any(RemoveObjectArgs.class));

            // Should not throw, deletion error is logged but not propagated
            String result = imageService.uploadAvatar(file, USER_ID);

            assertThat(result).isNotNull();
        }
    }

    // Helper methods

    private MultipartFile createMockFile(String filename, String contentType) {
        return new MockMultipartFile("file", filename, contentType, "test content".getBytes());
    }

    private Account createAccount(Long userId, String avatar) {
        Account account = new Account();
        account.setId(userId);
        account.setUsername("testuser");
        account.setAvatar(avatar);
        return account;
    }
}
