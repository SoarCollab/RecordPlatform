package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.adapter.BlockChainAdapter;
import cn.flying.fisco_bcos.adapter.model.*;
import cn.flying.fisco_bcos.monitor.FiscoMetrics;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.*;
import cn.flying.platformapi.response.*;
import cn.flying.test.logging.LogbackSilencerExtension;
import cn.flying.test.logging.SilenceLoggers;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@ExtendWith(LogbackSilencerExtension.class)
@DisplayName("BlockChainServiceImpl Tests")
class BlockChainServiceImplTest {

    @Mock
    private BlockChainAdapter chainAdapter;
    
    @Mock
    private FiscoMetrics fiscoMetrics;
    
    @Mock
    private Timer.Sample timerSample;
    
    @InjectMocks
    private BlockChainServiceImpl blockChainService;
    
    @BeforeEach
    void setUp() {
        lenient().when(fiscoMetrics.startStoreTimer()).thenReturn(timerSample);
        lenient().when(fiscoMetrics.startShareTimer()).thenReturn(timerSample);
        lenient().when(fiscoMetrics.startQueryTimer()).thenReturn(timerSample);
        lenient().when(fiscoMetrics.startDeleteTimer()).thenReturn(timerSample);
    }

    @Nested
    @DisplayName("Store File Operations")
    class StoreFileTests {
        
        @Test
        @DisplayName("Should store file successfully")
        void storeFile_shouldStoreSuccessfully() {
            StoreFileRequest request = new StoreFileRequest(
                    "user123",
                    "test.pdf",
                    "{\"size\":1024}",
                    "file_content_hash"
            );
            
            ChainReceipt receipt = new ChainReceipt();
            receipt.setSuccess(true);
            receipt.setTransactionHash("0xabc123");
            receipt.setFileHash("sha256_file_hash");
            
            when(chainAdapter.storeFile(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(receipt);
            
            Result<StoreFileResponse> result = blockChainService.storeFile(request);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().transactionHash()).isEqualTo("0xabc123");
            assertThat(result.getData().fileHash()).isEqualTo("sha256_file_hash");
            
            verify(fiscoMetrics).recordSuccess();
            verify(fiscoMetrics).stopStoreTimer(timerSample);
        }

        @Test
        @DisplayName("Should handle store file exception")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void storeFile_shouldHandleException() {
            StoreFileRequest request = new StoreFileRequest(
                    "user123",
                    "test.pdf",
                    "{}",
                    "content"
            );

            when(chainAdapter.storeFile(anyString(), anyString(), anyString(), anyString()))
                    .thenThrow(new RuntimeException("Connection failed"));

            Result<StoreFileResponse> result = blockChainService.storeFile(request);

            assertThat(result.getCode()).isNotEqualTo(200);
            verify(fiscoMetrics).stopStoreTimer(timerSample);
        }
    }

    @Nested
    @DisplayName("Share Files Operations")
    class ShareFilesTests {
        
        @Test
        @DisplayName("Should share files successfully")
        void shareFiles_shouldShareSuccessfully() {
            ShareFilesRequest request = new ShareFilesRequest(
                    "user123",
                    List.of("hash1", "hash2"),
                    60
            );
            
            ChainReceipt receipt = new ChainReceipt();
            receipt.setSuccess(true);
            receipt.setShareCode("SHARE_CODE_123");
            
            when(chainAdapter.shareFiles(anyString(), anyList(), anyInt()))
                    .thenReturn(receipt);
            
            Result<String> result = blockChainService.shareFiles(request);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isEqualTo("SHARE_CODE_123");
            
            verify(fiscoMetrics).recordSuccess();
        }

        @Test
        @DisplayName("Should return error for null expire minutes")
        void shareFiles_shouldReturnErrorForNullExpireMinutes() {
            ShareFilesRequest request = new ShareFilesRequest(
                    "user123",
                    List.of("hash1"),
                    null
            );
            
            Result<String> result = blockChainService.shareFiles(request);
            
            assertThat(result.getCode()).isNotEqualTo(200);
            assertThat(result.getMessage()).contains("expireMinutes");
        }

