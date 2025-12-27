package cn.flying.aspect;

import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.UserRole;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.SecurityUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 分级限流切面
 * 根据用户角色应用不同的限流策略
 */
@Slf4j
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String RATE_LIMIT_KEY_PREFIX = "rate:limit:";

    // Lua 脚本实现滑动窗口限流
    private static final String RATE_LIMIT_LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local period = tonumber(ARGV[2])
            local current = tonumber(redis.call('GET', key) or '0')
            if current + 1 > limit then
                return 0
            else
                redis.call('INCR', key)
                if current == 0 then
                    redis.call('EXPIRE', key, period)
                end
                return 1
            end
            """;

    private final DefaultRedisScript<Long> rateLimitScript;

    public RateLimitAspect() {
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setScriptText(RATE_LIMIT_LUA_SCRIPT);
        this.rateLimitScript.setResultType(Long.class);
    }

    @Around("@annotation(rateLimit) || @within(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 如果方法级别有注解，优先使用方法级别的配置
        if (rateLimit == null) {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            rateLimit = method.getAnnotation(RateLimit.class);
            if (rateLimit == null) {
                rateLimit = method.getDeclaringClass().getAnnotation(RateLimit.class);
            }
        }

        if (rateLimit == null) {
            return joinPoint.proceed();
        }

        // 获取当前用户角色
        UserRole role = SecurityUtils.getLoginUserRole();
        int limit = calculateLimit(rateLimit, role);
        int period = rateLimit.period();

        // 构建限流 key
        String key = buildRateLimitKey(joinPoint, rateLimit);

        // 执行限流检查
        Long result = stringRedisTemplate.execute(
                rateLimitScript,
                Collections.singletonList(key),
                String.valueOf(limit),
                String.valueOf(period)
        );

        if (result == 0) {
            log.warn("Rate limit exceeded: key={}, limit={}, period={}s", key, limit, period);
            throw new GeneralException(ResultEnum.PERMISSION_LIMIT);
        }

        return joinPoint.proceed();
    }

    /**
     * 根据角色计算限流阈值
     */
    private int calculateLimit(RateLimit rateLimit, UserRole role) {
        int baseLimit = rateLimit.limit();

        if (role == null) {
            return baseLimit;
        }

        return switch (role) {
            case ROLE_ADMINISTER -> {
                int adminLimit = rateLimit.adminLimit();
                yield adminLimit > 0 ? adminLimit : baseLimit * 5;
            }
            case ROLE_MONITOR -> {
                int monitorLimit = rateLimit.monitorLimit();
                if (monitorLimit > 0) {
                    yield monitorLimit;
                }
                int adminLimit = rateLimit.adminLimit();
                yield adminLimit > 0 ? adminLimit : baseLimit * 5;
            }
            default -> baseLimit;
        };
    }

    /**
     * 构建限流 key
     */
    private String buildRateLimitKey(ProceedingJoinPoint joinPoint, RateLimit rateLimit) {
        StringBuilder keyBuilder = new StringBuilder(RATE_LIMIT_KEY_PREFIX);

        // 添加自定义前缀
        if (!rateLimit.key().isEmpty()) {
            keyBuilder.append(rateLimit.key()).append(":");
        } else {
            // 使用类名+方法名
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            keyBuilder.append(signature.getDeclaringType().getSimpleName())
                    .append(":")
                    .append(signature.getMethod().getName())
                    .append(":");
        }

        // 添加租户隔离
        Long tenantId = SecurityUtils.getTenantId();
        keyBuilder.append("t").append(tenantId).append(":");

        // 根据限流维度添加标识
        switch (rateLimit.type()) {
            case USER -> {
                Long userId = SecurityUtils.getUserId();
                if (userId != null) {
                    keyBuilder.append("u").append(userId);
                } else {
                    // 未登录用户使用 IP
                    keyBuilder.append("ip").append(getClientIp());
                }
            }
            case IP -> keyBuilder.append("ip").append(getClientIp());
            case API -> keyBuilder.append("api");
        }

        return keyBuilder.toString();
    }

    /**
     * 获取客户端 IP
     */
    private String getClientIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();

        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 取第一个IP（可能有多个代理）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
