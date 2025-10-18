package cn.flying.monitor.client.integration;

import cn.flying.monitor.client.MonitorClientApplication;
import cn.flying.monitor.client.entity.BaseDetail;
import cn.flying.monitor.client.entity.RuntimeDetail;
import cn.flying.monitor.client.security.CertificateManager;
import cn.flying.monitor.client.utils.MonitorUtils;
import cn.flying.monitor.client.utils.NetUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the complete monitor client functionality
 */
@SpringBootTest(classes = MonitorClientApplication.class)
@TestPropertySource(properties = {
        "monitor.client.id=integration-test-client",
        "monitor.client.server.address=http://localhost:8088",
        "monitor.client.certificate.keystore-path=./test-integration-keystore.p12",
        "monitor.client.metrics.collection-interval=1",
        "monitor.client.metrics.enable-network-metrics=true",
        "monitor.client.metrics.enable-process-metrics=true",
        "monitor.client.metrics.enable-jvm-metrics=true",
        "monitor.client.network.compression-enabled=true",
        "monitor.client.network.max-connections=5",
        "logging.level.cn.flying.monitor.client=DEBUG"
})
class MonitorClientIntegrationTest {

    @Autowired
    private MonitorUtils monitorUtils;

    @Autowired
    private NetUtils netUtils;

    @Autowired
    private CertificateManager certificateManager;

    private WireMockServer wireMockServer;

    @BeforeEach
    void setUp() {
        // Start WireMock server
        wireMockServer = new WireMockServer(8088);
        wireMockServer.start();
        WireMock.configureFor("localhost", 8088);

        // Setup default stubs
        setupDefaultStubs();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    private void setupDefaultStubs() {
        // Registration endpoint
        stubFor(get(urlPathEqualTo("/monitor/register"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Registration successful\"}")));

        // Health check endpoint
        stubFor(get(urlPathEqualTo("/monitor/health"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Server healthy\"}")));

        // Base details endpoint
        stubFor(post(urlPathEqualTo("/monitor/detail"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Details updated\"}")));

        // Runtime details endpoint
        stubFor(post(urlPathEqualTo("/monitor/runtime"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Runtime updated\"}")));

        // Batch runtime details endpoint
        stubFor(post(urlPathEqualTo("/monitor/runtime/batch"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Batch updated\"}")));
    }

    @Test
    void testCertificateManagerInitialization() throws Exception {
        // When
        certificateManager.initialize();

        // Then
        assertNotNull(certificateManager.getClientCertificate());
        assertNotNull(certificateManager.getPrivateKey());
        assertTrue(certificateManager.isCertificateValid());
        assertNotNull(certificateManager.getCertificateFingerprint());
    }

    @Test
    void testNetworkConnectivity() {
        // When
        boolean reachable = netUtils.isServerReachable();

        // Then
        assertTrue(reachable);
    }

    @Test
    void testClientRegistration() {
        // When
        boolean registered = netUtils.registerToServer("http://localhost:8088");

        // Then
        assertTrue(registered);
        
        // Verify registration request was made with correct headers
        verify(getRequestedFor(urlPathEqualTo("/monitor/register"))
                .withHeader("X-Client-Certificate", matching(".*"))
                .withHeader("X-Client-ID", equalTo("integration-test-client")));
    }

    @Test
    void testMetricCollection() {
        // When
        RuntimeDetail runtimeDetail = monitorUtils.monitorRuntimeDetail();

        // Then
        assertNotNull(runtimeDetail);
        assertTrue(runtimeDetail.getTimestamp() > 0);
        assertTrue(runtimeDetail.getCpuUsage() >= 0);
        assertTrue(runtimeDetail.getMemoryUsage() >= 0);
        
        // Enhanced metrics should be present
        assertNotNull(runtimeDetail.getNetworkInterfaces());
        assertNotNull(runtimeDetail.getTopProcesses());
        assertNotNull(runtimeDetail.getJvmMetrics());
        assertNotNull(runtimeDetail.getDiskMountPoints());
        
        // JVM metrics should have reasonable values
        assertTrue(runtimeDetail.getJvmMetrics().getHeapUsedMB() > 0);
        assertTrue(runtimeDetail.getJvmMetrics().getThreadCount() > 0);
    }

    @Test
    void testBaseDetailsUpdate() {
        // Given
        BaseDetail baseDetail = monitorUtils.monitorBaseDetail();
        assertNotNull(baseDetail);

        // When
        assertDoesNotThrow(() -> netUtils.updateBaseDetails(baseDetail));

        // Then
        verify(postRequestedFor(urlPathEqualTo("/monitor/detail"))
                .withHeader("Content-Type", equalTo("application/json; charset=utf-8"))
                .withHeader("X-Client-Certificate", matching(".*"))
                .withHeader("X-Client-ID", equalTo("integration-test-client")));
    }

    @Test
    void testRuntimeDetailsUpdate() {
        // Given
        RuntimeDetail runtimeDetail = monitorUtils.monitorRuntimeDetail();
        assertNotNull(runtimeDetail);

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetails(runtimeDetail));

        // Then
        verify(postRequestedFor(urlPathEqualTo("/monitor/runtime"))
                .withHeader("Content-Type", equalTo("application/json; charset=utf-8"))
                .withHeader("X-Client-Certificate", matching(".*"))
                .withHeader("X-Client-ID", equalTo("integration-test-client")));
    }