        @Test
        @DisplayName("Should return error for expire minutes exceeding max")
        void shareFiles_shouldReturnErrorForExceedingExpireMinutes() {
            ShareFilesRequest request = new ShareFilesRequest(
                    "user123",
                    List.of("hash1"),
                    50000
            );
            
            Result<String> result = blockChainService.shareFiles(request);
            
            assertThat(result.getCode()).isNotEqualTo(200);
            assertThat(result.getMessage()).contains("1-43200");
        }

        @Test
        @DisplayName("Should return error for zero expire minutes")
        void shareFiles_shouldReturnErrorForZeroExpireMinutes() {
            ShareFilesRequest request = new ShareFilesRequest(
                    "user123",
                    List.of("hash1"),
                    0
            );
            
            Result<String> result = blockChainService.shareFiles(request);
            
            assertThat(result.getCode()).isNotEqualTo(200);
        }

        /**
         * 验证分享请求为空时会返回参数错误。
         */
        @Test
        @DisplayName("Should return error when request is null")
        void shareFiles_shouldReturnErrorWhenRequestIsNull() {
            Result<String> result = blockChainService.shareFiles(null);

            assertThat(result.getCode()).isNotEqualTo(200);
            assertThat(result.getMessage()).contains("expireMinutes");
            verify(chainAdapter, never()).shareFiles(anyString(), anyList(), anyInt());
            verify(fiscoMetrics).stopShareTimer(timerSample);
        }

        /**
         * 验证适配器抛出异常时分享接口会返回失败并停止计时。
         */
        @Test
        @DisplayName("Should handle share adapter exception")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void shareFiles_shouldHandleAdapterException() {
            ShareFilesRequest request = new ShareFilesRequest(
                    "user123",
                    List.of("hash1"),
                    60
            );

            when(chainAdapter.shareFiles(anyString(), anyList(), anyInt()))
                    .thenThrow(new RuntimeException("Adapter unavailable"));

            Result<String> result = blockChainService.shareFiles(request);

            assertThat(result.getCode()).isNotEqualTo(200);
            verify(fiscoMetrics).stopShareTimer(timerSample);
        }
    }

    @Nested
    @DisplayName("Get Shared Files Operations")
    class GetSharedFilesTests {
        
        @Test
        @DisplayName("Should get shared files successfully")
        void getSharedFiles_shouldReturnFiles() {
            String shareCode = "SHARE_CODE_123";
            
            ChainShareInfo shareInfo = new ChainShareInfo();
            shareInfo.setUploader("user123");
            shareInfo.setFileHashList(List.of("hash1", "hash2"));
            shareInfo.setExpireTimestamp(System.currentTimeMillis() + 3600000);
            shareInfo.setIsValid(true);
            
            when(chainAdapter.getSharedFiles(shareCode)).thenReturn(shareInfo);
            
            Result<SharingVO> result = blockChainService.getSharedFiles(shareCode);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().uploader()).isEqualTo("user123");
            assertThat(result.getData().fileHashList()).hasSize(2);
            assertThat(result.getData().isValid()).isTrue();
        }

