package cn.flying.filter;

import cn.flying.common.util.Const;
import cn.flying.service.OAuth2UserInfoCacheService;
import cn.flying.service.OAuth2UserInfoCacheService.CachedUserInfo;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OAuth2 鉴权过滤器（增强版 - 包含缓存和熔断机制）
 * 面向外网部署场景，作为资源服务通过调用 Identity 的 /oauth/userinfo 接口
 * 校验 Bearer Token 是否有效，并将认证信息注入 Spring Security 上下文。
 * <p>
 * 增强特性：
 * 1. 缓存机制：减少对 Identity 服务的调用
 * 2. 熔断保护：防止 Identity 服务故障影响整体系统
 * 3. 详细的错误处理和日志记录
 * 4. 性能监控和指标收集
 */
@Slf4j
@Component
@Order(Const.SECURITY_ORDER - 1)
public class OAuth2AuthenticationFilter extends OncePerRequestFilter {

    // 监控指标
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final AtomicLong authSuccessCount = new AtomicLong(0);
    private final AtomicLong authFailureCount = new AtomicLong(0);
    private final AtomicLong circuitBreakerOpenCount = new AtomicLong(0);

    @Autowired(required = false)
    private OAuth2UserInfoCacheService userInfoCacheService;

    @Value("${oauth2.resource.userinfo-url:http://localhost:8081/oauth/userinfo}")
    private String userinfoUrl;

    @Value("${oauth2.filter.circuit-breaker.enabled:true}")
    private boolean circuitBreakerEnabled;

    @Value("${oauth2.filter.cache.enabled:true}")
    private boolean cacheEnabled;

    @Value("${oauth2.filter.fallback-on-error:true}")
    private boolean fallbackOnError;

    private RestTemplate restTemplate;

    private CircuitBreaker circuitBreaker;

