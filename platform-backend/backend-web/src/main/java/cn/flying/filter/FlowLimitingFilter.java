package cn.flying.filter;

import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.util.Const;
import cn.flying.common.util.DistributedRateLimiter;
import cn.flying.common.util.DistributedRateLimiter.RateLimitResult;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.PrintWriter;

/**
 * 分布式限流控制过滤器。
 * 基于 Redis Lua 脚本实现，支持多实例部署。
 * <p>
 * 限流策略：
 * 1. 窗口期内请求次数超过阈值时触发限流
 * 2. 触发限流后设置封禁 key，封禁期间所有请求被拒绝
 * 3. 所有操作原子执行，无需本地锁
 */
@Slf4j
@Component
@Order(Const.ORDER_FLOW_LIMIT)
public class FlowLimitingFilter extends HttpFilter {

    @Resource
    private DistributedRateLimiter rateLimiter;

    /**
     * 窗口期内最大请求次数
     */
    @Value("${spring.web.flow.limit:50}")
    private int limit;

    /**
     * 计数窗口期（秒）
     */
    @Value("${spring.web.flow.period:10}")
    private int period;

    /**
     * 超限后封禁时间（秒）
     */
    @Value("${spring.web.flow.block:300}")
    private int blockTime;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String clientIp = getClientIp(request);

        RateLimitResult result = rateLimiter.tryAcquireWithBlock(
                Const.FLOW_LIMIT_COUNTER + clientIp,
                Const.FLOW_LIMIT_BLOCK + clientIp,
                limit,
                period,
                blockTime
        );

        if (result == RateLimitResult.ALLOWED) {
            chain.doFilter(request, response);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("Rate limited: ip={}, result={}", clientIp, result);
            }
            writeBlockMessage(response, result);
        }
    }

    /**
     * 获取客户端真实 IP。
     * 支持反向代理场景（X-Forwarded-For、X-Real-IP）。
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            // 多次代理时取第一个 IP
            int commaIndex = ip.indexOf(',');
            return commaIndex > 0 ? ip.substring(0, commaIndex).trim() : ip.trim();
        }

        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }

        return request.getRemoteAddr();
    }

    /**
     * 写入限流响应。
     */
    private void writeBlockMessage(HttpServletResponse response, RateLimitResult result) throws IOException {
        // HTTP 429 Too Many Requests
        response.setStatus(429);
        response.setContentType("application/json;charset=utf-8");
        PrintWriter writer = response.getWriter();
        writer.write(Result.error(ResultEnum.PERMISSION_LIMIT).toString());
    }
}
