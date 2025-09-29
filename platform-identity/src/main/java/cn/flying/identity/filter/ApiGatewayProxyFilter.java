package cn.flying.identity.filter;

import cn.flying.identity.dto.apigateway.ApiCallLog;
import cn.flying.identity.dto.apigateway.ApiKey;
import cn.flying.identity.dto.apigateway.ApiRoute;
import cn.flying.identity.gateway.alert.AlertService;
import cn.flying.identity.gateway.cache.ApiGatewayCacheManager;
import cn.flying.identity.gateway.circuitbreaker.CircuitBreakerService;
import cn.flying.identity.gateway.circuitbreaker.FallbackStrategyManager;
import cn.flying.identity.gateway.loadbalance.LoadBalanceManager;
import cn.flying.identity.gateway.loadbalance.LoadBalanceStrategy;
import cn.flying.identity.gateway.pool.ApiGatewayConnectionPoolManager;
import cn.flying.identity.service.apigateway.*;
import cn.flying.platformapi.constant.Result;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * API网关代理过滤器
 * 核心功能：路由匹配、负载均衡、熔断降级、请求转发
 * <p>
 * 执行流程：
 * 1. API密钥验证
 * 2. 权限验证
 * 3. 路由匹配
 * 4. 负载均衡选择实例
 * 5. 熔断保护的请求执行
 * 6. 缓存处理
 * 7. 降级处理
 * 8. 告警通知
 *
 * @author 王贝强
 * @since 2025-10-11
 */
@Slf4j
@Component
@Order(2)  // 在EnhancedGatewayFilter之后执行
public class ApiGatewayProxyFilter implements Filter {

    /**
     * API网关路径前缀
     */
    private static final String GATEWAY_PATH_PREFIX = "/api/gateway/proxy";

    /**
     * 需要代理的路径前缀列表
     */
    private static final List<String> PROXY_PATH_PREFIXES = Arrays.asList(
            "/api/gateway/proxy",
            "/api/v1",
            "/api/v2",
            "/gateway"
    );

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Value("${api.gateway.proxy.enabled:true}")
    private boolean proxyEnabled;

    @Value("${api.gateway.proxy.timeout:30000}")
    private long defaultTimeout;

    @Resource
    private ApiKeyService apiKeyService;

    @Resource
    private ApiPermissionService apiPermissionService;

    @Resource
    private ApiRouteService apiRouteService;

    @Resource
    private ApiCallLogService apiCallLogService;

    @Resource
    private ApiQuotaService apiQuotaService;

    @Resource
    private LoadBalanceManager loadBalanceManager;

    @Resource
    private CircuitBreakerService circuitBreakerService;

    @Resource
    private FallbackStrategyManager fallbackStrategyManager;

    @Resource
    private ApiGatewayCacheManager cacheManager;

    @Resource
    private ApiGatewayConnectionPoolManager connectionPoolManager;

    @Resource
    private AlertService alertService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest originalRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 包装请求以支持多次读取请求体
        final HttpServletRequest httpRequest;
        if (!(originalRequest instanceof ContentCachingRequestWrapper)) {
            httpRequest = new ContentCachingRequestWrapper(originalRequest);
        } else {
            httpRequest = originalRequest;
        }

        String requestURI = httpRequest.getRequestURI();
        String method = httpRequest.getMethod();

        // 检查是否启用代理功能
        if (!proxyEnabled) {
            chain.doFilter(request, response);
            return;
        }

        // 去除context path
        if (StrUtil.isNotBlank(contextPath) && requestURI.startsWith(contextPath)) {
            requestURI = requestURI.substring(contextPath.length());
        }

        // 检查是否需要代理的路径
        if (!shouldProxy(requestURI)) {
            chain.doFilter(request, response);
            return;
        }

        long startTime = System.currentTimeMillis();
        String clientIp = getClientIp(httpRequest);
        String requestId = generateRequestId();
        Long appId = null;