    /**
     * 初始化 RestTemplate 和熔断器
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        // 初始化 RestTemplate
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(5000);
        this.restTemplate = new RestTemplate(factory);

        // 初始化熔断器
        if (circuitBreakerEnabled) {
            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .slidingWindowSize(10) // 滑动窗口大小
                    .minimumNumberOfCalls(5) // 最少调用次数
                    .failureRateThreshold(50) // 失败率阈值
                    .waitDurationInOpenState(Duration.ofSeconds(30)) // 开启状态持续时间
                    .slowCallRateThreshold(100) // 慢调用率阈值
                    .slowCallDurationThreshold(Duration.ofSeconds(3)) // 慢调用时间阈值
                    .build();

            CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
            circuitBreaker = registry.circuitBreaker("oauth2-userinfo");

            // 添加事件监听
            circuitBreaker.getEventPublisher()
                    .onStateTransition(event -> {
                        log.warn("熔断器状态变化: from {} to {}",
                                event.getStateTransition().getFromState(),
                                event.getStateTransition().getToState());
                        if (event.getStateTransition().getToState().equals(CircuitBreaker.State.OPEN)) {
                            circuitBreakerOpenCount.incrementAndGet();
                        }
                    })
                    .onError(event -> {
                        log.debug("熔断器记录错误: {}", event.getThrowable().getMessage());
                    });
        }

        log.info("OAuth2AuthenticationFilter 初始化完成 - 缓存: {}, 熔断器: {}",
                cacheEnabled ? "启用" : "禁用",
                circuitBreakerEnabled ? "启用" : "禁用");
    }

    /**
     * 过滤请求，解析 Authorization: Bearer <token>，通过缓存或远程接口校验令牌有效性
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    @NotNull HttpServletResponse response,
                                    @NotNull FilterChain filterChain) throws ServletException, IOException {

        totalRequests.incrementAndGet();
        String requestId = generateRequestId();
        MDC.put("requestId", requestId);

        try {
            // 检查是否有 Bearer Token
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                log.trace("[{}] 请求未携带 Bearer Token: {}", requestId, request.getRequestURI());
                filterChain.doFilter(request, response);
                return;
            }

            String accessToken = authorization.substring(7).trim();
            if (accessToken.isEmpty()) {
                log.debug("[{}] Bearer Token 为空", requestId);
                filterChain.doFilter(request, response);
                return;
            }

            // 已存在认证则直接放行
            if (SecurityContextHolder.getContext().getAuthentication() != null) {
                log.trace("[{}] 认证上下文已存在，跳过处理", requestId);
                filterChain.doFilter(request, response);
                return;
            }

            // 获取用户信息（优先从缓存）
            CachedUserInfo userInfo = null;
            long startTime = System.currentTimeMillis();

            try {
                if (cacheEnabled && userInfoCacheService != null) {
                    // 使用缓存服务
                    userInfo = getUserInfoWithCache(accessToken, requestId);
                } else {
                    // 直接调用远程接口（兼容没有缓存服务的情况）
                    userInfo = getUserInfoDirectly(accessToken, requestId);
                }

                long elapsedTime = System.currentTimeMillis() - startTime;
                if (elapsedTime > 1000) {
                    log.warn("[{}] 获取用户信息耗时过长: {}ms", requestId, elapsedTime);
                }

            } catch (Exception e) {
                log.error("[{}] 获取用户信息异常: {}", requestId, e.getMessage(), e);
                authFailureCount.incrementAndGet();

                if (!fallbackOnError) {
                    // 不允许降级，直接拒绝请求
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication service unavailable");
                    return;
                }
                // 允许降级，继续处理（可能导致后续授权失败）
            }

            // 处理用户信息
            if (userInfo != null && userInfo.isValid()) {
                // 创建认证对象
                createAuthentication(userInfo, request);
                authSuccessCount.incrementAndGet();
                log.debug("[{}] 用户认证成功: userId={}, username={}",
                        requestId, userInfo.getUserId(), userInfo.getUsername());
            } else {
                authFailureCount.incrementAndGet();
                if (userInfo != null) {
                    log.warn("[{}] 用户认证失败: {}", requestId, userInfo.getErrorMessage());
                } else {
                    log.warn("[{}] 无法获取用户信息", requestId);
                }
            }

            // 继续过滤链
            filterChain.doFilter(request, response);

        } finally {
            // 清理 MDC
            MDC.remove("requestId");
            MDC.remove(Const.ATTR_USER_ID);
            MDC.remove(Const.ATTR_USER_ROLE);

            // 定期输出统计信息
            if (totalRequests.get() % 1000 == 0) {
                logStatistics();
            }
        }
    }

    /**
     * 生成请求ID用于追踪
     */
    private String generateRequestId() {
        return String.format("%d-%d", System.currentTimeMillis(), Thread.currentThread().threadId());
    }

    /**
     * 从缓存服务获取用户信息
     */
    private CachedUserInfo getUserInfoWithCache(String accessToken, String requestId) {
        // 记录缓存访问
        CachedUserInfo userInfo = userInfoCacheService.getUserInfo(accessToken);

        if (userInfo != null && userInfo.getCacheTime() != null) {
            // 缓存命中
            cacheHits.incrementAndGet();
            log.trace("[{}] 缓存命中", requestId);
        } else {
            // 缓存未命中
            cacheMisses.incrementAndGet();
            log.trace("[{}] 缓存未命中", requestId);
        }

        return userInfo;
    }

    /**
     * 直接调用远程接口获取用户信息（用于无缓存场景或缓存服务不可用）
     */
    private CachedUserInfo getUserInfoDirectly(String accessToken, String requestId) {
        log.debug("[{}] 直接调用远程接口获取用户信息", requestId);

        // 如果启用熔断器，通过熔断器调用
        if (circuitBreakerEnabled && circuitBreaker != null) {
            try {
                return circuitBreaker.executeSupplier(() -> {
                    // 这里应该调用实际的远程接口
                    // 由于当前实现依赖缓存服务，这里返回null
                    log.warn("[{}] 直接远程调用未实现，需要缓存服务", requestId);
                    return null;
                });
            } catch (Exception e) {
                log.error("[{}] 熔断器执行失败: {}", requestId, e.getMessage());
                return null;
            }
        }

        // 无熔断器保护的调用
        log.warn("[{}] 无熔断器保护的远程调用", requestId);
        return null;
    }