    @Test
    void testBatchRuntimeDetailsUpdate() {
        // Given
        RuntimeDetail[] details = {
                monitorUtils.monitorRuntimeDetail(),
                monitorUtils.monitorRuntimeDetail()
        };
        assertNotNull(details[0]);
        assertNotNull(details[1]);

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetailsBatch(details));

        // Then
        verify(postRequestedFor(urlPathEqualTo("/monitor/runtime/batch"))
                .withHeader("Content-Type", equalTo("application/json; charset=utf-8")));
    }

    @Test
    void testCompressionFunctionality() {
        // Given - create a large runtime detail to trigger compression
        RuntimeDetail largeDetail = monitorUtils.monitorRuntimeDetail();
        
        // Add large custom metrics
        java.util.Map<String, Object> customMetrics = new java.util.HashMap<>();
        for (int i = 0; i < 50; i++) {
            customMetrics.put("large_metric_" + i, 
                    "This is a large metric value that should trigger compression when sent to the server " + i);
        }
        largeDetail.setCustomMetrics(customMetrics);

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetails(largeDetail));

        // Then - verify compression header is sent
        verify(postRequestedFor(urlPathEqualTo("/monitor/runtime"))
                .withHeader("Content-Encoding", equalTo("snappy")));
    }

    @Test
    void testNetworkFailureRecovery() {
        // Given - setup server to fail initially then succeed
        stubFor(post(urlPathEqualTo("/monitor/runtime"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Failed Once"));

        stubFor(post(urlPathEqualTo("/monitor/runtime"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Failed Once")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\": true, \"message\": \"Runtime updated\"}")));

        RuntimeDetail runtimeDetail = monitorUtils.monitorRuntimeDetail();

        // When
        assertDoesNotThrow(() -> netUtils.updateRuntimeDetails(runtimeDetail));

        // Then - should have retried and succeeded
        verify(2, postRequestedFor(urlPathEqualTo("/monitor/runtime")));
    }

    @Test
    void testNetworkStatistics() {
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
    void testConnectionPoolStats() {
        // When
        String poolStats = netUtils.getConnectionPoolStats();

        // Then
        assertNotNull(poolStats);
        assertTrue(poolStats.contains("Pool Stats"));
    }

    @Test
    void testMetricFiltering() {
        // Given
        RuntimeDetail detail = monitorUtils.monitorRuntimeDetail();
        assertNotNull(detail);

        // When
        RuntimeDetail filtered = monitorUtils.filterMetrics(detail);

        // Then
        assertNotNull(filtered);
        // All metrics should be present since they're enabled in test properties
        assertNotNull(filtered.getNetworkInterfaces());
        assertNotNull(filtered.getTopProcesses());
        assertNotNull(filtered.getJvmMetrics());
    }

    @Test
    void testCustomMetricAddition() {
        // Given
        RuntimeDetail detail = new RuntimeDetail();

        // When
        monitorUtils.addCustomMetric(detail, "test_metric", 42.0);
        monitorUtils.addCustomMetric(detail, "test_string", "integration_test");

        // Then
        assertNotNull(detail.getCustomMetrics());
        assertEquals(42.0, detail.getCustomMetrics().get("test_metric"));
        assertEquals("integration_test", detail.getCustomMetrics().get("test_string"));
    }

    @Test
    void testCertificateRotation() throws Exception {
        // Given
        certificateManager.initialize();
        String originalFingerprint = certificateManager.getCertificateFingerprint();

        // When
        certificateManager.rotateCertificate();

        // Then
        String newFingerprint = certificateManager.getCertificateFingerprint();
        assertNotEquals(originalFingerprint, newFingerprint);
        assertTrue(certificateManager.isCertificateValid());
    }
}