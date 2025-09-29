package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.model.bo.*;
import org.fisco.bcos.sdk.v3.client.Client;
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
class StorageServiceTest {

    private static final String TEST_ADDRESS = "0xabcdef1234567890abcdef1234567890abcdef12";

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
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService();
        ReflectionTestUtils.setField(storageService, "address", TEST_ADDRESS);
        ReflectionTestUtils.setField(storageService, "client", client);
        ReflectionTestUtils.setField(storageService, "txProcessor", txProcessor);

        when(client.getCryptoSuite()).thenReturn(cryptoSuite);
        when(cryptoSuite.getCryptoKeyPair()).thenReturn(cryptoKeyPair);
        when(cryptoKeyPair.getAddress()).thenReturn(TEST_USER_ADDRESS);
    }

    @Test
    void testDeleteFile() throws Exception {
        // Prepare test data
        StorageDeleteFileInputBO input = new StorageDeleteFileInputBO("testUser", new byte[]{1, 2, 3});
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("deleteFile"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = storageService.deleteFile(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("deleteFile"), any(List.class));
    }

    @Test
    void testGetFile() throws Exception {
        // Prepare test data
        StorageGetFileInputBO input = new StorageGetFileInputBO("testUser", new byte[]{1, 2, 3});
        CallResponse mockResponse = mock(CallResponse.class);

        // Mock behavior
        when(txProcessor.sendCall(anyString(), anyString(), anyString(), eq("getFile"), any()))
                .thenReturn(mockResponse);

        // Execute
        CallResponse result = storageService.getFile(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendCall(eq(TEST_USER_ADDRESS), eq(TEST_ADDRESS), anyString(), eq("getFile"), any());
    }

    @Test
    void testDeleteFiles() throws Exception {
        // Prepare test data
        StorageDeleteFilesInputBO input = new StorageDeleteFilesInputBO(
                "testUser",
                java.util.List.of(new byte[]{1, 2, 3}, new byte[]{4, 5, 6})
        );
        TransactionResponse mockResponse = mock(TransactionResponse.class);

        // Mock behavior
        when(txProcessor.sendTransactionAndGetResponse(anyString(), anyString(), eq("deleteFiles"), any(List.class)))
                .thenReturn(mockResponse);

        // Execute
        TransactionResponse result = storageService.deleteFiles(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("deleteFiles"), any(List.class));
    }

    @Test
    void testGetUserFiles() throws Exception {
        // Prepare test data
        StorageGetUserFilesInputBO input = new StorageGetUserFilesInputBO("testUser");
        CallResponse mockResponse = mock(CallResponse.class);

        // Mock behavior
        when(txProcessor.sendCall(anyString(), anyString(), anyString(), eq("getUserFiles"), any()))
                .thenReturn(mockResponse);

        // Execute
        CallResponse result = storageService.getUserFiles(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendCall(eq(TEST_USER_ADDRESS), eq(TEST_ADDRESS), anyString(), eq("getUserFiles"), any());
    }

    @Test
    void testStoreFile() throws Exception {
        // Prepare test data
        StorageStoreFileInputBO input = new StorageStoreFileInputBO(
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
        TransactionResponse result = storageService.storeFile(input);

        // Verify
        assertNotNull(result);
        assertEquals(mockResponse, result);
        verify(txProcessor).sendTransactionAndGetResponse(eq(TEST_ADDRESS), anyString(), eq("storeFile"), any(List.class));
    }

    @Test
    void testInitMethod() {
        // Create a new instance to test init
        StorageService service = new StorageService();
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