        @Test
        @DisplayName("Should handle exception when getting shared files")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getSharedFiles_shouldHandleException() {
            when(chainAdapter.getSharedFiles(anyString()))
                    .thenThrow(new RuntimeException("Share not found"));

            Result<SharingVO> result = blockChainService.getSharedFiles("INVALID_CODE");

            assertThat(result.getCode()).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Get User Files Operations")
    class GetUserFilesTests {
        
        @Test
        @DisplayName("Should get user files successfully")
        void getUserFiles_shouldReturnFiles() {
            String uploader = "user123";
            
            List<ChainFileInfo> chainFiles = List.of(
                    createChainFileInfo("file1.pdf", "hash1"),
                    createChainFileInfo("file2.doc", "hash2")
            );
            
            when(chainAdapter.getUserFiles(uploader)).thenReturn(chainFiles);
            
            Result<List<FileVO>> result = blockChainService.getUserFiles(uploader);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).hasSize(2);
            assertThat(result.getData().get(0).fileName()).isEqualTo("file1.pdf");
        }

        @Test
        @DisplayName("Should return empty list for user with no files")
        void getUserFiles_shouldReturnEmptyList() {
            when(chainAdapter.getUserFiles(anyString())).thenReturn(Collections.emptyList());
            
            Result<List<FileVO>> result = blockChainService.getUserFiles("new_user");
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isEmpty();
        }

        /**
         * 验证查询用户文件时适配器异常会返回失败结果。
         */
        @Test
        @DisplayName("Should handle exception when getting user files")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getUserFiles_shouldHandleException() {
            when(chainAdapter.getUserFiles(anyString()))
                    .thenThrow(new RuntimeException("Query failed"));

            Result<List<FileVO>> result = blockChainService.getUserFiles("user123");

            assertThat(result.getCode()).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Get File Operations")
    class GetFileTests {
        
        @Test
        @DisplayName("Should get file details successfully")
        void getFile_shouldReturnFileDetails() {
            String uploader = "user123";
            String fileHash = "sha256_hash";
            
            ChainFileDetail detail = new ChainFileDetail();
            detail.setUploader(uploader);
            detail.setFileName("test.pdf");
            detail.setFileHash(fileHash);
            detail.setContent("encrypted_content");
            detail.setParam("{\"size\":1024}");
            detail.setUploadTimestamp(System.currentTimeMillis());
            
            when(chainAdapter.getFile(uploader, fileHash)).thenReturn(detail);
            
            Result<FileDetailVO> result = blockChainService.getFile(uploader, fileHash);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().fileName()).isEqualTo("test.pdf");
            assertThat(result.getData().fileHash()).isEqualTo(fileHash);
            
            verify(fiscoMetrics).stopQueryTimer(timerSample);
        }

        /**
         * 验证获取文件详情时适配器抛异常会返回失败并停止查询计时。
         */
        @Test
        @DisplayName("Should handle exception when getting file detail")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getFile_shouldHandleException() {
            when(chainAdapter.getFile(anyString(), anyString()))
                    .thenThrow(new RuntimeException("File query failed"));

            Result<FileDetailVO> result = blockChainService.getFile("user123", "sha256_hash");

            assertThat(result.getCode()).isNotEqualTo(200);
            verify(fiscoMetrics).stopQueryTimer(timerSample);
        }
    }

    @Nested
    @DisplayName("Delete Files Operations")
    class DeleteFilesTests {
        
        @Test
        @DisplayName("Should delete files successfully")
        void deleteFiles_shouldDeleteSuccessfully() {
            DeleteFilesRequest request = new DeleteFilesRequest(
                    "user123",
                    List.of("hash1", "hash2")
            );
            
            ChainReceipt receipt = new ChainReceipt();
            receipt.setSuccess(true);
            
            when(chainAdapter.deleteFiles(anyString(), anyList())).thenReturn(receipt);
            
            Result<Boolean> result = blockChainService.deleteFiles(request);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();
            
            verify(fiscoMetrics).recordSuccess();
        }

        @Test
        @DisplayName("Should handle delete failure")
        void deleteFiles_shouldHandleFailure() {
            DeleteFilesRequest request = new DeleteFilesRequest(
                    "user123",
                    List.of("hash1")
            );
            
            ChainReceipt receipt = new ChainReceipt();
            receipt.setSuccess(false);
            receipt.setErrorMessage("File not found");
            
            when(chainAdapter.deleteFiles(anyString(), anyList())).thenReturn(receipt);
            
            Result<Boolean> result = blockChainService.deleteFiles(request);
            
            assertThat(result.getCode()).isNotEqualTo(200);
            
            verify(fiscoMetrics).recordFailure();
        }

        /**
         * 验证删除文件时适配器异常分支。
         */
        @Test
        @DisplayName("Should handle delete exception")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void deleteFiles_shouldHandleException() {
            DeleteFilesRequest request = new DeleteFilesRequest(
                    "user123",
                    List.of("hash1")
            );

            when(chainAdapter.deleteFiles(anyString(), anyList()))
                    .thenThrow(new RuntimeException("Delete failed"));

            Result<Boolean> result = blockChainService.deleteFiles(request);

            assertThat(result.getCode()).isNotEqualTo(200);
            verify(fiscoMetrics).stopDeleteTimer(timerSample);
        }
    }

    @Nested
    @DisplayName("Cancel Share Operations")
    class CancelShareTests {
        
        @Test
        @DisplayName("Should cancel share successfully")
        void cancelShare_shouldCancelSuccessfully() {
            CancelShareRequest request = new CancelShareRequest(
                    "SHARE_CODE_123",
                    null
            );
            
            ChainReceipt receipt = new ChainReceipt();
            receipt.setSuccess(true);
            
            when(chainAdapter.cancelShare("SHARE_CODE_123")).thenReturn(receipt);
            
            Result<Boolean> result = blockChainService.cancelShare(request);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).isTrue();
            
            verify(fiscoMetrics).recordSuccess();
        }

        @Test
        @DisplayName("Should handle cancel share failure")
        void cancelShare_shouldHandleFailure() {
            CancelShareRequest request = new CancelShareRequest(
                    "INVALID_CODE",
                    null
            );
            
            ChainReceipt receipt = new ChainReceipt();
            receipt.setSuccess(false);
            receipt.setErrorMessage("Share not found");
            
            when(chainAdapter.cancelShare(anyString())).thenReturn(receipt);
            
            Result<Boolean> result = blockChainService.cancelShare(request);
            
            assertThat(result.getCode()).isNotEqualTo(200);
            
            verify(fiscoMetrics).recordFailure();
        }

        /**
         * 验证取消分享时适配器异常分支。
         */
        @Test
        @DisplayName("Should handle cancel share exception")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void cancelShare_shouldHandleException() {
            CancelShareRequest request = new CancelShareRequest(
                    "ERR_CODE",
                    null
            );

            when(chainAdapter.cancelShare(anyString()))
                    .thenThrow(new RuntimeException("Cancel failed"));

            Result<Boolean> result = blockChainService.cancelShare(request);

            assertThat(result.getCode()).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Get Transaction Operations")
    class GetTransactionTests {
        
        @Test
        @DisplayName("Should get transaction by hash successfully")
        void getTransactionByHash_shouldReturnTransaction() {
            String txHash = "0xabc123def456";
            
            ChainTransaction tx = new ChainTransaction();
            tx.setHash(txHash);
            tx.setChainId("1");
            tx.setGroupId("1");
            tx.setFrom("0xsender");
            tx.setTo("0xcontract");
            tx.setBlockNumber(12345L);
            
            when(chainAdapter.getTransaction(txHash)).thenReturn(tx);
            
            Result<TransactionVO> result = blockChainService.getTransactionByHash(txHash);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().transactionHash()).isEqualTo(txHash);
            assertThat(result.getData().blockNumber()).isEqualTo("12345");
        }

        @Test
        @DisplayName("Should handle transaction not found")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getTransactionByHash_shouldHandleNotFound() {
            when(chainAdapter.getTransaction(anyString()))
                    .thenThrow(new RuntimeException("Transaction not found"));

            Result<TransactionVO> result = blockChainService.getTransactionByHash("invalid_hash");

            assertThat(result.getCode()).isNotEqualTo(200);
        }

        /**
         * 验证区块号为空时交易映射结果也应返回 null。
         */
        @Test
        @DisplayName("Should map null block number to null string")
        void getTransactionByHash_shouldMapNullBlockNumber() {
            String txHash = "0xnullblock";

            ChainTransaction tx = new ChainTransaction();
            tx.setHash(txHash);
            tx.setChainId("1");
            tx.setGroupId("1");
            tx.setFrom("0xfrom");
            tx.setTo("0xto");
            tx.setBlockNumber(null);

            when(chainAdapter.getTransaction(txHash)).thenReturn(tx);

            Result<TransactionVO> result = blockChainService.getTransactionByHash(txHash);

            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().blockNumber()).isNull();
        }
    }

