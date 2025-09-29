package cn.flying.integration;

import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.FileService;
import cn.flying.service.FileUploadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 文件上传全流程集成测试
 * 测试从文件上传到存储、区块链记录的完整流程
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class FileUploadIntegrationTest {

    @TempDir
    Path tempDir;

    @Autowired
    private FileUploadService fileUploadService;

    @Autowired
    private FileService fileService;

    @Autowired
    private BlockChainService blockChainService;

    @Autowired
    private DistributedStorageService storageService;

    @BeforeEach
    void setUp() {
        // 手动注入Mock的依赖到FileServiceImpl中
        ReflectionTestUtils.setField(fileService, "blockChainService", blockChainService);
        ReflectionTestUtils.setField(fileService, "storageService", storageService);
    }

    @Test
    void testCompleteFileUploadFlow() throws Exception {
        // 测试完整的文件上传流程
        String userId = "testUser123";
        String fileName = "integration_test.pdf";
        String contentType = "application/pdf";
        String clientId = "client123";
        long fileSize = 1024 * 1024L; // 1MB

        // Step 1: 启动上传会话
        StartUploadVO startResult = fileUploadService.startUpload(
                userId, fileName, fileSize, contentType, clientId, 2, 1
        );

        assertNotNull(startResult);
        assertThat(startResult.getClientId()).isEqualTo(clientId);
        assertThat(startResult.getTotalChunks()).isEqualTo(1);

        // Step 2: 准备存储文件
        fileService.prepareStoreFile(userId, fileName);

        // Step 3: 模拟文件上传到存储服务
        java.io.File testFile = createTestFile("test.pdf", 1024 * 1024);
        List<java.io.File> fileList = List.of(testFile);
        List<String> hashList = List.of("hash123");

        // Mock存储服务响应
        Map<String, String> storageResult = new HashMap<>();
        storageResult.put("hash123", "/path/to/file");
        when(storageService.storeFile(any(), eq(hashList)))
                .thenReturn(Result.success(storageResult));

        // Mock区块链服务响应
        List<String> blockchainResult = Arrays.asList("txHash123", "fileHash123");
        when(blockChainService.storeFile(any(), any(), any(), any()))
                .thenReturn(Result.success(blockchainResult));

        // Step 4: 执行文件存储
        File storedFile = fileService.storeFile(
                userId, fileName, fileList, hashList, "{\"type\":\"document\"}"
        );

        // 验证结果
        assertNotNull(storedFile);
        assertThat(storedFile.getFileHash()).isEqualTo("fileHash123");
        assertThat(storedFile.getTransactionHash()).isEqualTo("txHash123");
        assertThat(storedFile.getFileName()).isEqualTo(fileName);
        assertThat(storedFile.getUid()).isEqualTo(userId);

        // Step 5: 验证文件可以被检索
        List<File> userFiles = fileService.getUserFilesList(userId);
        assertThat(userFiles).isNotEmpty();
        assertThat(userFiles).anyMatch(f -> f.getFileName().equals(fileName));

        // Step 6: 验证文件地址可以获取
        FileDetailVO detailVO = new FileDetailVO();
        detailVO.setContent("{\"hash123\":\"/path/to/file\"}");
        when(blockChainService.getFile(userId, "fileHash123"))
                .thenReturn(Result.success(detailVO));
        when(storageService.getFileUrlListByHash(any(), any()))
                .thenReturn(Result.success(List.of("https://storage.example.com/file")));

        List<String> fileUrls = fileService.getFileAddress(userId, "fileHash123");
        assertThat(fileUrls).isNotEmpty();
        assertThat(fileUrls.get(0)).contains("storage.example.com");
    }

    private java.io.File createTestFile(String name, int sizeInBytes) throws IOException {
        Path filePath = tempDir.resolve(name);
        byte[] data = new byte[sizeInBytes];
        new Random().nextBytes(data);
        Files.write(filePath, data);
        return filePath.toFile();
    }

    @Test
    void testFileUploadWithResume() throws Exception {
        // 测试断点续传场景
        String userId = "testUser456";
        String fileName = "large_file.zip";
        String contentType = "application/zip";
        String clientId = "client456";
        long fileSize = 50 * 1024 * 1024L; // 50MB
        int chunkSize = 5 * 1024 * 1024; // 5MB每片
        int totalChunks = 10;

        // Step 1: 初始上传会话
        StartUploadVO firstAttempt = fileUploadService.startUpload(
                userId, fileName, fileSize, contentType, clientId, chunkSize, totalChunks
        );

        assertNotNull(firstAttempt);
        assertFalse(firstAttempt.isResumed());
        assertThat(firstAttempt.getTotalChunks()).isEqualTo(totalChunks);

        // 模拟上传部分分片后中断...

        // Step 2: 恢复上传会话
        StartUploadVO resumeAttempt = fileUploadService.startUpload(
                userId, fileName, fileSize, contentType, clientId, chunkSize, totalChunks
        );

        // 根据实际实现，这里可能需要先保存一些分片状态
        // 这里简化处理，主要展示集成测试的结构
        assertNotNull(resumeAttempt);
    }

    @Test
    void testFileSharing() throws Exception {
        // 测试文件分享功能
        String ownerId = "owner123";
        String recipientId = "recipient456";
        String fileName = "shared_document.docx";

        // Step 1: 文件所有者上传文件
        prepareAndStoreFile(ownerId, fileName);

        // Step 2: 生成分享码
        List<String> fileHashes = Arrays.asList("fileHash1", "fileHash2");
        String expectedShareCode = "SHARE789XYZ";

        when(blockChainService.shareFiles(ownerId, fileHashes, 10))
                .thenReturn(Result.success(expectedShareCode));

        String shareCode = fileService.generateSharingCode(ownerId, fileHashes, 10);
        assertThat(shareCode).isEqualTo(expectedShareCode);

        // Step 3: 接收者获取分享的文件
        // 这里需要根据实际实现调整
        // 主要展示集成测试如何测试多个服务的协作
    }

    private void prepareAndStoreFile(String userId, String fileName) {
        fileService.prepareStoreFile(userId, fileName);

        // Mock存储和区块链服务
        Map<String, String> storageResult = new HashMap<>();
        storageResult.put("hash", "/path");
        when(storageService.storeFile(any(), any()))
                .thenReturn(Result.success(storageResult));

        when(blockChainService.storeFile(any(), any(), any(), any()))
                .thenReturn(Result.success(Arrays.asList("tx", "hash")));

        try {
            java.io.File file = createTestFile(fileName, 1024);
            fileService.storeFile(userId, fileName, List.of(file), List.of("hash"), "{}");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testConcurrentFileUploads() throws Exception {
        // 测试并发文件上传
        String userId = "concurrentUser";
        List<String> fileNames = Arrays.asList(
                "file1.txt", "file2.pdf", "file3.doc"
        );

        // 模拟多个文件同时上传
        List<StartUploadVO> uploadSessions = new ArrayList<>();

        for (String fileName : fileNames) {
            StartUploadVO session = fileUploadService.startUpload(
                    userId, fileName, 1024L, "application/octet-stream",
                    "client_" + fileName, 1, 1
            );
            uploadSessions.add(session);
        }

        // 验证所有会话都成功创建
        assertThat(uploadSessions).hasSize(3);
        assertThat(uploadSessions).allMatch(Objects::nonNull);

        // 验证每个会话有唯一的clientId
        Set<String> clientIds = new HashSet<>();
        for (StartUploadVO session : uploadSessions) {
            clientIds.add(session.getClientId());
        }
        assertThat(clientIds).hasSize(3);
    }

    // ============= 辅助方法 =============

    @Test
    void testFileUploadWithFailure() throws Exception {
        // 测试上传失败的处理
        String userId = "failUser";
        String fileName = "fail_test.txt";

        // 准备文件
        java.io.File testFile = createTestFile("fail.txt", 1024);
        List<java.io.File> fileList = List.of(testFile);
        List<String> hashList = List.of("failHash");

        // Mock存储服务失败
        when(storageService.storeFile(any(), any()))
                .thenReturn(new Result(500, "Storage service unavailable", null));

        // 执行存储
        File result = fileService.storeFile(
                userId, fileName, fileList, hashList, "{}"
        );

        // 验证失败处理
        assertNull(result);

        // 验证文件状态被更新为失败
        // 这里需要根据实际实现调整验证逻辑
    }

    @Test
    @Transactional
    void testTransactionalRollback() throws Exception {
        // 测试事务回滚
        String userId = "txUser";
        String fileName = "transaction_test.pdf";

        // 准备存储
        fileService.prepareStoreFile(userId, fileName);

        // 模拟存储过程中的异常
        java.io.File testFile = createTestFile("tx.pdf", 1024);
        List<java.io.File> fileList = List.of(testFile);
        List<String> hashList = List.of("txHash");

        // Mock存储成功但区块链失败
        Map<String, String> storageResult = new HashMap<>();
        storageResult.put("txHash", "/path/to/file");
        when(storageService.storeFile(any(), eq(hashList)))
                .thenReturn(Result.success(storageResult));

        when(blockChainService.storeFile(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Blockchain error"));

        // 执行并期待异常
        assertThrows(Exception.class, () -> {
            fileService.storeFile(userId, fileName, fileList, hashList, "{}");
        });

        // 验证事务回滚后，文件记录不存在
        List<File> files = fileService.getUserFilesList(userId);
        assertThat(files).noneMatch(f -> f.getFileName().equals(fileName));
    }
}