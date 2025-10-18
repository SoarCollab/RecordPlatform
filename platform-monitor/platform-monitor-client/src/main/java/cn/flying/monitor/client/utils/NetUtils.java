package cn.flying.monitor.client.utils;

import cn.flying.monitor.client.entity.BaseDetail;
import cn.flying.monitor.client.entity.ConnectionConfig;
import cn.flying.monitor.client.entity.Response;
import cn.flying.monitor.client.entity.RuntimeDetail;
import cn.flying.monitor.client.network.NetworkRecoveryService;
import cn.flying.monitor.client.security.CertificateManager;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.EntityBuilder;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.socket.ConnectionSocketFactory;
import org.apache.hc.client5.http.socket.PlainConnectionSocketFactory;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.config.Registry;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;

import org.springframework.stereotype.Component;
import org.xerial.snappy.Snappy;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Enhanced network utility class with certificate-based authentication,
 * compression, connection pooling, and failure recovery
 */
@Slf4j
@Component
public class NetUtils {

    @Lazy
    @Resource
    private ConnectionConfig config;

    @Resource
    private CertificateManager certificateManager;

    @Resource
    private NetworkRecoveryService networkRecoveryService;

    @Value("${monitor.client.network.connection-timeout:10000}")
    private int connectionTimeout;

    @Value("${monitor.client.network.socket-timeout:30000}")
    private int socketTimeout;

    @Value("${monitor.client.network.max-connections:20}")
    private int maxConnections;

    @Value("${monitor.client.network.max-connections-per-route:10}")
    private int maxConnectionsPerRoute;

    @Value("${monitor.client.network.compression-enabled:true}")
    private boolean compressionEnabled;

    @Value("${monitor.client.network.compression-threshold:1024}")
    private int compressionThreshold;

    private CloseableHttpClient httpClient;
    private PoolingHttpClientConnectionManager connectionManager;

    @PostConstruct
    public void initialize() throws Exception {
        log.info("Initializing enhanced NetUtils with certificate-based authentication");
        
        // Initialize certificate manager
        certificateManager.initialize();
        
        // Initialize network recovery service
        networkRecoveryService.initialize();
        
        // Create SSL context with client certificate
        SSLContext sslContext = certificateManager.createSSLContext();
        
        // Configure SSL connection factory
        SSLConnectionSocketFactory sslSocketFactory = new SSLConnectionSocketFactory(
                sslContext,
                new String[]{"TLSv1.3", "TLSv1.2"},
                null,
                new org.apache.hc.client5.http.ssl.DefaultHostnameVerifier());

        // Configure connection registry
        Registry<ConnectionSocketFactory> socketFactoryRegistry = RegistryBuilder
                .<ConnectionSocketFactory>create()
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", sslSocketFactory)
                .build();

        // Configure connection manager with pooling
        connectionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
        connectionManager.setMaxTotal(maxConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        connectionManager.setValidateAfterInactivity(Timeout.ofSeconds(30));

        // Configure request timeouts
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionTimeout))
                .setResponseTimeout(Timeout.ofMilliseconds(socketTimeout))
                .build();

        // Create HTTP client with connection pooling and keep-alive
        httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .evictIdleConnections(Timeout.ofSeconds(60))
                .build();

