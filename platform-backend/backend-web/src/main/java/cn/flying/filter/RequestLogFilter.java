package cn.flying.filter;

import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.hutool.json.JSONObject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 请求日志过滤器，用于记录所有用户请求信息
 * 与操作日志切面配合使用，该过滤器负责常规请求日志，操作日志切面负责业务操作日志
 */
@Slf4j
@Component
@Order(Const.LOG_ORDER)
public class RequestLogFilter extends OncePerRequestFilter {

    private final Set<String> ignores = Set.of(
            "/favicon.ico",
            "/webjars",
            "/doc.html",
            "/swagger-ui",
            "/v3/api-docs",
            "/api/system/logs"
    );

    /**
     * 敏感参数列表，这些参数在日志中会被脱敏处理
     */
    private static final Set<String> SENSITIVE_PARAMS = Set.of(
            "password", "pwd", "oldPassword", "newPassword", "new_password", "old_password",
            "token", "secret", "secretKey", "accessKey", "apiKey", "privateKey",
            "creditCard", "cardNumber", "cvv", "ssn"
    );

    private static final String MASK = "***";

    /**
     * 响应体日志截断阈值（字节）。
     * <p>
     * 目的：避免日志输出/复制大块内容（如文件分片、图片、base64 大 JSON），降低 GC 与 I/O 开销。
     * </p>
     */
    private static final int MAX_RESPONSE_LOG_BYTES = 4096;

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        if(this.isIgnoreUrl(request.getServletPath())) {
            filterChain.doFilter(request, response);
        } else {
            if (isSseRequest(request)) {
                // SSE 请求特殊处理：不使用 ContentCachingResponseWrapper，避免缓存导致流关闭
                String reqId = IdUtils.nextLogId();
                MDC.put(Const.ATTR_REQ_ID, reqId);
                long startTime = System.currentTimeMillis();
                this.logRequestStart(request);
                try {
                    filterChain.doFilter(request, response);
                } finally {
                    long time = System.currentTimeMillis() - startTime;
                    if (log.isInfoEnabled()) {
                        log.info("SSE连接保持: 处理耗时: {}ms | 响应状态: {}", time, response.getStatus());
                    }
                    MDC.remove(Const.ATTR_REQ_ID);
                }
            } else {
                // 常规请求：按需包装响应，记录响应信息（文件/下载类接口跳过响应体缓存）
                String reqId = IdUtils.nextLogId();
                MDC.put(Const.ATTR_REQ_ID, reqId);

                long startTime = System.currentTimeMillis();
                this.logRequestStart(request);

                if (shouldSkipResponseBodyCache(request)) {
                    try {
                        filterChain.doFilter(request, response);
                        this.logRequestEndWithoutBody(response, startTime);
                    } finally {
                        // 清理MDC
                        MDC.remove(Const.ATTR_REQ_ID);
                    }
                } else {
                    // 使用ContentCachingResponseWrapper包装响应，允许多次读取响应内容
                    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
                    try {
                        filterChain.doFilter(request, wrapper);
                        this.logRequestEnd(request, wrapper, startTime);
                    } finally {
                        wrapper.copyBodyToResponse();
                        // 清理MDC
                        MDC.remove(Const.ATTR_REQ_ID);
                    }
                }
            }
        }
    }

    /**
     * 判断是否为 SSE 请求
     */
    private boolean isSseRequest(HttpServletRequest request) {
        String contentType = request.getHeader("Accept");
        return (contentType != null && contentType.contains(MediaType.TEXT_EVENT_STREAM_VALUE))
                || request.getServletPath().contains("/sse/connect");
    }

    /**
     * 判断是否跳过响应体缓存与输出（例如：文件/图片下载接口）。
     *
     * @param request 请求
     * @return true 表示不包装响应（避免缓存/打印大块内容）
     */
    private boolean shouldSkipResponseBodyCache(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null || requestUri.isBlank()) {
            requestUri = request.getServletPath();
        }
        // 统一按路径关键字跳过：download 类接口可能返回二进制或大块 base64 JSON
        return requestUri != null && requestUri.contains("/download");
    }

    /**
     * 判定当前请求url是否不需要日志打印
     * @param url 路径
     * @return 是否忽略
     */
    private boolean isIgnoreUrl(String url){
        for (String ignore : ignores) {
            if(url.startsWith(ignore)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 请求结束时的日志打印，包含处理耗时以及响应结果
     * 使用INFO级别，便于在生产环境定位请求问题
     *
     * @param request 请求
     * @param wrapper 用于读取响应结果的包装类
     * @param startTime 起始时间
     */
    public void logRequestEnd(HttpServletRequest request, ContentCachingResponseWrapper wrapper, long startTime){
        long time = System.currentTimeMillis() - startTime;
        int status = wrapper.getStatus();

        // 使用INFO级别打印响应日志
        if (log.isInfoEnabled()) {
            byte[] body = wrapper.getContentAsByteArray();
            String content = buildResponseLogContent(request, wrapper.getContentType(), status, body);
            log.info("请求处理耗时: {}ms | 响应状态: {} | 响应结果: {}", time, status, content);
        }
    }

    /**
     * 请求结束日志（不输出响应体）。
     *
     * @param response 响应
     * @param startTime 起始时间
     */
    public void logRequestEndWithoutBody(HttpServletResponse response, long startTime) {
        if (!log.isInfoEnabled()) {
            return;
        }
        long time = System.currentTimeMillis() - startTime;
        log.info("请求处理耗时: {}ms | 响应状态: {} | 响应结果: {}", time, response.getStatus(), "<skipped>");
    }

    /**
     * 构建响应体日志内容：对文件/二进制内容跳过，对大响应做截断。
     *
     * @param request 请求
     * @param contentType 响应 Content-Type
     * @param status HTTP 状态码
     * @param body 响应体字节数组
     * @return 可用于日志输出的字符串
     */
    private String buildResponseLogContent(HttpServletRequest request, String contentType, int status, byte[] body) {
        int bodySize = body == null ? 0 : body.length;
        if (shouldSkipResponseBodyCache(request)) {
            return "<skipped>";
        }
        if (bodySize == 0) {
            return "";
        }

        if (isBinaryContentType(contentType)) {
            return "<binary omitted: contentType=" + contentType + ", bytes=" + bodySize + ">";
        }

        int limit = Math.min(bodySize, MAX_RESPONSE_LOG_BYTES);
        String preview = new String(body, 0, limit, StandardCharsets.UTF_8);
        if (bodySize > MAX_RESPONSE_LOG_BYTES) {
            return preview + "...(truncated, bytes=" + bodySize + ")";
        }
        // 对非 200 的响应也输出内容，便于排障
        if (status != HttpServletResponse.SC_OK) {
            return preview;
        }
        return preview;
    }

    /**
     * 判断响应 Content-Type 是否属于二进制类型（不适合直接打印日志）。
     *
     * @param contentType 响应 Content-Type
     * @return true 表示二进制内容
     */
    private boolean isBinaryContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return ct.startsWith("image/")
                || ct.startsWith("video/")
                || ct.startsWith("audio/")
                || ct.contains(MediaType.APPLICATION_OCTET_STREAM_VALUE)
                || ct.contains("application/zip")
                || ct.contains("application/pdf");
    }

    /**
     * 请求开始时的日志打印，包含请求全部信息，以及对应用户角色
     * 使用INFO级别，便于在生产环境定位请求问题
     * 敏感参数会被脱敏处理
     * @param request 请求
     */
    public void logRequestStart(HttpServletRequest request){
        // 仅在INFO级别打印
        if (!log.isInfoEnabled()) {
            return;
        }

        // 将请求参数转换为JSON，敏感参数脱敏处理
        JSONObject object = new JSONObject();
        request.getParameterMap().forEach((k, v) -> {
            if (isSensitiveParam(k)) {
                object.set(k, MASK);
            } else {
                String value = v.length > 0 ? v[0] : null;
                object.set(k, sanitizeParameterForLog(k, value));
            }
        });

        // 获取用户信息
        Object id = request.getAttribute(Const.ATTR_USER_ID);
        if(id != null) {
            User user = (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            log.info("请求URL: \"{}\" ({}) | 远程IP地址: {} │ 身份: {} (UID: {}) | 角色: {} | 请求参数列表: {}",
                    request.getServletPath(), request.getMethod(), request.getRemoteAddr(),
                    user.getUsername(), id, user.getAuthorities(), object);
        } else {
            log.info("请求URL: \"{}\" ({}) | 远程IP地址: {} │ 身份: 未验证 | 请求参数列表: {}",
                    request.getServletPath(), request.getMethod(), request.getRemoteAddr(), object);
        }
    }

    /**
     * 判断参数是否为敏感参数（大小写不敏感）
     * @param paramName 参数名
     * @return 是否敏感
     */
    private boolean isSensitiveParam(String paramName) {
        if (paramName == null) return false;
        String lowerName = paramName.toLowerCase();
        return SENSITIVE_PARAMS.stream()
                .anyMatch(sensitive -> lowerName.contains(sensitive.toLowerCase()));
    }

    /**
     * 对请求参数做轻量化处理：避免把 base64/大文本等内容写入日志。
     *
     * @param paramName 参数名
     * @param value 参数值
     * @return 适合写入日志的值（可能被截断或替换为 &lt;omitted&gt;）
     */
    private Object sanitizeParameterForLog(String paramName, String value) {
        if (value == null) {
            return null;
        }
        String name = paramName == null ? "" : paramName.toLowerCase();
        // 明显的“文件内容/大文本”参数：直接省略（保留参数存在性）
        if (name.contains("base64")
                || name.contains("filecontent")
                || name.contains("filedata")
                || name.contains("bytes")
                || name.contains("content")) {
            if (value.length() > 128) {
                return "<omitted>";
            }
        }
        // 兜底：对任意超长参数做截断
        if (value.length() > 512) {
            return value.substring(0, 512) + "...(truncated, len=" + value.length() + ")";
        }
        return value;
    }
}
