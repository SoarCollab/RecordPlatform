package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.model.bo.*;
import cn.flying.fisco_bcos.utils.Convert;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.*;
import org.fisco.bcos.sdk.v3.client.protocol.model.JsonTransactionResponse;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BlockChainServiceImplTest {

    @Mock
    private SharingService sharingService;

    @InjectMocks
    private BlockChainServiceImpl blockChainService;

    private static final String TEST_UPLOADER = "testUser";
    private static final String TEST_FILE_NAME = "test.txt";
    private static final String TEST_FILE_HASH = "1234567890abcdef";
    private static final String TEST_SHARE_CODE = "SHARE123456";
    private static final String TEST_CONTENT = "test content";
    private static final String TEST_PARAM = "test param";
    private static final String TEST_TRANSACTION_HASH = "0xabc123def456";

    @BeforeEach
    void setUp() {
        // BlockChainServiceImpl already has @InjectMocks which handles injection
    }

    @Test
    void testShareFilesSuccess() throws Exception {
        // Prepare test data
        List<String> fileHashList = Arrays.asList(TEST_FILE_HASH, "fedcba0987654321");
        Integer maxAccesses = 10;
        
        // Mock response
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(0);
        when(mockResponse.getReturnObject()).thenReturn(List.of(TEST_SHARE_CODE));
        
        when(sharingService.shareFiles(any(SharingShareFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<String> result = blockChainService.shareFiles(TEST_UPLOADER, fileHashList, maxAccesses);
        
        // Verify
        assertTrue(result.isSuccess());
        assertEquals(TEST_SHARE_CODE, result.getData());
        verify(sharingService).shareFiles(any(SharingShareFilesInputBO.class));
    }

    @Test
    void testShareFilesError() throws Exception {
        // Prepare test data
        List<String> fileHashList = Arrays.asList(TEST_FILE_HASH);
        Integer maxAccesses = 10;
        
        // Mock response with error
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(-1);
        when(mockResponse.getReturnMessage()).thenReturn("Contract execution failed");
        
        when(sharingService.shareFiles(any(SharingShareFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<String> result = blockChainService.shareFiles(TEST_UPLOADER, fileHashList, maxAccesses);
        
        // Verify
        assertFalse(result.isSuccess());
        assertEquals("Contract execution failed", result.getMessage());
    }

    @Test
    void testShareFilesException() throws Exception {
        // Prepare test data
        List<String> fileHashList = Arrays.asList(TEST_FILE_HASH);
        Integer maxAccesses = 10;
        
        // Mock exception
        when(sharingService.shareFiles(any(SharingShareFilesInputBO.class)))
            .thenThrow(new RuntimeException("Network error"));
        
        // Execute
        Result<String> result = blockChainService.shareFiles(TEST_UPLOADER, fileHashList, maxAccesses);
        
        // Verify
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Network error"));
    }

    @Test
    void testGetSharedFilesSuccess() throws Exception {
        // Mock response
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(0);
        
        // Create mock file data
        byte[] fileHashBytes = Convert.hexTobyte(TEST_FILE_HASH);
        List<Object> fileInfo = Arrays.asList(
            TEST_FILE_NAME, TEST_UPLOADER, TEST_PARAM, TEST_CONTENT, fileHashBytes, 1234567890L
        );
        List<Object> returnList = Arrays.asList(TEST_UPLOADER, List.of(fileInfo));
        when(mockResponse.getReturnObject()).thenReturn(returnList);
        
        when(sharingService.getSharedFiles(any(SharingGetSharedFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<SharingVO> result = blockChainService.getSharedFiles(TEST_SHARE_CODE);
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(TEST_UPLOADER, result.getData().getUploader());
        assertEquals(1, result.getData().getFileHashList().size());
        verify(sharingService).getSharedFiles(any(SharingGetSharedFilesInputBO.class));
    }

    @Test
    void testGetSharedFilesError() throws Exception {
        // Mock response with error
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(-1);
        
        when(sharingService.getSharedFiles(any(SharingGetSharedFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<SharingVO> result = blockChainService.getSharedFiles(TEST_SHARE_CODE);
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testStoreFileSuccess() throws Exception {
        // Mock response
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        TransactionReceipt mockReceipt = mock(TransactionReceipt.class);
        when(mockReceipt.getTransactionHash()).thenReturn(TEST_TRANSACTION_HASH);
        when(mockResponse.getTransactionReceipt()).thenReturn(mockReceipt);
        when(mockResponse.getReturnCode()).thenReturn(0);
        
        byte[] fileHashBytes = Convert.hexTobyte(TEST_FILE_HASH);
        when(mockResponse.getReturnObject()).thenReturn(List.of(fileHashBytes));
        
        when(sharingService.storeFile(any(SharingStoreFileInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<List<String>> result = blockChainService.storeFile(TEST_UPLOADER, TEST_FILE_NAME, TEST_PARAM, TEST_CONTENT);
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(2, result.getData().size());
        verify(sharingService).storeFile(any(SharingStoreFileInputBO.class));
    }

    @Test
    void testStoreFileNullReceipt() throws Exception {
        // Mock response with null receipt
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getTransactionReceipt()).thenReturn(null);
        
        when(sharingService.storeFile(any(SharingStoreFileInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<List<String>> result = blockChainService.storeFile(TEST_UPLOADER, TEST_FILE_NAME, TEST_PARAM, TEST_CONTENT);
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetUserFilesSuccess() throws Exception {
        // Mock response
        CallResponse mockResponse = mock(CallResponse.class);
        
        byte[] fileHashBytes = Convert.hexTobyte(TEST_FILE_HASH);
        List<Object> fileInfo = Arrays.asList(TEST_FILE_NAME, fileHashBytes);
        List<Object> filesList = List.of(fileInfo);
        when(mockResponse.getReturnObject()).thenReturn(List.of(filesList));
        
        when(sharingService.getUserFiles(any(SharingGetUserFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<List<FileVO>> result = blockChainService.getUserFiles(TEST_UPLOADER);
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(1, result.getData().size());
        assertEquals(TEST_FILE_NAME, result.getData().get(0).getFileName());
        verify(sharingService).getUserFiles(any(SharingGetUserFilesInputBO.class));
    }

    @Test
    void testGetUserFilesEmpty() throws Exception {
        // Mock response with empty list
        CallResponse mockResponse = mock(CallResponse.class);
        when(mockResponse.getReturnObject()).thenReturn(List.of(new ArrayList<>()));
        
        when(sharingService.getUserFiles(any(SharingGetUserFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<List<FileVO>> result = blockChainService.getUserFiles(TEST_UPLOADER);
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertTrue(result.getData().isEmpty());
    }

    @Test
    void testGetUserFilesException() throws Exception {
        // Mock exception
        when(sharingService.getUserFiles(any(SharingGetUserFilesInputBO.class)))
            .thenThrow(new RuntimeException("Database error"));
        
        // Execute
        Result<List<FileVO>> result = blockChainService.getUserFiles(TEST_UPLOADER);
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetFileSuccess() throws Exception {
        // Mock response
        CallResponse mockResponse = mock(CallResponse.class);
        
        byte[] fileHashBytes = Convert.hexTobyte(TEST_FILE_HASH);
        long uploadTime = System.currentTimeMillis();
        List<Object> fileInfo = Arrays.asList(
            TEST_UPLOADER, TEST_FILE_NAME, TEST_PARAM, TEST_CONTENT, fileHashBytes, uploadTime
        );
        when(mockResponse.getReturnObject()).thenReturn(List.of(fileInfo));
        
        when(sharingService.getFile(any(SharingGetFileInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<FileDetailVO> result = blockChainService.getFile(TEST_UPLOADER, TEST_FILE_HASH);
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(TEST_FILE_NAME, result.getData().getFileName());
        assertEquals(TEST_UPLOADER, result.getData().getUploader());
        assertEquals(TEST_CONTENT, result.getData().getContent());
        assertEquals(TEST_PARAM, result.getData().getParam());
        verify(sharingService).getFile(any(SharingGetFileInputBO.class));
    }

    @Test
    void testGetFileNotFound() throws Exception {
        // Mock response with null
        CallResponse mockResponse = mock(CallResponse.class);
        when(mockResponse.getReturnObject()).thenReturn(null);
        
        when(sharingService.getFile(any(SharingGetFileInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<FileDetailVO> result = blockChainService.getFile(TEST_UPLOADER, TEST_FILE_HASH);
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testDeleteFilesSuccess() throws Exception {
        // Prepare test data
        List<String> fileHashList = Arrays.asList(TEST_FILE_HASH, "fedcba0987654321");
        
        // Mock response
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(0);
        
        when(sharingService.deleteFiles(any(SharingDeleteFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<Boolean> result = blockChainService.deleteFiles(TEST_UPLOADER, fileHashList);
        
        // Verify
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
        verify(sharingService).deleteFiles(any(SharingDeleteFilesInputBO.class));
    }

    @Test
    void testDeleteFilesError() throws Exception {
        // Prepare test data
        List<String> fileHashList = Arrays.asList(TEST_FILE_HASH);
        
        // Mock response with error
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(-1);
        
        when(sharingService.deleteFiles(any(SharingDeleteFilesInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<Boolean> result = blockChainService.deleteFiles(TEST_UPLOADER, fileHashList);
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testDeleteFileSuccess() throws Exception {
        // Mock response
        TransactionResponse mockResponse = mock(TransactionResponse.class);
        when(mockResponse.getReturnCode()).thenReturn(0);
        
        when(sharingService.deleteFile(any(SharingDeleteFileInputBO.class))).thenReturn(mockResponse);
        
        // Execute
        Result<Boolean> result = blockChainService.deleteFile(TEST_UPLOADER, TEST_FILE_HASH);
        
        // Verify
        assertTrue(result.isSuccess());
        assertTrue(result.getData());
        verify(sharingService).deleteFile(any(SharingDeleteFileInputBO.class));
    }

    @Test
    void testDeleteFileException() throws Exception {
        // Mock exception
        when(sharingService.deleteFile(any(SharingDeleteFileInputBO.class)))
            .thenThrow(new RuntimeException("Permission denied"));
        
        // Execute
        Result<Boolean> result = blockChainService.deleteFile(TEST_UPLOADER, TEST_FILE_HASH);
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetCurrentBlockChainMessageSuccess() throws Exception {
        // Mock response
        TotalTransactionCount mockCount = mock(TotalTransactionCount.class);
        
        when(sharingService.getCurrentBlockChainMessage()).thenReturn(mockCount);
        
        // Execute
        Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        verify(sharingService).getCurrentBlockChainMessage();
    }

    @Test
    void testGetCurrentBlockChainMessageException() throws Exception {
        // Mock exception
        when(sharingService.getCurrentBlockChainMessage())
            .thenThrow(new RuntimeException("Connection timeout"));
        
        // Execute
        Result<BlockChainMessage> result = blockChainService.getCurrentBlockChainMessage();
        
        // Verify
        assertFalse(result.isSuccess());
    }

    @Test
    void testGetTransactionByHashSuccess() throws Exception {
        // Mock transaction
        BcosTransaction mockTransaction = mock(BcosTransaction.class);
        JsonTransactionResponse mockTxResult = mock(JsonTransactionResponse.class);
        when(mockTxResult.getHash()).thenReturn(TEST_TRANSACTION_HASH);
        when(mockTxResult.getFrom()).thenReturn("0xfrom123");
        when(mockTxResult.getTo()).thenReturn("0xto456");
        when(mockTxResult.getInput()).thenReturn("0xinput789");
        when(mockTxResult.getChainID()).thenReturn("chain1");
        when(mockTxResult.getGroupID()).thenReturn("group0");
        when(mockTxResult.getSignature()).thenReturn("sig123");
        when(mockTxResult.getImportTime()).thenReturn(123456789L);
        when(mockTransaction.getResult()).thenReturn(mockTxResult);
        
        // Mock receipt  
        BcosTransactionReceipt mockReceipt = mock(BcosTransactionReceipt.class);
        TransactionReceipt mockReceiptResult = mock(TransactionReceipt.class);
        when(mockReceipt.getResult()).thenReturn(mockReceiptResult);
        
        when(sharingService.getTransactionByHash(TEST_TRANSACTION_HASH)).thenReturn(mockTransaction);
        when(sharingService.getTransactionReceipt(TEST_TRANSACTION_HASH)).thenReturn(mockReceipt);
        
        // Execute
        Result<TransactionVO> result = blockChainService.getTransactionByHash(TEST_TRANSACTION_HASH);
        
        // Verify
        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        assertEquals(TEST_TRANSACTION_HASH, result.getData().getTransactionHash());
        verify(sharingService).getTransactionByHash(TEST_TRANSACTION_HASH);
        verify(sharingService).getTransactionReceipt(TEST_TRANSACTION_HASH);
    }

    @Test
    void testGetTransactionByHashNotFound() throws Exception {
        // Mock null transaction
        when(sharingService.getTransactionByHash(TEST_TRANSACTION_HASH)).thenReturn(null);
        
        // Execute
        Result<TransactionVO> result = blockChainService.getTransactionByHash(TEST_TRANSACTION_HASH);
        
        // Verify
        assertFalse(result.isSuccess());
        verify(sharingService).getTransactionByHash(TEST_TRANSACTION_HASH);
        verify(sharingService, never()).getTransactionReceipt(anyString());
    }

    @Test
    void testGetTransactionByHashReceiptNotFound() throws Exception {
        // Mock transaction
        BcosTransaction mockTransaction = mock(BcosTransaction.class);
        JsonTransactionResponse mockTxResult = mock(JsonTransactionResponse.class);
        when(mockTransaction.getResult()).thenReturn(mockTxResult);
        
        // Mock null receipt
        when(sharingService.getTransactionByHash(TEST_TRANSACTION_HASH)).thenReturn(mockTransaction);
        when(sharingService.getTransactionReceipt(TEST_TRANSACTION_HASH)).thenReturn(null);
        
        // Execute
        Result<TransactionVO> result = blockChainService.getTransactionByHash(TEST_TRANSACTION_HASH);
        
        // Verify
        assertFalse(result.isSuccess());
        verify(sharingService).getTransactionByHash(TEST_TRANSACTION_HASH);
        verify(sharingService).getTransactionReceipt(TEST_TRANSACTION_HASH);
    }
}
