package cn.flying.service.impl;

import cn.flying.common.util.Const;
import cn.flying.common.util.FlowUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.ImageStore;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.ImageStoreMapper;
import io.minio.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ImageServiceImplTest {

    @InjectMocks
    private ImageServiceImpl imageService;

    @Mock
    private ImageStoreMapper imageStoreMapper;

    @Mock
    private MinioClient minioClient;

    @Mock
    private AccountMapper accountMapper;

    @Mock
    private FlowUtils flowUtils;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "bucketName", "test-bucket");
        ReflectionTestUtils.setField(imageService, "baseMapper", imageStoreMapper);
        lenient().when(imageStoreMapper.insert(any(ImageStore.class))).thenReturn(1);
    }

    // ============= 头像上传测试 =============

    @Test
    void uploadAvatar_successfulUpload() throws Exception {
        // 准备测试数据
        String userId = "123";
        byte[] imageData = "test image data".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.jpg",
                "image/jpeg",
                imageData
        );

        // Mock账户查询
        Account account = new Account();
        account.setId(123L);
        account.setAvatar(null);
        when(accountMapper.selectById(userId)).thenReturn(account);

        // Mock MinIO操作
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
        when(accountMapper.update(isNull(), any())).thenReturn(1);

        // 执行测试
        String result = imageService.uploadAvatar(file, userId);

        // 验证结果
        assertNotNull(result);
        assertTrue(result.startsWith("/avatar/"));
        assertFalse(result.contains("-")); // UUID中的横线应该被移除

        // 验证MinIO调用
        ArgumentCaptor<PutObjectArgs> putObjectCaptor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(putObjectCaptor.capture());
        PutObjectArgs putArgs = putObjectCaptor.getValue();
        assertThat(putArgs.bucket()).isEqualTo("test-bucket");
        assertThat(putArgs.object()).startsWith("/avatar/");

        // 验证数据库更新
        verify(accountMapper).update(isNull(), any());
    }

    @Test
    void uploadAvatar_replacesExistingAvatar() throws Exception {
        // 准备测试数据
        String userId = "123";
        String oldAvatar = "/avatar/old_avatar";
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "new_avatar.jpg",
                "image/jpeg",
                "new image".getBytes()
        );

        // Mock账户有旧头像
        Account account = new Account();
        account.setId(123L);
        account.setAvatar(oldAvatar);
        when(accountMapper.selectById(userId)).thenReturn(account);

        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
        when(accountMapper.update(isNull(), any())).thenReturn(1);

        // 执行测试
        String result = imageService.uploadAvatar(file, userId);

        // 验证结果
        assertNotNull(result);

        // 验证删除旧头像
        ArgumentCaptor<RemoveObjectArgs> removeCaptor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(minioClient).removeObject(removeCaptor.capture());
        RemoveObjectArgs removeArgs = removeCaptor.getValue();
        assertThat(removeArgs.bucket()).isEqualTo("test-bucket");
        assertThat(removeArgs.object()).isEqualTo(oldAvatar);
    }

    @Test
    void uploadAvatar_returnsNullOnMinioError() throws Exception {
        // 准备测试数据
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.jpg",
                "image/jpeg",
                "image data".getBytes()
        );

        Account account = new Account();
        account.setId(123L);
        when(accountMapper.selectById(userId)).thenReturn(account);

        // Mock MinIO上传失败
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO error"));

        // 执行测试
        String result = imageService.uploadAvatar(file, userId);

        // 验证返回null
        assertNull(result);

        // 验证没有更新数据库
        verify(accountMapper, never()).update(any(), any());
    }

    @Test
    void uploadAvatar_returnsNullOnDatabaseUpdateFailure() throws Exception {
        // 准备测试数据
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.jpg",
                "image/jpeg",
                "image data".getBytes()
        );

        Account account = new Account();
        account.setId(123L);
        when(accountMapper.selectById(userId)).thenReturn(account);

        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        // Mock数据库更新失败
        when(accountMapper.update(isNull(), any())).thenReturn(0);

        // 执行测试
        String result = imageService.uploadAvatar(file, userId);

        // 验证返回null
        assertNull(result);
    }

    // ============= 图片上传测试 =============

    @Test
    void uploadImage_successfulUpload() throws Exception {
        // 准备测试数据
        String userId = "123";
        byte[] imageData = "test image".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.png",
                "image/png",
                imageData
        );

        // Mock流控检查通过
        when(flowUtils.limitPeriodCountCheck(eq(Const.IMAGE_COUNTER + userId), eq(20), eq(3600)))
                .thenReturn(true);

        // Mock MinIO和数据库操作
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
        when(imageStoreMapper.insert(any(ImageStore.class))).thenReturn(1);

        // 执行测试
        String result = imageService.uploadImage(file, userId);

        // 验证结果
        assertNotNull(result);
        assertTrue(result.startsWith("/cache"));
        assertTrue(result.contains("/")); // 包含日期分隔

        // 验证MinIO调用
        verify(minioClient).putObject(any(PutObjectArgs.class));

        // 验证数据库插入
        ArgumentCaptor<ImageStore> storeCaptor = ArgumentCaptor.forClass(ImageStore.class);
        verify(imageStoreMapper).insert(storeCaptor.capture());
        ImageStore savedStore = storeCaptor.getValue();
        assertThat(savedStore.getName()).isEqualTo(result);
        assertNotNull(savedStore.getTime());
    }

    @Test
    void uploadImage_returnsNullWhenRateLimited() throws Exception {
        // 准备测试数据
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.png",
                "image/png",
                "image data".getBytes()
        );

        // Mock流控检查失败
        when(flowUtils.limitPeriodCountCheck(eq(Const.IMAGE_COUNTER + userId), eq(20), eq(3600)))
                .thenReturn(false);

        // 执行测试
        String result = imageService.uploadImage(file, userId);

        // 验证返回null
        assertNull(result);

        // 验证没有调用MinIO和数据库
        verify(minioClient, never()).putObject(any());
        verify(imageStoreMapper, never()).insert(any(ImageStore.class));
    }

    @Test
    void uploadImage_returnsNullAndDeletesImageOnDatabaseError() throws Exception {
        // 准备测试数据
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.png",
                "image/png",
                "image data".getBytes()
        );

        // Mock流控通过
        when(flowUtils.limitPeriodCountCheck(any(), eq(20), eq(3600))).thenReturn(true);

        // Mock MinIO成功但数据库失败
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
        when(imageStoreMapper.insert(any(ImageStore.class))).thenReturn(0);

        // 执行测试
        String result = imageService.uploadImage(file, userId);

        // 验证返回null
        assertNull(result);

        // 验证删除已上传的图片
        verify(minioClient).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void uploadImage_returnsNullOnMinioError() throws Exception {
        // 准备测试数据
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "test.png",
                "image/png",
                "image data".getBytes()
        );

        when(flowUtils.limitPeriodCountCheck(any(), eq(20), eq(3600))).thenReturn(true);

        // Mock MinIO上传失败
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("Upload failed"));

        // 执行测试
        String result = imageService.uploadImage(file, userId);

        // 验证返回null
        assertNull(result);

        // 验证没有数据库操作
        verify(imageStoreMapper, never()).insert(any(ImageStore.class));
    }

    // ============= 图片获取测试 =============

    @Test
    void fetchImage_successfullyFetchesImage() throws Exception {
        // 准备测试数据
        String imagePath = "/cache20241215/testimage";
        byte[] imageData = "image content".getBytes();
        InputStream imageStream = new ByteArrayInputStream(imageData);

        // Mock MinIO响应
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(mockResponse.read(any(byte[].class))).thenAnswer(invocation -> {
            byte[] buffer = invocation.getArgument(0);
            return imageStream.read(buffer);
        });
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        // 准备输出流
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // 执行测试
        imageService.fetchImage(outputStream, imagePath);

        // 验证输出内容
        assertThat(outputStream.toByteArray()).isEqualTo(imageData);

        // 验证MinIO调用
        ArgumentCaptor<GetObjectArgs> getCaptor = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minioClient).getObject(getCaptor.capture());
        GetObjectArgs getArgs = getCaptor.getValue();
        assertThat(getArgs.bucket()).isEqualTo("test-bucket");
        assertThat(getArgs.object()).isEqualTo(imagePath);
    }

    @Test
    void fetchImage_throwsExceptionWhenImageNotFound() throws Exception {
        // 准备测试数据
        String imagePath = "/nonexistent/image";
        OutputStream outputStream = new ByteArrayOutputStream();

        // Mock MinIO抛出异常
        when(minioClient.getObject(any(GetObjectArgs.class)))
                .thenThrow(new RuntimeException("Object not found"));

        // 执行测试并验证异常
        assertThatThrownBy(() -> imageService.fetchImage(outputStream, imagePath))
                .isInstanceOf(Exception.class);
    }

    @Test
    void fetchImage_handlesEmptyImage() throws Exception {
        // 准备空图片数据
        String imagePath = "/empty/image";
        byte[] emptyData = new byte[0];
        InputStream emptyStream = new ByteArrayInputStream(emptyData);

        // Mock MinIO响应
        GetObjectResponse mockResponse = mock(GetObjectResponse.class);
        when(mockResponse.read(any(byte[].class))).thenReturn(-1); // 表示流结束
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(mockResponse);

        // 准备输出流
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // 执行测试
        imageService.fetchImage(outputStream, imagePath);

        // 验证输出为空
        assertThat(outputStream.toByteArray()).isEmpty();
    }

    // ============= 私有方法测试（通过公有方法间接测试）=============

    @Test
    void deleteOldImage_handlesNullAvatar() throws Exception {
        // 通过uploadAvatar测试，当旧头像为null时不删除
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.jpg",
                "image/jpeg",
                "image".getBytes()
        );

        Account account = new Account();
        account.setId(123L);
        account.setAvatar(null); // 没有旧头像
        when(accountMapper.selectById(userId)).thenReturn(account);

        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
        when(accountMapper.update(isNull(), any())).thenReturn(1);

        // 执行测试
        imageService.uploadAvatar(file, userId);

        // 验证没有尝试删除
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteOldImage_handlesEmptyAvatar() throws Exception {
        // 通过uploadAvatar测试，当旧头像为空字符串时不删除
        String userId = "123";
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.jpg",
                "image/jpeg",
                "image".getBytes()
        );

        Account account = new Account();
        account.setId(123L);
        account.setAvatar(""); // 空字符串
        when(accountMapper.selectById(userId)).thenReturn(account);

        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);
        when(accountMapper.update(isNull(), any())).thenReturn(1);

        // 执行测试
        imageService.uploadAvatar(file, userId);

        // 验证没有尝试删除
        verify(minioClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void deleteOldImage_continuesOnDeleteError() throws Exception {
        // 通过uploadAvatar测试，删除旧头像失败时继续执行
        String userId = "123";
        String oldAvatar = "/avatar/old";
        MockMultipartFile file = new MockMultipartFile(
                "avatar",
                "avatar.jpg",
                "image/jpeg",
                "image".getBytes()
        );

        Account account = new Account();
        account.setId(123L);
        account.setAvatar(oldAvatar);
        when(accountMapper.selectById(userId)).thenReturn(account);

        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(null);

        // Mock删除失败
        doThrow(new RuntimeException("Delete failed"))
                .when(minioClient).removeObject(any(RemoveObjectArgs.class));

        when(accountMapper.update(isNull(), any())).thenReturn(1);

        // 执行测试
        String result = imageService.uploadAvatar(file, userId);

        // 验证即使删除失败，上传仍然成功
        assertNotNull(result);
        verify(accountMapper).update(isNull(), any());
    }
}