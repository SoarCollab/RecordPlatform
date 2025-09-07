package cn.flying.identity.filter;

import cn.dev33.satoken.stp.StpUtil;
import cn.flying.identity.service.TrafficMonitorService;
import cn.flying.identity.util.IpUtils;
import cn.flying.platformapi.constant.Result;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;
import java.util.UUID;

/**
 * 流量监控过滤器
 * 集成流量监控、异常检测和自动拦截功能
 *
 * @author 王贝强
 */
@Slf4j
@Component
@Order(-50) // 确保在其他过滤器之前执行
public class TrafficMonitorFilter implements Filter {

    @Resource
    private TrafficMonitorService trafficMonitorService;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // 生成请求ID用于链路追踪
        String requestId = UUID.randomUUID().toString();
        httpRequest.setAttribute("requestId", requestId);

        // 获取请求信息
        String clientIp = IpUtils.getClientIp(httpRequest);
        Long userId = getCurrentUserId();
        String requestPath = httpRequest.getRequestURI();
        String requestMethod = httpRequest.getMethod();
        String userAgent = httpRequest.getHeader("User-Agent");

        // 记录请求开始时间
        long startTime = System.currentTimeMillis();

        try {
            // 记录流量信息
            trafficMonitorService.recordTrafficInfo(requestId, clientIp, userId, 
                                                   requestPath, requestMethod, userAgent);

            // 检查是否需要拦截
            Result<Map<String, Object>> blockResult = trafficMonitorService.checkTrafficBlock(
                    clientIp, userId, requestPath, userAgent);
            
            if (blockResult.getCode() == 1 && blockResult.getData() != null) {
                Map<String, Object> blockInfo = blockResult.getData();
                Boolean blocked = (Boolean) blockInfo.get("blocked");
                
                if (Boolean.TRUE.equals(blocked)) {
                    handleBlockedRequest(httpResponse, blockInfo);
                    return;
                }
            }

            // 继续处理请求
            chain.doFilter(request, response);

        } catch (Exception e) {
            log.error("流量监控过滤器处理异常", e);
            // 异常情况下也要继续处理请求，避免影响正常业务
            chain.doFilter(request, response);
        } finally {
            // 记录响应信息
            recordResponseInfo(requestId, httpResponse, startTime, clientIp, userId, requestPath);
        }
    }

    /**
     * 处理被拦截的请求
     */
    private void handleBlockedRequest(HttpServletResponse response, Map<String, Object> blockInfo) 
            throws IOException {
        
        Integer blockLevel = (Integer) blockInfo.get("blockLevel");
        String blockReason = (String) blockInfo.get("blockReason");
        Object retryAfterObj = blockInfo.get("retryAfter");
        
        // 设置响应状态和头部
        switch (blockLevel) {
            case 1: // 限流
                response.setStatus(429); // Too Many Requests
                if (retryAfterObj != null) {
                    response.setHeader("Retry-After", retryAfterObj.toString());
                }
                break;
            case 2: // 临时拦截
                response.setStatus(403); // Forbidden
                break;
            case 3: // 黑名单
            case 4: // 永久封禁
                response.setStatus(403); // Forbidden
                break;
            default:
                response.setStatus(429);
        }

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        // 构建响应体
        Result<String> errorResult = Result.error(blockReason != null ? blockReason : "Request blocked");
        
        try (PrintWriter writer = response.getWriter()) {
            writer.write(JSONUtil.toJsonStr(errorResult));
            writer.flush();
        }

        log.warn("请求被拦截 - 拦截级别: {}, 原因: {}, 详情: {}", blockLevel, blockReason, blockInfo);
    }

    /**
     * 记录响应信息
     */
    private void recordResponseInfo(String requestId, HttpServletResponse response, 
                                   long startTime, String clientIp, Long userId, String requestPath) {
        try {
            long responseTime = System.currentTimeMillis() - startTime;
            Integer responseStatus = response.getStatus();
            
            // 记录响应信息
            trafficMonitorService.recordResponseInfo(requestId, responseStatus, responseTime, 0L, 0L);

            // 执行异常检测
            trafficMonitorService.detectAnomalies(clientIp, userId, requestPath, responseTime, responseStatus);

        } catch (Exception e) {
            log.error("记录响应信息失败", e);
        }
    }

    /**
     * 获取当前用户ID
     */
    private Long getCurrentUserId() {
        try {
            if (StpUtil.isLogin()) {
                return StpUtil.getLoginIdAsLong();
            }
        } catch (Exception e) {
            // 忽略获取用户ID的异常
        }
        return null;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        log.info("流量监控过滤器初始化完成");
    }

    @Override
    public void destroy() {
        log.info("流量监控过滤器销毁");
    }
}
