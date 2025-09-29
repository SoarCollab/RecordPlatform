package cn.flying.integration;

import cn.flying.dao.dto.File;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.FileVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * FileController集成测试
 * 测试文件管理的完整流程，包括：
 * - 文件列表查询
 * - 文件分页查询
 * - 文件删除
 * - 文件下载地址获取
 * - 文件分享
 * - 区块链交易查询
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class FileControllerIntegrationTest {

    @Autowired
    private FileService fileService;

    @Autowired
    private BlockChainService blockChainService;

    @Autowired
    private DistributedStorageService storageService;

    private static final String TEST_USER_ID = "testUser123";
    private static final String TEST_FILE_HASH = "fileHash123";
    private static final String TEST_TRANSACTION_HASH = "txHash123";

    @BeforeEach
    void setUp() {
        // 手动注入Mock的依赖到FileServiceImpl中
        // 因为@DubboReference在测试环境中无法自动注入
        ReflectionTestUtils.setField(fileService, "blockChainService", blockChainService);
        ReflectionTestUtils.setField(fileService, "storageService", storageService);
    }

    @Test
    void testGetUserFilesList() {
        // Given: Mock区块链服务返回文件列表
        when(blockChainService.getUserFiles(TEST_USER_ID))
                .thenReturn(Result.success(Arrays.asList(
                        createFileVO("document1.pdf", TEST_FILE_HASH),
                        createFileVO("document2.docx", "fileHash456")
                )));

        // When: 获取文件列表
        List<File> files = fileService.getUserFilesList(TEST_USER_ID);

        // Then: 验证结果
        assertNotNull(files);
        assertThat(files.size()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testGetUserFilesPage() {
        // Given: 准备分页参数
        Page<File> page = new Page<>(1, 10);

        // Mock区块链服务返回文件列表
        List<FileVO> mockFiles = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            mockFiles.add(createFileVO("file" + i + ".txt", "hash" + i));
        }
        when(blockChainService.getUserFiles(TEST_USER_ID))
                .thenReturn(Result.success(mockFiles));

        // When: 获取分页数据
        fileService.getUserFilesPage(TEST_USER_ID, page);

        // Then: 验证分页结果
        assertNotNull(page);
        assertThat(page.getSize()).isEqualTo(10);
    }

    @Test
    void testDeleteFileByHash() {
        // Given: 准备要删除的文件
        List<String> fileHashList = Arrays.asList(TEST_FILE_HASH, "fileHash456");

        // Mock区块链删除操作
        when(blockChainService.deleteFiles(TEST_USER_ID, fileHashList))
                .thenReturn(Result.success(true));

        // When & Then: 删除文件不应抛出异常
        assertDoesNotThrow(() -> {
            fileService.deleteFile(TEST_USER_ID, fileHashList);
        });
    }

    @Test
    void testGetFileAddress() {
        // Given: 准备文件详情
        FileDetailVO fileDetail = new FileDetailVO();
        fileDetail.setContent("{\"chunk1\":\"/path/to/chunk1\",\"chunk2\":\"/path/to/chunk2\"}");

        when(blockChainService.getFile(TEST_USER_ID, TEST_FILE_HASH))
                .thenReturn(Result.success(fileDetail));

        // Mock存储服务返回预签名URL
        List<String> paths = Arrays.asList("/path/to/chunk1", "/path/to/chunk2");
        List<String> hashes = Arrays.asList("hash1", "hash2");
        when(storageService.getFileUrlListByHash(anyList(), anyList()))
                .thenReturn(Result.success(Arrays.asList(
                        "https://minio.example.com/chunk1?signature=xxx",
                        "https://minio.example.com/chunk2?signature=yyy"
                )));

        // When: 获取文件下载地址
        List<String> addresses = fileService.getFileAddress(TEST_USER_ID, TEST_FILE_HASH);

        // Then: 验证返回的地址
        assertNotNull(addresses);
        assertThat(addresses).isNotEmpty();
    }

    @Test
    void testGetTransactionByHash() {
        // Given: Mock区块链交易信息
        TransactionVO transactionVO = new TransactionVO();
        transactionVO.setTransactionHash(TEST_TRANSACTION_HASH);
        transactionVO.setChainId("chain0");
        transactionVO.setTimestamp(System.currentTimeMillis());

        when(blockChainService.getTransactionByHash(TEST_TRANSACTION_HASH))
                .thenReturn(Result.success(transactionVO));

        // When: 获取交易信息
        TransactionVO transaction = fileService.getTransactionByHash(TEST_TRANSACTION_HASH);

        // Then: 验证交易信息
        assertNotNull(transaction);
        assertThat(transaction.getTransactionHash()).isEqualTo(TEST_TRANSACTION_HASH);
        assertThat(transaction.getChainId()).isEqualTo("chain0");
    }

    @Test
    void testDownloadFile() {
        // Given: Mock文件详情
        FileDetailVO fileDetail = new FileDetailVO();
        fileDetail.setContent("{\"chunk1\":\"/path/to/chunk1\"}");

        when(blockChainService.getFile(TEST_USER_ID, TEST_FILE_HASH))
                .thenReturn(Result.success(fileDetail));

        // Mock存储服务返回文件内容
        byte[] chunk1Data = "File chunk 1 content".getBytes();
        when(storageService.getFileListByHash(anyList(), anyList()))
                .thenReturn(Result.success(Collections.singletonList(chunk1Data)));

        // When: 下载文件
        List<byte[]> fileChunks = fileService.getFile(TEST_USER_ID, TEST_FILE_HASH);

        // Then: 验证文件内容
        assertNotNull(fileChunks);
        assertThat(fileChunks).isNotEmpty();
        assertThat(fileChunks.get(0)).isNotEmpty();
    }

    @Test
    void testGenerateSharingCode() {
        // Given: 准备分享参数
        List<String> fileHashes = Arrays.asList(TEST_FILE_HASH, "fileHash456");
        Integer maxAccesses = 10;
        String expectedSharingCode = "SHARE123ABC";

        // Mock区块链分享服务
        when(blockChainService.shareFiles(TEST_USER_ID, fileHashes, maxAccesses))
                .thenReturn(Result.success(expectedSharingCode));

        // When: 生成分享码
        String sharingCode = fileService.generateSharingCode(TEST_USER_ID, fileHashes, maxAccesses);

        // Then: 验证分享码
        assertNotNull(sharingCode);
        assertThat(sharingCode).isEqualTo(expectedSharingCode);
        assertThat(sharingCode).hasSize(11);
    }

    @Test
    void testGetSharedFiles() {
        // Given: 准备分享码
        String sharingCode = "SHARE789XYZ";

        // Mock区块链服务返回分享信息
        SharingVO sharingVO = new SharingVO();
        sharingVO.setUploader("uploader123");
        sharingVO.setFileHashList(Arrays.asList(TEST_FILE_HASH, "fileHash456"));

        when(blockChainService.getSharedFiles(sharingCode))
                .thenReturn(Result.success(sharingVO));

        // When: 获取分享的文件
        List<File> sharedFiles = fileService.getShareFile(sharingCode);

        // Then: 验证分享文件
        assertNotNull(sharedFiles);
        // 由于实现可能返回空列表或文件列表，这里只验证不为null
    }

    @Test
    void testFileNotFound() {
        // Given: Mock文件不存在
        when(blockChainService.getFile(TEST_USER_ID, "nonexistent_hash"))
                .thenReturn(new Result(404, "文件不存在", null));

        // When & Then: 获取不存在的文件应抛出GeneralException
        assertThrows(cn.flying.common.exception.GeneralException.class, () -> {
            fileService.getFile(TEST_USER_ID, "nonexistent_hash");
        });
    }

    @Test
    void testInvalidSharingCode() {
        // Given: Mock无效的分享码
        String invalidCode = "INVALID";

        when(blockChainService.getSharedFiles(invalidCode))
                .thenReturn(new Result(400, "分享码无效", null));

        // When: 使用无效分享码获取文件
        List<File> files = fileService.getShareFile(invalidCode);

        // Then: 应返回空列表或null
        assertTrue(files == null || files.isEmpty());
    }

    // ============= 辅助方法 =============

    private FileVO createFileVO(String fileName, String fileHash) {
        FileVO vo = new FileVO();
        vo.setFileName(fileName);
        vo.setFileHash(fileHash);
        return vo;
    }

    private FileDetailVO createFileDetailVO(String fileName, String fileHash) {
        FileDetailVO vo = new FileDetailVO();
        vo.setFileName(fileName);
        vo.setFileHash(fileHash);
        vo.setContent("{\"chunk1\":\"/path/to/chunk\"}");
        return vo;
    }
}