    /**
     * 创建 Spring Security 认证对象
     */
    private void createAuthentication(CachedUserInfo userInfo, HttpServletRequest request) {
        // 构建权限列表
        List<GrantedAuthority> authorities = new ArrayList<>();
        String role = userInfo.getRole();

        if (role != null && !role.isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        } else {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + Const.ROLE_DEFAULT));
        }

        // 确定主体名称
        String principalName = userInfo.getUsername();
        if (principalName == null || principalName.isEmpty()) {
            principalName = userInfo.getEmail();
        }
        if (principalName == null || principalName.isEmpty()) {
            principalName = "user_" + userInfo.getUserId();
        }

        // 创建认证令牌
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principalName, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 设置请求属性和 MDC
        if (userInfo.getUserId() != null) {
            request.setAttribute(Const.ATTR_USER_ID, userInfo.getUserId());
            MDC.put(Const.ATTR_USER_ID, String.valueOf(userInfo.getUserId()));
        }

        if (role != null && !role.isEmpty()) {
            request.setAttribute(Const.ATTR_USER_ROLE, role);
            MDC.put(Const.ATTR_USER_ROLE, role);
        } else {
            request.setAttribute(Const.ATTR_USER_ROLE, Const.ROLE_DEFAULT);
            MDC.put(Const.ATTR_USER_ROLE, Const.ROLE_DEFAULT);
        }
    }

    /**
     * 输出统计信息
     */
    private void logStatistics() {
        long total = totalRequests.get();
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long success = authSuccessCount.get();
        long failure = authFailureCount.get();
        long cbOpen = circuitBreakerOpenCount.get();

        double hitRate = (hits + misses) > 0 ? (double) hits / (hits + misses) * 100 : 0;
        double successRate = total > 0 ? (double) success / total * 100 : 0;

        log.info("OAuth2 过滤器统计 - 总请求: {}, 缓存命中率: {:.2f}%, 认证成功率: {:.2f}%, 熔断器开启次数: {}",
                total, hitRate, successRate, cbOpen);
    }

    /**
     * 获取监控指标（可通过管理端点暴露）
     */
    public OAuth2FilterMetrics getMetrics() {
        OAuth2FilterMetrics metrics = new OAuth2FilterMetrics();
        metrics.setTotalRequests(totalRequests.get());
        metrics.setCacheHits(cacheHits.get());
        metrics.setCacheMisses(cacheMisses.get());
        metrics.setAuthSuccessCount(authSuccessCount.get());
        metrics.setAuthFailureCount(authFailureCount.get());
        metrics.setCircuitBreakerOpenCount(circuitBreakerOpenCount.get());

        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        metrics.setCacheHitRate((hits + misses) > 0 ? (double) hits / (hits + misses) : 0);

        long total = totalRequests.get();
        metrics.setAuthSuccessRate(total > 0 ? (double) authSuccessCount.get() / total : 0);

        if (circuitBreaker != null) {
            metrics.setCircuitBreakerState(circuitBreaker.getState().name());
        }

        return metrics;
    }

    /**
     * 监控指标数据类
     */
    @Setter
    @Getter
    public static class OAuth2FilterMetrics {
        // Getters and setters
        private long totalRequests;
        private long cacheHits;
        private long cacheMisses;
        private long authSuccessCount;
        private long authFailureCount;
        private long circuitBreakerOpenCount;
        private double cacheHitRate;
        private double authSuccessRate;
        private String circuitBreakerState;

    }
}