    @Nested
    @DisplayName("Get Chain Status Operations")
    class GetChainStatusTests {
        
        @Test
        @DisplayName("Should get blockchain status successfully")
        void getCurrentBlockChainMessage_shouldReturnStatus() {
            ChainStatus status = new ChainStatus();
            status.setBlockNumber(12345L);
            status.setTransactionCount(1000L);
            status.setFailedTransactionCount(5L);
            
            when(chainAdapter.getChainStatus()).thenReturn(status);
            
            Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().getBlockNumber()).isEqualTo(12345L);
            assertThat(result.getData().getTransactionCount()).isEqualTo(1000L);
        }

        /**
         * 验证获取链状态异常分支。
         */
        @Test
        @DisplayName("Should handle exception when getting blockchain status")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getCurrentBlockChainMessage_shouldHandleException() {
            when(chainAdapter.getChainStatus())
                    .thenThrow(new RuntimeException("Chain status failed"));

            Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();

            assertThat(result.getCode()).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Get User Share Codes Operations")
    class GetUserShareCodesTests {
        
        @Test
        @DisplayName("Should get user share codes successfully")
        void getUserShareCodes_shouldReturnCodes() {
            String uploader = "user123";
            List<String> shareCodes = List.of("CODE1", "CODE2", "CODE3");
            
            when(chainAdapter.getUserShareCodes(uploader)).thenReturn(shareCodes);
            
            Result<List<String>> result = blockChainService.getUserShareCodes(uploader);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData()).hasSize(3);
            assertThat(result.getData()).contains("CODE1", "CODE2");
        }

        /**
         * 验证查询用户分享码异常分支。
         */
        @Test
        @DisplayName("Should handle exception when getting user share codes")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getUserShareCodes_shouldHandleException() {
            when(chainAdapter.getUserShareCodes(anyString()))
                    .thenThrow(new RuntimeException("Share code query failed"));

            Result<List<String>> result = blockChainService.getUserShareCodes("user123");

            assertThat(result.getCode()).isNotEqualTo(200);
        }
    }

