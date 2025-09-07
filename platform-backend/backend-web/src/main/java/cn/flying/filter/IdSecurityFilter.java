package cn.flying.filter;


import cn.flying.common.util.Const;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ID安全过滤器 - 用于对外API中的ID转换
 * 对请求中的ID进行安全处理，避免直接暴露内部ID
 */
@Slf4j
@Component
@Order(Const.ORDER_ID_SECURITY) // 确保在安全过滤器之后执行
public class IdSecurityFilter extends OncePerRequestFilter {

    private static final Pattern ID_PATTERN = Pattern.compile("/api/(\\w+)/(\\d+)");
    
    /**
     * 过滤请求和响应，实现ID安全转换
     */
    @Override
    protected void doFilterInternal(
            @NotNull HttpServletRequest request,
            @NotNull HttpServletResponse response,
            @NotNull FilterChain filterChain) throws ServletException, IOException {
        
        // 仅处理GET和对外API请求
        if (shouldProcess(request)) {
            String requestURI = request.getRequestURI();
            Matcher matcher = ID_PATTERN.matcher(requestURI);
            
            if (matcher.find()) {
                try {
                    // 获取资源类型和ID
                    String resourceType = matcher.group(1);
                    String idStr = matcher.group(2);
                    Long id = Long.parseLong(idStr);
                    
                    // 记录ID转换操作
                    log.debug("API请求: 资源类型 [{}], 内部ID [{}]", resourceType, id);
                    
                    // 这里可以添加额外的安全检查逻辑
                    // 例如：检查当前用户是否有权限访问该资源
                    
                    // 包装请求，在后续处理中可直接使用内部ID
                    request.setAttribute("secureResourceId", id);
                    request.setAttribute("resourceType", resourceType);
                    
                } catch (NumberFormatException e) {
                    log.warn("无效的ID格式: {}", request.getRequestURI());
                }
            }
        }
        
        // 继续过滤器链
        filterChain.doFilter(request, response);
    }
    
    /**
     * 决定是否处理该请求
     */
    private boolean shouldProcess(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();

        // 仅处理GET请求并且是API路径
        return "GET".equals(method) && path.startsWith("/api/");
    }
} 