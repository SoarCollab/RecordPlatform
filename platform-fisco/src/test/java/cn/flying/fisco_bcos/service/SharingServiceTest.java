package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.model.bo.*;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.v3.transaction.manager.AssembleTransactionProcessor;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SharingServiceTest {

    private static final String TEST_ADDRESS = "0x1234567890abcdef1234567890abcdef12345678";

    private static final String TEST_USER_ADDRESS = "0xuser123456789";

    @Mock
    private Client client;

    @Mock
    private AssembleTransactionProcessor txProcessor;

    @Mock
    private CryptoSuite cryptoSuite;

    @Mock
    private CryptoKeyPair cryptoKeyPair;

    @InjectMocks
    private SharingService sharingService;

    @BeforeEach
    void setUp() {
        sharingService = new SharingService();
        ReflectionTestUtils.setField(sharingService, "address", TEST_ADDRESS);
        ReflectionTestUtils.setField(sharingService, "client", client);
        ReflectionTestUtils.setField(sharingService, "txProcessor", txProcessor);

        when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        when(cryptoSuite.getCryptoKeyPair()).thenReturn(cryptoKeyPair);
        when(cryptoKeyPair.getAddress()).thenReturn(TEST_USER_ADDRESS);
    }

    @Test
    void testDeleteFile() throws Exception {
        // Prepare test data
        SharingDeleteFileInputBO input = new SharingDeleteFileInputBO("testUser", new byte[]{1, 2, 3});
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("deleteFile"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = sharingService.deleteFile(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("deleteFile"), any(List.class));
    }

    @Test
    void testGetFile() throws Exception {
        // Prepare test data
        SharingGetFileInputBO input = new SharingGetFileInputBO("testUser", new byte[]{1, 2, 3});
        CallResponse mockResponse = mock(CallResponse.class);

        // Mock behavior
        when(txProcessor.sendCall(anyString(), anyString(), anyString(), eq("getFile"), any()))
                .thenReturn(mockResponse);

        // Execute
        CallResponse result = sharingService.getFile(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendCall(eq(TEST_USER_ADDRESS), eq(TEST_ADDRESS), anyString(), eq("getFile"), any());
    }

    @Test
    void testShareFiles() throws Exception {
        // Prepare test data
        SharingShareFilesInputBO input = new SharingShareFilesInputBO(
                "testUser",
                java.util.List.of(new byte[]{1, 2, 3}, new byte[]{4, 5, 6}),
                10
        );
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("shareFiles"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = sharingService.shareFiles(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("shareFiles"), any(List.class));
    }

    @Test
    void testGetSharedFiles() throws Exception {
        // Prepare test data
        SharingGetSharedFilesInputBO input = new SharingGetSharedFilesInputBO("SHARE123");
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("getSharedFiles"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = sharingService.getSharedFiles(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("getSharedFiles"), any(List.class));
    }

    @Test
    void testDeleteFiles() throws Exception {
        // Prepare test data
        SharingDeleteFilesInputBO input = new SharingDeleteFilesInputBO(
                "testUser",
                java.util.List.of(new byte[]{1, 2, 3}, new byte[]{4, 5, 6})
        );
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("deleteFiles"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = sharingService.deleteFiles(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("deleteFiles"), any(List.class));
    }

    @Test
    void testGetUserFiles() throws Exception {
        // Prepare test data
        SharingGetUserFilesInputBO input = new SharingGetUserFilesInputBO("testUser");
        CallResponse mockResponse = mock(CallResponse.class);

        // Mock behavior
        when(txProcessor.sendCall(anyString(), anyString(), anyString(), eq("getUserFiles"), any()))
                .thenReturn(mockResponse);

        // Execute
        CallResponse result = sharingService.getUserFiles(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendCall(eq(TEST_USER_ADDRESS), eq(TEST_ADDRESS), anyString(), eq("getUserFiles"), any());
    }

    @Test
    void testStoreFile() throws Exception {
        // Prepare test data
        SharingStoreFileInputBO input = new SharingStoreFileInputBO(
                "test.txt",
                "testUser",
                "test content",
                "test param"
        );
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("storeFile"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = sharingService.storeFile(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("storeFile"), any(List.class));
    }

    @Test
    void testGetCurrentBlockChainMessage() {
        // Mock response
        TotalTransactionCount mockCount = mock(TotalTransactionCount.class);
        when(client.getTotalTransactionCount()).thenReturn(mockCount);

        // Execute
        TotalTransactionCount result = sharingService.getCurrentBlockChainMessage();

        // Verify
        assertNotNull(result);
        assertEquals(mockCount, result);
        verify(client).getTotalTransactionCount();
    }

    @Test
    void testGetTransactionByHash() {
        // Prepare test data
        String transactionHash = "0xabc123def456";
        BcosTransaction mockTransaction = mock(BcosTransaction.class);

        // Mock behavior
        when(client.getTransaction(transactionHash, false)).thenReturn(mockTransaction);

        // Execute
        BcosTransaction result = sharingService.getTransactionByHash(transactionHash);

        // Verify
        assertNotNull(result);
        assertEquals(mockTransaction, result);
        verify(client).getTransaction(transactionHash, false);
    }

    @Test
    void testGetTransactionReceipt() {
        // Prepare test data
        String transactionHash = "0xabc123def456";
        BcosTransactionReceipt mockReceipt = mock(BcosTransactionReceipt.class);

        // Mock behavior
        when(client.getTransactionReceipt(transactionHash, false)).thenReturn(mockReceipt);

        // Execute
        BcosTransactionReceipt result = sharingService.getTransactionReceipt(transactionHash);

        // Verify
        assertNotNull(result);
        assertEquals(mockReceipt, result);
        verify(client).getTransactionReceipt(transactionHash, false);
    }

    @Test
    void testInitMethod() {
        // Create a new instance to test init
        SharingService service = new SharingService();
        ReflectionTestUtils.setField(service, "client", client);

        // Mock the factory method
        when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        when(cryptoSuite.getCryptoKeyPair()).thenReturn(cryptoKeyPair);

        // Call init
        service.init();

        // Verify that txProcessor was set
        assertNotNull(ReflectionTestUtils.getField(service, "txProcessor"));
    }
}
