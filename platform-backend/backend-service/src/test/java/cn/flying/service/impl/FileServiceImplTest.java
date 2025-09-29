package cn.flying.service.impl;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.util.Const;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FileServiceImplTest {

    @TempDir
    Path tempDir;
    @InjectMocks
    private FileServiceImpl fileService;
    @Mock
    private FileMapper fileMapper;
    @Mock
    private BlockChainService blockChainService;
    @Mock
    private DistributedStorageService storageService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);
        lenient().when(fileMapper.insert(any(cn.flying.dao.dto.File.class))).thenReturn(1);
        lenient().when(fileMapper.update(any(), any())).thenReturn(1);
    }

    // ============= 文件预存储测试 =============

    @Test
    void prepareStoreFile_savesFileWithPrepareStatus() {
        String uid = "user123";
        String fileName = "test.pdf";

        // Mock saveOrUpdate behavior
        when(fileMapper.selectOne(any())).thenReturn(null);
        when(fileMapper.insert(any(cn.flying.dao.dto.File.class))).thenReturn(1);

        fileService.prepareStoreFile(uid, fileName);

        ArgumentCaptor<cn.flying.dao.dto.File> fileCaptor = ArgumentCaptor.forClass(cn.flying.dao.dto.File.class);
        verify(fileMapper).insertOrUpdate(fileCaptor.capture());

        cn.flying.dao.dto.File savedFile = fileCaptor.getValue();
        assertThat(savedFile.getUid()).isEqualTo(uid);
        assertThat(savedFile.getFileName()).isEqualTo(fileName);
        assertThat(savedFile.getStatus()).isEqualTo(FileUploadStatus.PREPARE.getCode());
    }

    // ============= 文件存储测试 =============

    @Test
    void storeFile_returnsNullWhenFileListEmpty() {
        cn.flying.dao.dto.File result = fileService.storeFile(
                "user123", "test.pdf", Collections.emptyList(), Collections.emptyList(), "{}"
        );

        assertNull(result);
        verify(storageService, never()).storeFile(any(), any());
        verify(blockChainService, never()).storeFile(any(), any(), any(), any());
    }

    @Test
    void storeFile_smallFileUsesNormalTransfer() throws IOException {
        // 准备测试文件
        java.io.File testFile = createTestFile("small.txt", 1024); // 1KB文件
        List<java.io.File> fileList = List.of(testFile);
        List<String> hashList = List.of("hash123");

        // Mock存储服务响应
        Map<String, String> storageResult = new HashMap<>();
        storageResult.put("hash123", "/path/to/file");
        when(storageService.storeFile(any(), eq(hashList)))
                .thenReturn(Result.success(storageResult));

        // Mock区块链服务响应
        List<String> blockchainResult = Arrays.asList("txHash", "fileHash");
        when(blockChainService.storeFile(any(), any(), any(), any()))
                .thenReturn(Result.success(blockchainResult));

        // 执行测试
        cn.flying.dao.dto.File result = fileService.storeFile(
                "user123", "small.txt", fileList, hashList, "{\"type\":\"document\"}"
        );

        // 验证结果
        assertNotNull(result);
        assertThat(result.getFileHash()).isEqualTo("fileHash");
        assertThat(result.getTransactionHash()).isEqualTo("txHash");
        assertThat(result.getStatus()).isEqualTo(FileUploadStatus.SUCCESS.getCode());

        verify(storageService).storeFile(any(), eq(hashList));
        verify(blockChainService).storeFile(eq("user123"), eq("small.txt"), any(), any());
    }

    private java.io.File createTestFile(String name, int sizeInBytes) throws IOException {
        Path filePath = tempDir.resolve(name);
        byte[] data = new byte[sizeInBytes];
        new Random().nextBytes(data);
        Files.write(filePath, data);
        return filePath.toFile();
    }

    @Test
    void storeFile_largeFileUsesStreamingTransfer() throws IOException {
        // 创建大文件（>10MB）
        java.io.File largeFile = createTestFile("large.bin", 11 * 1024 * 1024);
        List<java.io.File> fileList = List.of(largeFile);
        List<String> hashList = List.of("largeHash");

        // Mock分块上传相关方法
        when(storageService.initMultipartUpload(any(), any(), anyLong(), any()))
                .thenReturn(Result.success("uploadId123"));
        when(storageService.uploadPart(any(), anyInt(), any(), any()))
                .thenReturn(Result.success("etag"));
        when(storageService.completeMultipartUpload(any(), any()))
                .thenReturn(Result.success("/path/to/large/file"));

        // Mock区块链服务
        when(blockChainService.storeFile(any(), any(), any(), any()))
                .thenReturn(Result.success(Arrays.asList("txHash", "fileHash")));

        // 执行测试
        cn.flying.dao.dto.File result = fileService.storeFile(
                "user123", "large.bin", fileList, hashList, "{}"
        );

        // 验证使用了流式传输
        verify(storageService).initMultipartUpload(any(), eq("largeHash"), eq(11L * 1024 * 1024), any());
        verify(storageService, atLeastOnce()).uploadPart(any(), anyInt(), any(), any());
        verify(storageService).completeMultipartUpload(any(), any());
    }

    @Test
    void storeFile_handlesStorageServiceFailure() throws IOException {
        java.io.File testFile = createTestFile("test.txt", 1024);
        List<java.io.File> fileList = List.of(testFile);
        List<String> hashList = List.of("hash123");

        // Mock存储服务失败
        when(storageService.storeFile(any(), any()))
                .thenReturn(new Result(500, "Storage service error", null));

        // 执行测试
        cn.flying.dao.dto.File result = fileService.storeFile(
                "user123", "test.txt", fileList, hashList, "{}"
        );

        // 验证返回null并更新文件状态
        assertNull(result);
        verify(fileMapper).update(any(cn.flying.dao.dto.File.class), any());
    }

    // ============= 文件状态管理测试 =============

    @Test
    void storeFile_handlesBlockchainServiceFailure() throws IOException {
        java.io.File testFile = createTestFile("test.txt", 1024);
        List<java.io.File> fileList = List.of(testFile);
        List<String> hashList = List.of("hash123");

        // Mock存储服务成功
        Map<String, String> storageResult = new HashMap<>();
        storageResult.put("hash123", "/path/to/file");
        when(storageService.storeFile(any(), eq(hashList)))
                .thenReturn(Result.success(storageResult));

        // Mock区块链服务失败
        when(blockChainService.storeFile(any(), any(), any(), any()))
                .thenReturn(new Result(500, "Blockchain error", null));

        // 执行测试
        cn.flying.dao.dto.File result = fileService.storeFile(
                "user123", "test.txt", fileList, hashList, "{}"
        );

        // 验证返回null并更新文件状态为失败
        assertNull(result);
        verify(fileMapper).update(any(cn.flying.dao.dto.File.class), any());
    }

    @Test
    void changeFileStatusByName_updatesStatus() {
        String uid = "user123";
        String fileName = "document.pdf";
        Integer newStatus = FileUploadStatus.SUCCESS.getCode();

        fileService.changeFileStatusByName(uid, fileName, newStatus);

        ArgumentCaptor<cn.flying.dao.dto.File> fileCaptor = ArgumentCaptor.forClass(cn.flying.dao.dto.File.class);
        ArgumentCaptor<LambdaUpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);

        verify(fileMapper).update(fileCaptor.capture(), wrapperCaptor.capture());

        assertThat(fileCaptor.getValue().getStatus()).isEqualTo(newStatus);
    }

    // ============= 文件删除测试 =============

    @Test
    void changeFileStatusByHash_updatesStatus() {
        String uid = "user123";
        String fileHash = "abc123hash";
        Integer newStatus = FileUploadStatus.FAIL.getCode();

        fileService.changeFileStatusByHash(uid, fileHash, newStatus);

        ArgumentCaptor<cn.flying.dao.dto.File> fileCaptor = ArgumentCaptor.forClass(cn.flying.dao.dto.File.class);
        verify(fileMapper).update(fileCaptor.capture(), any());

        assertThat(fileCaptor.getValue().getStatus()).isEqualTo(newStatus);
    }

    @Test
    void deleteFile_doesNothingWhenListEmpty() {
        fileService.deleteFile("user123", Collections.emptyList());
        verify(fileMapper, never()).delete(any());
    }

    // ============= 文件查询测试 =============

    @Test
    void deleteFile_removesMultipleFiles() {
        List<String> hashList = Arrays.asList("hash1", "hash2", "hash3");

        fileService.deleteFile("user123", hashList);

        verify(fileMapper).delete(any(LambdaUpdateWrapper.class));
    }

    @Test
    void getUserFilesList_normalUserSeesOwnFiles() {
        String uid = "user123";
        List<cn.flying.dao.dto.File> mockFiles = Arrays.asList(
                new cn.flying.dao.dto.File().setFileName("file1.txt"),
                new cn.flying.dao.dto.File().setFileName("file2.pdf")
        );

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAdmin).thenReturn(false);
            when(fileMapper.selectList(any())).thenReturn(mockFiles);

            List<cn.flying.dao.dto.File> result = fileService.getUserFilesList(uid);

            assertThat(result).hasSize(2);
            assertThat(result).isEqualTo(mockFiles);
            verify(fileMapper).selectList(any(LambdaQueryWrapper.class));
        }
    }

    @Test
    void getUserFilesList_adminSeesAllFiles() {
        String uid = "admin";
        List<cn.flying.dao.dto.File> mockFiles = Arrays.asList(
                new cn.flying.dao.dto.File().setFileName("file1.txt"),
                new cn.flying.dao.dto.File().setFileName("file2.pdf"),
                new cn.flying.dao.dto.File().setFileName("file3.doc")
        );

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAdmin).thenReturn(true);
            when(fileMapper.selectList(any())).thenReturn(mockFiles);

            List<cn.flying.dao.dto.File> result = fileService.getUserFilesList(uid);

            assertThat(result).hasSize(3);
            verify(fileMapper).selectList(any());
        }
    }

    @Test
    void getUserFilesPage_paginatesResults() {
        String uid = "user123";
        Page<cn.flying.dao.dto.File> page = new Page<>(1, 10);
        Page<cn.flying.dao.dto.File> resultPage = new Page<>(1, 10);
        resultPage.setRecords(Arrays.asList(
                new cn.flying.dao.dto.File().setFileName("file1.txt")
        ));

        try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
            securityUtils.when(SecurityUtils::isAdmin).thenReturn(false);
            when(fileMapper.selectPage(eq(page), any())).thenReturn(resultPage);

            fileService.getUserFilesPage(uid, page);

            verify(fileMapper).selectPage(eq(page), any(LambdaQueryWrapper.class));
        }
    }

    @Test
    void getFileAddress_retrievesFileUrls() {
        String uid = "user123";
        String fileHash = "fileHash123";
        FileDetailVO detailVO = new FileDetailVO();
        detailVO.setContent("{\"hash1\":\"/path1\",\"hash2\":\"/path2\"}");

        when(blockChainService.getFile(uid, fileHash))
                .thenReturn(Result.success(detailVO));
        when(storageService.getFileUrlListByHash(any(), any()))
                .thenReturn(Result.success(Arrays.asList("url1", "url2")));

        List<String> urls = fileService.getFileAddress(uid, fileHash);

        assertThat(urls).containsExactly("url1", "url2");
        verify(blockChainService).getFile(uid, fileHash);
        verify(storageService).getFileUrlListByHash(any(), any());
    }

    @Test
    void getTransactionByHash_retrievesTransaction() {
        String txHash = "0x123abc";
        TransactionVO mockTx = new TransactionVO();
        mockTx.setTransactionHash(txHash);

        when(blockChainService.getTransactionByHash(txHash))
                .thenReturn(Result.success(mockTx));

        TransactionVO result = fileService.getTransactionByHash(txHash);

        assertThat(result).isNotNull();
        assertThat(result.getTransactionHash()).isEqualTo(txHash);
    }

    // ============= 文件分享测试 =============

    @Test
    void getFile_retrievesFileContent() {
        String uid = "user123";
        String fileHash = "fileHash123";
        FileDetailVO detailVO = new FileDetailVO();
        detailVO.setContent("{\"hash1\":\"/path1\"}");

        byte[] fileData = "file content".getBytes();

        when(blockChainService.getFile(uid, fileHash))
                .thenReturn(Result.success(detailVO));
        when(storageService.getFileListByHash(any(), any()))
                .thenReturn(Result.success(List.of(fileData)));

        List<byte[]> result = fileService.getFile(uid, fileHash);

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isEqualTo(fileData);
    }

    @Test
    void generateSharingCode_createsShareCode() {
        String uid = "user123";
        List<String> fileHashes = Arrays.asList("hash1", "hash2");
        Integer maxAccesses = 10;
        String expectedCode = "SHARE123CODE";

        when(blockChainService.shareFiles(uid, fileHashes, maxAccesses))
                .thenReturn(Result.success(expectedCode));

        String result = fileService.generateSharingCode(uid, fileHashes, maxAccesses);

        assertThat(result).isEqualTo(expectedCode);
        verify(blockChainService).shareFiles(uid, fileHashes, maxAccesses);
    }

    @Test
    void getShareFile_returnsSharedFiles() {
        String shareCode = "SHARE123";
        SharingVO sharingVO = new SharingVO();
        sharingVO.setUploader("user456");
        sharingVO.setFileHashList(Arrays.asList("hash1", "hash2"));

        List<cn.flying.dao.dto.File> sharedFiles = Arrays.asList(
                new cn.flying.dao.dto.File().setFileName("shared1.txt"),
                new cn.flying.dao.dto.File().setFileName("shared2.pdf")
        );

        when(blockChainService.getSharedFiles(shareCode))
                .thenReturn(Result.success(sharingVO));
        when(fileMapper.selectList(any()))
                .thenReturn(sharedFiles);

        List<cn.flying.dao.dto.File> result = fileService.getShareFile(shareCode);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getFileName()).isEqualTo("shared1.txt");
        verify(blockChainService).getSharedFiles(shareCode);
    }

    @Test
    void getShareFile_returnsEmptyListWhenShareCodeInvalid() {
        String invalidCode = "INVALID";

        when(blockChainService.getSharedFiles(invalidCode))
                .thenReturn(new Result(500, "Invalid share code", null));

        List<cn.flying.dao.dto.File> result = fileService.getShareFile(invalidCode);

        assertThat(result).isEmpty();
        verify(fileMapper, never()).selectList(any());
    }

    @Test
    @org.junit.jupiter.api.Disabled("需要完整的MyBatis Plus基础设施")
    void saveShareFile_copiesSharedFilesToCurrentUser() {
        List<String> fileIds = Arrays.asList("1", "2");
        String currentUid = "currentUser";

        List<cn.flying.dao.dto.File> sharedFiles = Arrays.asList(
                new cn.flying.dao.dto.File()
                        .setId(1L)
                        .setUid("otherUser")
                        .setFileName("shared1.txt")
                        .setFileHash("hash1"),
                new cn.flying.dao.dto.File()
                        .setId(2L)
                        .setUid("otherUser")
                        .setFileName("shared2.pdf")
                        .setFileHash("hash2")
        );

        // 设置baseMapper避免MybatisMapperProxy问题
        ReflectionTestUtils.setField(fileService, "baseMapper", fileMapper);

        try {
            MDC.put(Const.ATTR_USER_ID, currentUid);
            when(fileMapper.selectList(any())).thenReturn(sharedFiles);
            when(fileMapper.insertOrUpdate(any(cn.flying.dao.dto.File.class))).thenReturn(true);

            fileService.saveShareFile(fileIds);

            // 验证批量插入被调用
            verify(fileMapper, times(2)).insertOrUpdate(any(cn.flying.dao.dto.File.class));
        } finally {
            MDC.remove(Const.ATTR_USER_ID);
        }
    }

    // ============= 辅助方法 =============

    @Test
    void saveShareFile_doesNothingWhenListEmpty() {
        fileService.saveShareFile(Collections.emptyList());

        verify(fileMapper, never()).selectList(any());
        verify(fileMapper, never()).insert(any(cn.flying.dao.dto.File.class));
    }
}