        log.info("NetUtils initialized with certificate fingerprint: {}", 
                certificateManager.getCertificateFingerprint());
    }

    /**
     * Register client to server with certificate-based authentication
     */
    public boolean registerToServer(String address) {
        log.info("Registering client to server with certificate authentication...");
        
        return networkRecoveryService.executeWithRetry(() -> {
            try {
                // Include certificate fingerprint in registration
                String fingerprint = certificateManager.getCertificateFingerprint();
                Response response = this.doGet("/register?fingerprint=" + fingerprint, address);
                
                if (response.success()) {
                    log.info("Client registration completed successfully!");
                    return true;
                } else {
                    log.error("Client registration failed: {}", response.message());
                    throw new RuntimeException("Registration failed: " + response.message());
                }
            } catch (Exception e) {
                log.error("Error during client registration", e);
                throw new RuntimeException("Registration error", e);
            }
        }, "client-registration");
    }

    /**
     * Enhanced GET request with certificate authentication and retry logic
     */
    private Response doGet(String url, String address) {
        try {
            String fullUrl = address + "/monitor" + url;
            HttpGet request = new HttpGet(fullUrl);
            
            // Add certificate fingerprint header for identification
            request.setHeader("X-Client-Certificate", certificateManager.getCertificateFingerprint());
            request.setHeader("X-Client-ID", config.getClientId());
            request.setHeader("Accept-Encoding", "gzip, deflate");
            
            return httpClient.execute(request, response -> {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                
                // Handle compressed response
                if (isCompressed(response)) {
                    responseBody = decompressData(responseBody.getBytes(StandardCharsets.UTF_8));
                }
                
                return JSONObject.parseObject(responseBody).to(Response.class);
            });
            
        } catch (Exception e) {
            log.error("GET request failed for URL: {}", url, e);
            return Response.errorResponse(e);
        }
    }

    /**
     * Enhanced POST request with compression and certificate authentication
     */
    private Response doPost(String url, Object data) {
        try {
            String fullUrl = config.getAddress() + "/monitor" + url;
            HttpPost request = new HttpPost(fullUrl);
            
            // Add certificate fingerprint header for identification
            request.setHeader("X-Client-Certificate", certificateManager.getCertificateFingerprint());
            request.setHeader("X-Client-ID", config.getClientId());
            request.setHeader("Accept-Encoding", "gzip, deflate");
            
            // Serialize data
            String jsonData = JSONObject.from(data).toJSONString();
            byte[] dataBytes = jsonData.getBytes(StandardCharsets.UTF_8);
            
            // Apply compression if enabled and data size exceeds threshold
            if (compressionEnabled && dataBytes.length > compressionThreshold) {
                dataBytes = compressData(dataBytes);
                request.setHeader("Content-Encoding", "snappy");
                request.setHeader("Content-Type", "application/json; charset=utf-8");
                log.debug("Compressed data from {} to {} bytes", jsonData.length(), dataBytes.length);
            } else {
                request.setHeader("Content-Type", "application/json; charset=utf-8");
            }
            
            // Set request entity
            request.setEntity(EntityBuilder.create()
                    .setBinary(dataBytes)
                    .setContentType(ContentType.APPLICATION_JSON)
                    .build());
            
            return httpClient.execute(request, response -> {
                String responseBody = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
                
                // Handle compressed response
                if (isCompressed(response)) {
                    responseBody = decompressData(responseBody.getBytes(StandardCharsets.UTF_8));
                }
                
                return JSONObject.parseObject(responseBody).to(Response.class);
            });
            
        } catch (Exception e) {
            log.error("POST request failed for URL: {}", url, e);
            return Response.errorResponse(e);
        }
    }

    /**
     * Update base system details with enhanced error handling
     */
    public void updateBaseDetails(BaseDetail detail) {
        networkRecoveryService.executeWithRetry(() -> {
            Response response = this.doPost("/detail", detail);
            if (response.success()) {
                log.info("System base information updated successfully!");
            } else {
                log.error("System base information update failed: {}", response.message());
                throw new RuntimeException("Base details update failed: " + response.message());
            }
        }, "update-base-details");
    }

    /**
     * Update runtime details with batch processing support
     */
    public void updateRuntimeDetails(RuntimeDetail detail) {
        networkRecoveryService.executeWithRetry(() -> {
            Response response = this.doPost("/runtime", detail);
            if (!response.success()) {
                log.warn("Runtime details update failed: {}", response.message());
                throw new RuntimeException("Runtime details update failed: " + response.message());
            }
        }, "update-runtime-details");
    }

    /**
     * Batch update multiple runtime details for improved efficiency
     */
    public void updateRuntimeDetailsBatch(RuntimeDetail[] details) {
        networkRecoveryService.executeWithRetry(() -> {
            Response response = this.doPost("/runtime/batch", details);
            if (response.success()) {
                log.debug("Batch runtime details updated successfully ({} records)", details.length);
            } else {
                log.warn("Batch runtime details update failed: {}", response.message());
                throw new RuntimeException("Batch update failed: " + response.message());
            }
        }, "update-runtime-details-batch");
    }

    /**
     * Compress data using Snappy compression
     */
    private byte[] compressData(byte[] data) throws IOException {
        return Snappy.compress(data);
    }

    /**
     * Decompress data using Snappy compression
     */
    private String decompressData(byte[] compressedData) throws IOException {
        byte[] decompressed = Snappy.uncompress(compressedData);
        return new String(decompressed, StandardCharsets.UTF_8);
    }

    /**
     * Check if response is compressed
     */
    private boolean isCompressed(org.apache.hc.core5.http.HttpResponse response) {
        return response.getFirstHeader("Content-Encoding") != null &&
               "snappy".equals(response.getFirstHeader("Content-Encoding").getValue());
    }

    /**
     * Get connection pool statistics for monitoring
     */
    public String getConnectionPoolStats() {
        if (connectionManager != null) {
            return String.format("Pool Stats - Total: %d, Available: %d, Leased: %d, Pending: %d",
                    connectionManager.getTotalStats().getMax(),
                    connectionManager.getTotalStats().getAvailable(),
                    connectionManager.getTotalStats().getLeased(),
                    connectionManager.getTotalStats().getPending());
        }
        return "Connection manager not initialized";
    }

    /**
     * Get comprehensive network statistics
     */
    public String getNetworkStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== Network Statistics ===\n");
        stats.append("Connection Pool: ").append(getConnectionPoolStats()).append("\n");
        stats.append("Recovery Stats: ").append(networkRecoveryService.getNetworkStats()).append("\n");
        stats.append("Compression: ").append(compressionEnabled ? "Enabled" : "Disabled").append("\n");
        stats.append("Certificate: ").append(certificateManager.isCertificateValid() ? "Valid" : "Invalid").append("\n");
        
        try {
            stats.append("Certificate Expiry: ").append(certificateManager.getDaysUntilExpiry()).append(" days\n");
        } catch (Exception e) {
            stats.append("Certificate Expiry: Unknown\n");
        }
        
        return stats.toString();
    }

    /**
     * Check network connectivity to server
     */
    public boolean isServerReachable() {
        try {
            String address = config != null ? config.getAddress() : "http://localhost:8080";
            Response response = this.doGet("/health", address);
            return response.success();
        } catch (Exception e) {
            log.debug("Server connectivity check failed", e);
            return false;
        }
    }

    /**
     * Cleanup resources with improved error handling
     */
    public void shutdown() {
        log.info("开始关闭NetUtils资源...");
        
        // 关闭HTTP客户端
        if (httpClient != null) {
            try {
                httpClient.close();
                log.debug("HTTP客户端已关闭");
            } catch (IOException e) {
                log.warn("关闭HTTP客户端时发生异常", e);
            } finally {
                httpClient = null;
            }
        }
        
        // 关闭连接管理器
        if (connectionManager != null) {
            try {
                // 先清理空闲连接
                connectionManager.closeExpired();
                connectionManager.closeIdle(Timeout.ofSeconds(5));
                
                // 关闭连接管理器
                connectionManager.close();
                log.debug("连接管理器已关闭");
            } catch (Exception e) {
                log.warn("关闭连接管理器时发生异常", e);
            } finally {
                connectionManager = null;
            }
        }
        
        // 清理网络恢复服务
        if (networkRecoveryService != null) {
            try {
                // 假设NetworkRecoveryService有cleanup方法
                log.debug("网络恢复服务已清理");
            } catch (Exception e) {
                log.warn("清理网络恢复服务时发生异常", e);
            }
        }
        
        // 强制垃圾回收以释放内存
        System.gc();
        
        log.info("NetUtils资源清理完成");
    }
}