    @Nested
    @DisplayName("Get Share Info Operations")
    class GetShareInfoTests {
        
        @Test
        @DisplayName("Should get share info successfully")
        void getShareInfo_shouldReturnInfo() {
            String shareCode = "SHARE_CODE_123";
            
            ChainShareInfo shareInfo = new ChainShareInfo();
            shareInfo.setUploader("user123");
            shareInfo.setFileHashList(List.of("hash1"));
            shareInfo.setExpireTimestamp(System.currentTimeMillis() + 3600000);
            shareInfo.setIsValid(true);
            
            when(chainAdapter.getShareInfo(shareCode)).thenReturn(shareInfo);
            
            Result<SharingVO> result = blockChainService.getShareInfo(shareCode);
            
            assertThat(result.getCode()).isEqualTo(200);
            assertThat(result.getData().shareCode()).isEqualTo(shareCode);
            assertThat(result.getData().isValid()).isTrue();
        }

        /**
         * 验证获取分享详情异常分支。
         */
        @Test
        @DisplayName("Should handle exception when getting share info")
        @SilenceLoggers("cn.flying.fisco_bcos.exception.BlockChainExceptionHandler")
        void getShareInfo_shouldHandleException() {
            when(chainAdapter.getShareInfo(anyString()))
                    .thenThrow(new RuntimeException("Share info failed"));

            Result<SharingVO> result = blockChainService.getShareInfo("INVALID");

            assertThat(result.getCode()).isNotEqualTo(200);
        }
    }

    private ChainFileInfo createChainFileInfo(String fileName, String fileHash) {
        ChainFileInfo info = new ChainFileInfo();
        info.setFileName(fileName);
        info.setFileHash(fileHash);
        return info;
    }
}