        try {
            // 1. 验证API密钥（如果需要）
            String apiKey = extractApiKey(httpRequest);
            if (StrUtil.isNotBlank(apiKey)) {
                Result<Void> keyResult = apiKeyService.validateApiKey(apiKey);
                if (!keyResult.isSuccess()) {
                    recordFailedCallLog(requestId, appId, null, requestURI, method, clientIp, 401, "Invalid API key", startTime);
                    writeErrorResponse(httpResponse, HttpServletResponse.SC_UNAUTHORIZED,
                            "Invalid API key: " + keyResult.getMessage());
                    return;
                }

                // 获取ApiKey完整信息，包含appId
                Result<ApiKey> keyInfoResult = apiKeyService.getKeyInfoByApiKey(apiKey);
                if (keyInfoResult.isSuccess() && keyInfoResult.getData() != null) {
                    appId = keyInfoResult.getData().getAppId();
                    log.debug("从ApiKey获取到appId: {}", appId);
                }
            }

            // 2. 配额检查
            if (appId != null) {
                Result<Boolean> quotaResult = apiQuotaService.checkQuotaExceeded(appId, null);
                if (quotaResult.isSuccess() && quotaResult.getData()) {
                    recordFailedCallLog(requestId, appId, apiKey, requestURI, method, clientIp, 429, "Quota exceeded", startTime);
                    writeErrorResponse(httpResponse, 429,
                            "API quota exceeded");
                    return;
                }
            }

            // 3. 验证权限（基于路径）
            Result<Boolean> permResult = apiPermissionService.hasPermissionByPath(
                    Long.valueOf(requestURI), method, apiKey);
            if (!permResult.isSuccess() || !permResult.getData()) {
                writeErrorResponse(httpResponse, HttpServletResponse.SC_FORBIDDEN,
                        "Permission denied for path: " + requestURI);
                return;
            }

            // 3. 匹配路由
            String cleanPath = extractCleanPath(requestURI);
            Result<ApiRoute> routeResult = apiRouteService.matchRoute(cleanPath, method);
            if (!routeResult.isSuccess()) {
                writeErrorResponse(httpResponse, HttpServletResponse.SC_NOT_FOUND,
                        "No route found for: " + cleanPath);
                return;
            }

            ApiRoute route = routeResult.getData();
            log.info("匹配到路由: {} -> {}", cleanPath, route.getServiceName());

            // 4. 检查路由是否启用
            if (route.getRouteStatus() != 1) {
                writeErrorResponse(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Route is disabled");
                return;
            }

            // 5. 检查缓存（GET请求）
            String cacheKey = buildCacheKey(route.getId(), requestURI, method, httpRequest.getQueryString());
            if ("GET".equalsIgnoreCase(method)) {
                Object cachedResponse = cacheManager.get(cacheKey);
                if (cachedResponse != null) {
                    log.debug("命中缓存: {}", cacheKey);
                    writeCachedResponse(httpResponse, (CachedResponse) cachedResponse);
                    return;
                }
            }

            // 6. 选择负载均衡策略
            String loadBalanceStrategy = getLoadBalanceStrategy(route);
            String requestKey = clientIp + ":" + requestURI;

            // 7. 选择目标实例
            LoadBalanceStrategy.ServiceInstance instance = loadBalanceManager.selectInstance(
                    route.getServiceName(), loadBalanceStrategy, requestKey);
            if (instance == null) {
                // 没有可用实例,尝试降级
                Object fallbackResponse = fallbackStrategyManager.smartFallback(
                        route.getServiceName(), requestURI, null);
                if (fallbackResponse != null) {
                    writeFallbackResponse(httpResponse, fallbackResponse);
                    return;
                }
                writeErrorResponse(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "No available instance for service: " + route.getServiceName());
                return;
            }

            // 8. 构建目标URL
            String targetUrl = buildTargetUrl(instance, route, requestURI, httpRequest.getQueryString());
            log.info("转发请求: {} -> {}", requestURI, targetUrl);

            // 9. 使用熔断器执行请求（异步包装支持超时控制）
            String circuitBreakerName = "route-" + route.getId();
            CompletableFuture<ProxyResponse> future = CompletableFuture.supplyAsync(() ->
                    circuitBreakerService.executeAsync(
                            circuitBreakerName,
                            () -> executeHttpRequest(httpRequest, httpResponse, targetUrl, route),
                            () -> null  // fallback 返回 null,由后续逻辑处理降级
                    )
            );

            // 10. 等待结果（带超时）
            long timeout = route.getTimeout() != null ? route.getTimeout() : defaultTimeout;
            ProxyResponse proxyResponse;
            try {
                proxyResponse = future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // 重要：取消未完成的任务，防止资源泄漏
                boolean cancelled = future.cancel(true);
                log.warn("请求超时: {} -> {}, timeout={}ms, cancelled={}",
                        requestURI, targetUrl, timeout, cancelled);

                // 尝试降级
                Object fallbackResponse = fallbackStrategyManager.smartFallback(
                        route.getServiceName(), requestURI, e);
                if (fallbackResponse != null) {
                    writeFallbackResponse(httpResponse, fallbackResponse);
                } else {
                    writeErrorResponse(httpResponse, HttpServletResponse.SC_GATEWAY_TIMEOUT,
                            "Request timeout after " + timeout + "ms");
                }

                // 发送告警
                alertService.sendAlert("TIMEOUT",
                        String.format("Route %s timeout: %s -> %s",
                                route.getId(), requestURI, targetUrl),
                        "HIGH");
                return;
            } catch (InterruptedException e) {
                // 线程被中断，取消任务
                future.cancel(true);
                Thread.currentThread().interrupt(); // 恢复中断状态
                log.error("请求被中断: {} -> {}", requestURI, targetUrl, e);
                writeErrorResponse(httpResponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Request was interrupted");
                return;
            } catch (Exception e) {
                // 其他异常也要取消任务
                future.cancel(true);
                log.error("请求执行异常: {} -> {}", requestURI, targetUrl, e);
                throw e;
            }

            // 11. 处理响应
            if (proxyResponse.isSuccess()) {
                // 写入响应
                writeProxyResponse(httpResponse, proxyResponse);

                // 缓存成功的GET响应
                if ("GET".equalsIgnoreCase(method) && proxyResponse.getStatusCode() == 200) {
                    cacheManager.put("proxy", cacheKey, new CachedResponse(proxyResponse), 300); // 缓存5分钟
                }

                // 更新负载均衡统计
                long responseTime = System.currentTimeMillis() - startTime;
                loadBalanceManager.updateInstanceStats(route.getServiceName(),
                        instance.getInstanceId(), responseTime, true);

                // 记录成功的调用日志
                recordSuccessCallLog(requestId, appId, apiKey, route.getId(), requestURI, method,
                        httpRequest.getQueryString(), clientIp, proxyResponse.getStatusCode(),
                        (int) responseTime, proxyResponse.getBody() != null ? proxyResponse.getBody().length : 0L,
                        httpRequest.getHeader("User-Agent"));

                // 增加配额使用量
                if (appId != null) {
                    apiQuotaService.incrementQuotaUsage(appId, route.getId(), 1);
                }

            } else {
                // 请求失败，尝试降级
                log.error("请求失败: {} -> {}, status={}",
                        requestURI, targetUrl, proxyResponse.getStatusCode());

                // 更新负载均衡统计
                loadBalanceManager.updateInstanceStats(route.getServiceName(),
                        instance.getInstanceId(), 0, false);

                // 尝试降级
                Object fallbackResponse = fallbackStrategyManager.smartFallback(
                        route.getServiceName(), requestURI,
                        new RuntimeException("HTTP " + proxyResponse.getStatusCode()));

                if (fallbackResponse != null) {
                    writeFallbackResponse(httpResponse, fallbackResponse);
                } else {
                    writeProxyResponse(httpResponse, proxyResponse);
                }

                // 发送告警（5xx错误）
                if (proxyResponse.getStatusCode() >= 500) {
                    alertService.sendAlert("SERVER_ERROR",
                            String.format("Route %s error: %s -> %s, status=%d",
                                    route.getId(), requestURI, targetUrl, proxyResponse.getStatusCode()),
                            "MEDIUM");
                }
            }

        } catch (Exception e) {
            log.error("代理请求处理异常: {} -> {}", requestURI, e.getMessage(), e);

            // 尝试降级
            try {
                Object fallbackResponse = fallbackStrategyManager.smartFallback(
                        null, requestURI, e);
                if (fallbackResponse != null) {
                    writeFallbackResponse(httpResponse, fallbackResponse);
                    return;
                }
            } catch (Exception fallbackEx) {
                log.error("降级处理失败", fallbackEx);
            }

            // 写入错误响应
            writeErrorResponse(httpResponse, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal gateway error: " + e.getMessage());

            // 发送告警
            alertService.sendAlert("GATEWAY_ERROR",
                    String.format("Gateway error for %s: %s", requestURI, e.getMessage()),
                    "HIGH");
        }
    }

    /**
     * 判断是否需要代理的路径
     */
    private boolean shouldProxy(String path) {
        return PROXY_PATH_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多个代理的情况下，取第一个IP
        if (StrUtil.isNotBlank(ip) && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 生成请求ID
     *
     * @return 请求ID
     */
    private String generateRequestId() {
        return "req_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 10000);
    }

    /**
     * 提取API密钥
     */
    private String extractApiKey(HttpServletRequest request) {
        // 从Header中获取
        String apiKey = request.getHeader("X-API-Key");
        if (StrUtil.isBlank(apiKey)) {
            apiKey = request.getHeader("Api-Key");
        }
        // 从参数中获取
        if (StrUtil.isBlank(apiKey)) {
            apiKey = request.getParameter("api_key");
        }
        return apiKey;
    }

    /**
     * 记录失败的调用日志
     */
    private void recordFailedCallLog(String requestId, Long appId, String apiKey,
                                     String interfacePath, String method, String clientIp,
                                     int responseCode, String errorMessage, long startTime) {
        try {
            ApiCallLog log = new ApiCallLog();
            log.setRequestId(requestId);
            log.setAppId(appId);
            log.setApiKey(apiKey);
            log.setInterfacePath(interfacePath);
            log.setRequestMethod(method);
            log.setRequestIp(clientIp);
            log.setRequestTime(java.time.LocalDateTime.now());
            log.setResponseCode(responseCode);
            log.setResponseTime((int) (System.currentTimeMillis() - startTime));
            log.setErrorMessage(errorMessage);

            // 异步记录日志
            apiCallLogService.recordCallLogAsync(log);
        } catch (Exception e) {
            log.error("记录失败调用日志失败", e);
        }
    }

    /**
     * 写入错误响应
     */
    private void writeErrorResponse(HttpServletResponse response, int statusCode, String message)
            throws IOException {
        response.setStatus(statusCode);
        response.setContentType("application/json;charset=UTF-8");

        Map<String, Object> error = new HashMap<>();
        error.put("code", statusCode);
        error.put("message", message);
        error.put("timestamp", System.currentTimeMillis());

        response.getWriter().write(JSONUtil.toJsonStr(error));
    }

    /**
     * 提取干净的路径（去除代理前缀）
     */
    private String extractCleanPath(String requestURI) {
        for (String prefix : PROXY_PATH_PREFIXES) {
            if (requestURI.startsWith(prefix)) {
                return requestURI.substring(prefix.length());
            }
        }
        return requestURI;
    }

    /**
     * 构建缓存键
     */
    private String buildCacheKey(Long routeId, String path, String method, String queryString) {
        return String.format("route:%d:%s:%s:%s",
                routeId, method, path,
                queryString != null ? queryString : "");
    }

    /**
     * 写入缓存的响应
     */
    private void writeCachedResponse(HttpServletResponse response, CachedResponse cachedResponse)
            throws IOException {
        response.setStatus(cachedResponse.getStatusCode());
        cachedResponse.getHeaders().forEach(response::setHeader);
        if (cachedResponse.getBody() != null) {
            response.getOutputStream().write(cachedResponse.getBody());
        }
    }

    /**
     * 获取负载均衡策略
     */
    private String getLoadBalanceStrategy(ApiRoute route) {
        if (route.getLoadBalance() == null) {
            return "roundRobin";
        }
        switch (route.getLoadBalance()) {
            case 1:
                return "roundRobin";
            case 2:
                return "random";
            case 3:
                return "leastConnection";
            case 4:
                return "consistentHash";
            case 5:
                return "responseTime";
            default:
                return "roundRobin";
        }
    }

    /**
     * 写入降级响应
     */
    private void writeFallbackResponse(HttpServletResponse response, Object fallbackResponse)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("X-Fallback", "true");

        if (fallbackResponse instanceof String) {
            response.getWriter().write((String) fallbackResponse);
        } else {
            response.getWriter().write(JSONUtil.toJsonStr(fallbackResponse));
        }
    }

    /**
     * 构建目标URL
     */
    private String buildTargetUrl(LoadBalanceStrategy.ServiceInstance instance,
                                  ApiRoute route, String requestPath, String queryString) {
        StringBuilder url = new StringBuilder();
        url.append("http://");
        url.append(instance.getHost());
        url.append(":");
        url.append(instance.getPort());

        // 添加目标路径
        if (StrUtil.isNotBlank(route.getTargetPath())) {
            url.append(route.getTargetPath());
        } else {
            url.append(requestPath);
        }

        // 添加查询参数
        if (StrUtil.isNotBlank(queryString)) {
            url.append("?").append(queryString);
        }

        return url.toString();
    }

    /**
     * 执行HTTP请求
     */
    private ProxyResponse executeHttpRequest(HttpServletRequest request,
                                             HttpServletResponse response,
                                             String targetUrl,
                                             ApiRoute route) throws IOException {
        CloseableHttpClient httpClient = connectionPoolManager.getServiceHttpClient(
                route.getServiceName());

        // 创建请求
        HttpUriRequestBase httpRequest = createHttpRequest(request, targetUrl);

        // 复制请求头
        copyRequestHeaders(request, httpRequest);

        // 执行请求
        try (CloseableHttpResponse httpResponse = httpClient.execute(httpRequest)) {
            // 构建响应
            ProxyResponse proxyResponse = new ProxyResponse();
            proxyResponse.setStatusCode(httpResponse.getCode());
            proxyResponse.setSuccess(httpResponse.getCode() >= 200 && httpResponse.getCode() < 300);

            // 复制响应头
            Map<String, String> headers = new HashMap<>();
            Arrays.stream(httpResponse.getHeaders()).forEach(header ->
                    headers.put(header.getName(), header.getValue())
            );
            proxyResponse.setHeaders(headers);

            // 读取响应体
            HttpEntity entity = httpResponse.getEntity();
            if (entity != null) {
                byte[] content = EntityUtils.toByteArray(entity);
                proxyResponse.setBody(content);
                proxyResponse.setContentType(entity.getContentType());
            }

            // 记录请求结果
            connectionPoolManager.recordRequestResult(route.getServiceName(),
                    proxyResponse.isSuccess(),
                    System.currentTimeMillis());

            return proxyResponse;
        }
    }

    /**
     * 写入代理响应
     */
    private void writeProxyResponse(HttpServletResponse response, ProxyResponse proxyResponse)
            throws IOException {
        // 设置状态码
        response.setStatus(proxyResponse.getStatusCode());

        // 复制响应头
        proxyResponse.getHeaders().forEach((name, value) -> {
            if (!shouldSkipResponseHeader(name)) {
                response.setHeader(name, value);
            }
        });

        // 设置内容类型
        if (StrUtil.isNotBlank(proxyResponse.getContentType())) {
            response.setContentType(proxyResponse.getContentType());
        }

        // 写入响应体
        if (proxyResponse.getBody() != null) {
            response.getOutputStream().write(proxyResponse.getBody());
        }
    }

    /**
     * 记录成功的调用日志
     */
    private void recordSuccessCallLog(String requestId, Long appId, String apiKey, Long interfaceId,
                                      String interfacePath, String method, String queryString,
                                      String clientIp, int responseCode, int responseTime,
                                      long responseSize, String userAgent) {
        try {
            ApiCallLog log = new ApiCallLog();
            log.setRequestId(requestId);
            log.setAppId(appId);
            log.setApiKey(apiKey);
            log.setInterfaceId(interfaceId);
            log.setInterfacePath(interfacePath);
            log.setRequestMethod(method);
            log.setRequestParams(queryString);
            log.setRequestIp(clientIp);
            log.setRequestTime(java.time.LocalDateTime.now());
            log.setResponseCode(responseCode);
            log.setResponseTime(responseTime);
            log.setResponseSize(responseSize);
            log.setUserAgent(userAgent);

            // 异步记录日志
            apiCallLogService.recordCallLogAsync(log);
        } catch (Exception e) {
            log.error("记录调用日志失败", e);
        }
    }

    /**
     * 创建HTTP请求
     * 修复了请求体只能读取一次的问题
     */
    private HttpUriRequestBase createHttpRequest(HttpServletRequest request, String targetUrl)
            throws IOException {
        String method = request.getMethod().toUpperCase();
        HttpUriRequestBase httpRequest;

        // 获取请求体（支持多次读取）
        byte[] body = null;
        if (request instanceof ContentCachingRequestWrapper) {
            ContentCachingRequestWrapper wrapper = (ContentCachingRequestWrapper) request;
            body = wrapper.getContentAsByteArray();
            // 如果缓存为空，尝试读取原始输入流
            if (body.length == 0 && wrapper.getInputStream().available() > 0) {
                body = StreamUtils.copyToByteArray(wrapper.getInputStream());
            }
        } else {
            // 回退到原始方式（不推荐）
            body = StreamUtils.copyToByteArray(request.getInputStream());
        }

        switch (method) {
            case "GET":
                httpRequest = new HttpGet(targetUrl);
                break;
            case "POST":
                httpRequest = new HttpPost(targetUrl);
                // 设置请求体
                if (body != null && body.length > 0) {
                    ((HttpPost) httpRequest).setEntity(new ByteArrayEntity(body,
                            ContentType.parse(request.getContentType())));
                }
                break;
            case "PUT":
                httpRequest = new HttpPut(targetUrl);
                // 设置请求体
                if (body != null && body.length > 0) {
                    ((HttpPut) httpRequest).setEntity(new ByteArrayEntity(body,
                            ContentType.parse(request.getContentType())));
                }
                break;
            case "DELETE":
                httpRequest = new HttpDelete(targetUrl);
                break;
            case "HEAD":
                httpRequest = new HttpHead(targetUrl);
                break;
            case "OPTIONS":
                httpRequest = new HttpOptions(targetUrl);
                break;
            case "PATCH":
                httpRequest = new HttpPatch(targetUrl);
                // 设置请求体
                if (body != null && body.length > 0) {
                    ((HttpPatch) httpRequest).setEntity(new ByteArrayEntity(body,
                            ContentType.parse(request.getContentType())));
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported HTTP method: " + method);
        }

        return httpRequest;
    }

    /**
     * 复制请求头
     */
    private void copyRequestHeaders(HttpServletRequest request, HttpUriRequestBase httpRequest) {
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            // 跳过一些特殊的header
            if (shouldSkipHeader(headerName)) {
                continue;
            }
            String headerValue = request.getHeader(headerName);
            httpRequest.addHeader(headerName, headerValue);
        }
        // 添加X-Forwarded-For等代理头
        httpRequest.addHeader("X-Forwarded-For", getClientIp(request));
        httpRequest.addHeader("X-Real-IP", getClientIp(request));
        httpRequest.addHeader("X-Forwarded-Proto", request.getScheme());
    }

    /**
     * 判断是否跳过某些响应头
     */
    private boolean shouldSkipResponseHeader(String headerName) {
        return "Transfer-Encoding".equalsIgnoreCase(headerName) ||
                "Content-Encoding".equalsIgnoreCase(headerName);
    }

    /**
     * 判断是否跳过某些请求头
     */
    private boolean shouldSkipHeader(String headerName) {
        return "Host".equalsIgnoreCase(headerName) ||
                "Connection".equalsIgnoreCase(headerName) ||
                "Content-Length".equalsIgnoreCase(headerName) ||
                "Transfer-Encoding".equalsIgnoreCase(headerName);
    }

    /**
     * 代理响应包装类
     */
    private static class ProxyResponse {
        private int statusCode;
        private boolean success;
        private Map<String, String> headers;
        private byte[] body;
        private String contentType;

        // getters and setters
        public int getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public byte[] getBody() {
            return body;
        }

        public void setBody(byte[] body) {
            this.body = body;
        }

        public String getContentType() {
            return contentType;
        }

        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
    }

    /**
     * 缓存响应包装类
     */
    @Getter
    private static class CachedResponse {
        // getters
        private final int statusCode;
        private final Map<String, String> headers;
        private final byte[] body;
        private final long timestamp;

        public CachedResponse(ProxyResponse proxyResponse) {
            this.statusCode = proxyResponse.getStatusCode();
            this.headers = new HashMap<>(proxyResponse.getHeaders());
            this.body = proxyResponse.getBody();
            this.timestamp = System.currentTimeMillis();
        }

    }
}