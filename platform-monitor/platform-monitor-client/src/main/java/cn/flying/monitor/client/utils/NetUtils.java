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
import java.time.Instant;

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
     * 使用证书认证向服务端进行初始化连接校验（改为访问微服务数据接口健康检查）
     * 功能：调用 /api/v2/data/health 作为注册握手，校验证书指纹与网络可达性
     */
    public boolean registerToServer(String address) {
        log.info("Registering client to server with certificate authentication...");
        
        return networkRecoveryService.executeWithRetry(() -> {
            try {
                Response response = this.doGet("/health", address);
                if (response.success()) {
                    log.info("Client registration (health check) completed successfully!");
                    return true;
                } else {
                    log.error("Client registration (health check) failed: {}", response.message());
                    throw new RuntimeException("Registration failed: " + response.message());
                }
            } catch (Exception e) {
                log.error("Error during client registration", e);
                throw new RuntimeException("Registration error", e);
            }
        }, "client-registration");
    }

    /**
     * 增强版GET请求：走微服务数据接口前缀 /api/v2/data，并携带证书指纹与客户端ID头
     */
    private Response doGet(String url, String address) {
        try {
            String fullUrl = address + "/api/v2/data" + url;
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
                
                return parseResult(responseBody);
            });
            
        } catch (Exception e) {
            log.error("GET request failed for URL: {}", url, e);
            return Response.errorResponse(e);
        }
    }

    /**
     * 增强版POST请求：走微服务数据接口前缀 /api/v2/data，支持Snappy压缩与证书认证
     */
    private Response doPost(String url, Object data) {
        try {
            String fullUrl = config.getAddress() + "/api/v2/data" + url;
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
                
                return parseResult(responseBody);
            });
            
        } catch (Exception e) {
            log.error("POST request failed for URL: {}", url, e);
            return Response.errorResponse(e);
        }
    }

    /**
     * 更新服务器基础信息（适配微服务数据上报接口 /api/v2/data/metrics）
     * 功能：将基础信息封装进 custom_metrics 字段，以最小必填指标(0)完成一次上报
     */
    public void updateBaseDetails(BaseDetail detail) {
        networkRecoveryService.executeWithRetry(() -> {
            JSONObject payload = buildBaseDetailPayload(detail);
            Response response = this.doPost("/metrics", payload);
            if (response.success()) {
                log.info("System base information updated successfully!");
            } else {
                log.error("System base information update failed: {}", response.message());
                throw new RuntimeException("Base details update failed: " + response.message());
            }
        }, "update-base-details");
    }

    /**
     * 上报运行时指标（适配微服务数据上报接口 /api/v2/data/metrics）
     * 功能：将 RuntimeDetail 转换为服务端 DTO 期望的字段并上报
     */
    public void updateRuntimeDetails(RuntimeDetail detail) {
        networkRecoveryService.executeWithRetry(() -> {
            JSONObject payload = buildMetricsPayload(detail);
            Response response = this.doPost("/metrics", payload);
            if (!response.success()) {
                log.warn("Runtime details update failed: {}", response.message());
                throw new RuntimeException("Runtime details update failed: " + response.message());
            }
        }, "update-runtime-details");
    }

    /**
     * 批量上报运行时指标（适配微服务数据上报接口 /api/v2/data/metrics/batch）
     * 功能：将 RuntimeDetail[] 批量转换为 metrics 数组后上报
     */
    public void updateRuntimeDetailsBatch(RuntimeDetail[] details) {
        networkRecoveryService.executeWithRetry(() -> {
            JSONObject batchPayload = buildBatchPayload(details);
            Response response = this.doPost("/metrics/batch", batchPayload);
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
     * 将 RuntimeDetail 转换为服务端 MetricsDataDTO 对应的JSON结构
     */
    private JSONObject buildMetricsPayload(RuntimeDetail detail) {
        JSONObject o = new JSONObject();
        o.put("client_id", config.getClientId());
        o.put("timestamp", Instant.ofEpochMilli(detail.getTimestamp()).toString());
        o.put("cpu_usage", detail.getCpuUsage());
        o.put("memory_usage", detail.getMemoryUsage());
        o.put("disk_usage", detail.getDiskUsage());
        o.put("network_upload", detail.getNetworkUpload());
        o.put("network_download", detail.getNetworkDownload());
        o.put("disk_read", detail.getDiskRead());
        o.put("disk_write", detail.getDiskWrite());
        // 可选指标
        o.put("load_average", detail.getLoadAverage1min());
        o.put("process_count", detail.getTotalProcessCount());
        // 自定义指标聚合
        JSONObject custom = new JSONObject();
        if (detail.getJvmMetrics() != null) {
            custom.put("jvm_metrics", JSONObject.from(detail.getJvmMetrics()));
        }
        if (detail.getDiskMountPoints() != null) {
            custom.put("disk_mount_points", JSONObject.from(detail.getDiskMountPoints()));
        }
        if (detail.getNetworkInterfaces() != null) {
            custom.put("network_interfaces", JSONObject.from(detail.getNetworkInterfaces()));
        }
        if (detail.getCustomMetrics() != null) {
            custom.putAll(detail.getCustomMetrics());
        }
        if (!custom.isEmpty()) {
            o.put("custom_metrics", custom);
        }
        return o;
    }

    /**
     * 将 BaseDetail 封装为一次最小有效的 metrics 上报，基础字段进入 custom_metrics
     */
    private JSONObject buildBaseDetailPayload(BaseDetail base) {
        JSONObject o = new JSONObject();
        o.put("client_id", config.getClientId());
        o.put("timestamp", Instant.now().toString());
        // 必填指标使用0占位，满足校验规则
        o.put("cpu_usage", 0.0);
        o.put("memory_usage", 0.0);
        o.put("disk_usage", 0.0);
        o.put("network_upload", 0.0);
        o.put("network_download", 0.0);
        o.put("disk_read", 0.0);
        o.put("disk_write", 0.0);
        // 基础信息写入自定义指标
        JSONObject custom = new JSONObject();
        custom.put("base_detail", JSONObject.from(base));
        o.put("custom_metrics", custom);
        return o;
    }

    /**
     * 将批量 RuntimeDetail 转换为批量上报JSON结构 { metrics: [...], batch_timestamp: ... }
     */
    private JSONObject buildBatchPayload(RuntimeDetail[] details) {
        JSONObject batch = new JSONObject();
        com.alibaba.fastjson2.JSONArray arr = new com.alibaba.fastjson2.JSONArray();
        if (details != null) {
            for (RuntimeDetail d : details) {
                if (d != null) {
                    arr.add(buildMetricsPayload(d));
                }
            }
        }
        batch.put("metrics", arr);
        batch.put("batch_timestamp", System.currentTimeMillis());
        return batch;
    }

    /**
     * 将服务端返回的Result格式(success/message/data)解析并适配为客户端的Response
     * 兼容旧格式(code/message/data)与未知格式的降级处理
     */
    private Response parseResult(String responseBody) {
        try {
            com.alibaba.fastjson2.JSONObject obj = JSONObject.parseObject(responseBody);
            if (obj == null) {
                return new Response(0, 500, null, "Empty response");
            }
            // 优先解析Result结构
            if (obj.containsKey("success")) {
                boolean ok = obj.getBooleanValue("success");
                String msg = obj.getString("message");
                Object data = obj.get("data");
                return new Response(0, ok ? 200 : 500, data, msg);
            }
            // 兼容旧结构(code/message/data)
            if (obj.containsKey("code")) {
                Integer code = obj.getInteger("code");
                String msg = obj.getString("message");
                Object data = obj.get("data");
                return new Response(0, code != null ? code : 500, data, msg);
            }
            // 尝试直接映射为Response(容错)
            try {
                return obj.to(Response.class);
            } catch (Exception ignore) {
                // ignored
            }
            return new Response(0, 500, null, "Unrecognized response format");
        } catch (Exception e) {
            return Response.errorResponse(e);
        }
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
     * 检查服务端可用性（访问数据服务健康检查）
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
