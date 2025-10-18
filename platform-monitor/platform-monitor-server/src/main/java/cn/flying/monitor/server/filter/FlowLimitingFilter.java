package cn.flying.monitor.server.filter;

import cn.flying.monitor.common.entity.Result;
import cn.flying.monitor.server.utils.Const;
import cn.flying.monitor.server.utils.FlowUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 限流控制过滤器
 * 防止用户高频请求接口，借助Redis进行限流
 */
@Slf4j
@Component
@Order(Const.ORDER_FLOW_LIMIT)
public class FlowLimitingFilter extends HttpFilter {

    @Resource
    StringRedisTemplate template;
    @Value("${spring.web.flow.limit}")
    int limit;
    @Value("${spring.web.flow.period}")
    int period;
    @Value("${spring.web.flow.block}")
    int block;

    @Resource
    FlowUtils utils;

    @Resource
    ObjectMapper objectMapper;

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        String address = request.getRemoteAddr();
        if (!tryCount(address)) {
            this.writeBlockMessage(response);
        } else {
            chain.doFilter(request, response);
        }
    }

    private boolean tryCount(String address) {
        synchronized (address.intern()) {
            if (template.hasKey(Const.FLOW_LIMIT_BLOCK + address)) {
                return false;
            }
            String counterKey = Const.FLOW_LIMIT_COUNTER + address;
            String blockKey = Const.FLOW_LIMIT_BLOCK + address;
            return utils.limitPeriodCheck(counterKey, blockKey, block, limit, period);
        }
    }

    private void writeBlockMessage(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.error("操作频繁，请稍后再试"));
    }
}
