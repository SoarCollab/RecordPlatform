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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * 请求日志过滤器，用于记录所有用户请求信息
 * 与操作日志切面配合使用，该过滤器负责常规请求日志，操作日志切面负责业务操作日志
 */
@Slf4j
@Component
@Order(Const.LOG_ORDER)
public class RequestLogFilter extends OncePerRequestFilter {

    private final Set<String> ignores = Set.of("/favicon.ico","/webjars","/doc.html","/swagger-ui","/v3/api-docs","/api/file","/api/system/logs");

    @Override
    protected void doFilterInternal(HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull FilterChain filterChain) throws ServletException, IOException {
        if(this.isIgnoreUrl(request.getServletPath())) {
            filterChain.doFilter(request, response);
        } else {
            // 设置请求唯一ID
            String reqId = IdUtils.nextLogId();
            MDC.put(Const.ATTR_REQ_ID, reqId);
            
            long startTime = System.currentTimeMillis();
            this.logRequestStart(request);
            
            // 使用ContentCachingResponseWrapper包装响应，允许多次读取响应内容
            ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
            try {
                filterChain.doFilter(request, wrapper);
                this.logRequestEnd(wrapper, startTime);
            } finally {
                wrapper.copyBodyToResponse();
                // 清理MDC
                MDC.remove(Const.ATTR_REQ_ID);
            }
        }
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
     * @param wrapper 用于读取响应结果的包装类
     * @param startTime 起始时间
     */
    public void logRequestEnd(ContentCachingResponseWrapper wrapper, long startTime){
        long time = System.currentTimeMillis() - startTime;
        int status = wrapper.getStatus();
        
        // 仅在DEBUG级别打印完整响应内容
        if (log.isDebugEnabled()) {
            String content = status != 200 ?
                    status + " 错误" : new String(wrapper.getContentAsByteArray());
            log.debug("请求处理耗时: {}ms | 响应状态: {} | 响应结果: {}", time, status, content);
        } else {
            log.info("请求处理耗时: {}ms | 响应状态: {}", time, status);
        }
    }

    /**
     * 请求开始时的日志打印，包含请求全部信息，以及对应用户角色
     * @param request 请求
     */
    public void logRequestStart(HttpServletRequest request){
        // 将请求参数转换为JSON
        JSONObject object = new JSONObject();
        request.getParameterMap().forEach((k, v) -> object.set(k, v.length > 0 ? v[0] : null));
        
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
}
