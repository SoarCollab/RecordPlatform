package cn.flying.monitor.client.utils;

import cn.flying.monitor.client.entity.BaseDetail;
import cn.flying.monitor.client.entity.ConnectionConfig;
import cn.flying.monitor.client.entity.RuntimeDetail;
import cn.flying.monitor.client.network.NetworkRecoveryService;
import cn.flying.monitor.client.security.CertificateManager;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for network communication and compression functionality
 */
@ExtendWith(MockitoExtension.class)
class NetUtilsTest {

    private NetUtils netUtils;
    private WireMockServer wireMockServer;

    @Mock
    private CertificateManager certificateManager;

    @Mock
    private NetworkRecoveryService networkRecoveryService;

    @Mock
    private ConnectionConfig connectionConfig;

    @BeforeEach
    void setUp() throws Exception {
        // Start WireMock server
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();
        WireMock.configureFor("localhost", 8089);

        // Create NetUtils instance
        netUtils = new NetUtils();
        
        // Inject mocks
        ReflectionTestUtils.setField(netUtils, "certificateManager", certificateManager);
        ReflectionTestUtils.setField(netUtils, "networkRecoveryService", networkRecoveryService);
        ReflectionTestUtils.setField(netUtils, "config", connectionConfig);
        
        // Configure test values
        ReflectionTestUtils.setField(netUtils, "connectionTimeout", 5000);
        ReflectionTestUtils.setField(netUtils, "socketTimeout", 10000);
        ReflectionTestUtils.setField(netUtils, "maxConnections", 10);
        ReflectionTestUtils.setField(netUtils, "maxConnectionsPerRoute", 5);
        ReflectionTestUtils.setField(netUtils, "compressionEnabled", true);
        ReflectionTestUtils.setField(netUtils, "compressionThreshold", 100);

        // Mock certificate manager
        when(certificateManager.getCertificateFingerprint()).thenReturn("test-fingerprint");
        when(certificateManager.createSSLContext()).thenReturn(javax.net.ssl.SSLContext.getDefault());

        // Mock network recovery service
        doAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(0);
            return supplier.get();
        }).when(networkRecoveryService).executeWithRetry(any(java.util.function.Supplier.class), any(String.class));

        // Mock connection config
        when(connectionConfig.getAddress()).thenReturn("http://localhost:8089");
        when(connectionConfig.getClientId()).thenReturn("test-client");

        // Initialize NetUtils
        netUtils.initialize();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        if (netUtils != null) {
            netUtils.shutdown();
        }
    }

    @Test
    void testSuccessfulRegistration() {
        // Given
        stubFor(get(urlPathEqualTo("/monitor/register"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Registration successful\"}")));

        // When
        boolean result = netUtils.registerToServer("http://localhost:8089");

        // Then
        assertTrue(result);
        verify(getRequestedFor(urlPathEqualTo("/monitor/register"))
                .withQueryParam("fingerprint", equalTo("test-fingerprint"))
                .withHeader("X-Client-Certificate", equalTo("test-fingerprint"))
                .withHeader("X-Client-ID", equalTo("test-client")));
    }

    @Test
    void testFailedRegistration() {
        // Given
        stubFor(get(urlPathEqualTo("/monitor/register"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": false, \"message\": \"Invalid certificate\"}")));

        // When & Then
        assertThrows(RuntimeException.class, () -> {
            netUtils.registerToServer("http://localhost:8089");
        });
    }

    @Test
    void testUpdateBaseDetails() {
        // Given
        BaseDetail baseDetail = new BaseDetail()
                .setOsName("Linux")
                .setCpuCore(4)
                .setMemory(8.0)
                .setIp("192.168.1.100");

        stubFor(post(urlPathEqualTo("/monitor/detail"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Details updated\"}")));

        // When
        assertDoesNotThrow(() -> netUtils.updateBaseDetails(baseDetail));

        // Then
        verify(postRequestedFor(urlPathEqualTo("/monitor/detail"))
                .withHeader("Content-Type", equalTo("application/json; charset=utf-8"))
                .withHeader("X-Client-Certificate", equalTo("test-fingerprint"))
                .withHeader("X-Client-ID", equalTo("test-client")));
    }

    @Test
    void testUpdateRuntimeDetails() {
        // Given
        RuntimeDetail runtimeDetail = new RuntimeDetail()
                .setCpuUsage(45.5)
                .setMemoryUsage(6.2)
                .setDiskUsage(120.5)
                .setTimestamp(System.currentTimeMillis());

        stubFor(post(urlPathEqualTo("/monitor/runtime"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Runtime updated\"}")));

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetails(runtimeDetail));

        // Then
        verify(postRequestedFor(urlPathEqualTo("/monitor/runtime"))
                .withHeader("Content-Type", equalTo("application/json; charset=utf-8")));
    }

    @Test
    void testBatchUpdateRuntimeDetails() {
        // Given
        RuntimeDetail[] details = {
                new RuntimeDetail().setCpuUsage(45.5).setTimestamp(System.currentTimeMillis()),
                new RuntimeDetail().setCpuUsage(50.0).setTimestamp(System.currentTimeMillis())
        };

        stubFor(post(urlPathEqualTo("/monitor/runtime/batch"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Batch updated\"}")));

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetailsBatch(details));

        // Then
        verify(postRequestedFor(urlPathEqualTo("/monitor/runtime/batch")));
    }

    @Test
    void testServerReachability() {
        // Given
        stubFor(get(urlPathEqualTo("/monitor/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Server healthy\"}")));

        // When
        boolean reachable = netUtils.isServerReachable();

        // Then
        assertTrue(reachable);
    }

    @Test
    void testServerUnreachable() {
        // Given
        stubFor(get(urlPathEqualTo("/monitor/health"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error")));

        // When
        boolean reachable = netUtils.isServerReachable();

        // Then
        assertFalse(reachable);
    }

    @Test
    void testConnectionPoolStats() {
        // When
        String stats = netUtils.getConnectionPoolStats();

        // Then
        assertNotNull(stats);
        assertTrue(stats.contains("Pool Stats"));
    }

    @Test
    void testNetworkStats() {
        // Given
        NetworkRecoveryService.NetworkStats mockStats = NetworkRecoveryService.NetworkStats.builder()
                .totalRetries(5)
                .successfulOperations(10)
                .failedOperations(2)
                .failureRate(0.2)
                .isHealthy(true)
                .build();
        
        when(networkRecoveryService.getNetworkStats()).thenReturn(mockStats);

        // When
        String stats = netUtils.getNetworkStats();

        // Then
        assertNotNull(stats);
        assertTrue(stats.contains("Network Statistics"));
        assertTrue(stats.contains("Connection Pool"));
        assertTrue(stats.contains("Recovery Stats"));
        assertTrue(stats.contains("Compression"));
        assertTrue(stats.contains("Certificate"));
    }

    @Test
    void testCompressionHeaders() {
        // Given
        RuntimeDetail largeDetail = new RuntimeDetail()
                .setCpuUsage(45.5)
                .setMemoryUsage(6.2)
                .setTimestamp(System.currentTimeMillis());
        
        // Add large custom metrics to trigger compression
        java.util.Map<String, Object> customMetrics = new java.util.HashMap<>();
        for (int i = 0; i < 100; i++) {
            customMetrics.put("metric_" + i, "large_value_that_should_trigger_compression_" + i);
        }
        largeDetail.setCustomMetrics(customMetrics);

        stubFor(post(urlPathEqualTo("/monitor/runtime"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Runtime updated\"}")));

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetails(largeDetail));

        // Then - verify compression header is sent for large payloads
        verify(postRequestedFor(urlPathEqualTo("/monitor/runtime"))
                .withHeader("Content-Encoding", equalTo("snappy")));
    }
}