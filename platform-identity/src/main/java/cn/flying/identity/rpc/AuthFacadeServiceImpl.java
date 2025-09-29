package cn.flying.identity.rpc;

import cn.dev33.satoken.stp.StpInterface;
import cn.flying.identity.config.OAuthConfig;
import cn.flying.identity.mapper.AccountMapper;
import cn.flying.identity.service.JwtBlacklistService;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.identity.AuthFacadeService;
import cn.flying.platformapi.identity.dto.TokenIntrospection;
import cn.flying.platformapi.identity.dto.UserPrincipal;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.DubboService;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 认证与授权 Dubbo 门面服务实现（增强版）
 * 提供令牌自省、主体校验、权限与角色判定、令牌撤销等能力
 * <p>
 * 增强特性：
 * 1. 详细的错误处理和分类
 * 2. 性能监控和指标收集
 * 3. 参数验证
 * 4. 详细的日志记录
 */
@Slf4j
@DubboService(protocol = "tri", timeout = 3000, retries = 2)
public class AuthFacadeServiceImpl implements AuthFacadeService {

    // 监控指标
    private final AtomicLong introspectCount = new AtomicLong(0);
    private final AtomicLong validateCount = new AtomicLong(0);
    private final AtomicLong revokeCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong redisErrorCount = new AtomicLong(0);

    @Resource(name = "stringRedisTemplate")
    private StringRedisTemplate redisTemplate;

    @Resource
    private OAuthConfig oauthConfig;

    @Resource
    private JwtBlacklistService jwtBlacklistService;

    @Resource
    private StpInterface stpInterface;

    @Resource
    private AccountMapper accountMapper;

    /**
     * 令牌自省 - 获取令牌详细信息
     */
    @Override
    public Result<TokenIntrospection> introspectToken(String token) {
        introspectCount.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            // 参数验证
            if (token == null || token.trim().isEmpty()) {
                log.warn("令牌自省失败：令牌为空");
                return Result.errorWithMessage(ResultEnum.PARAMETER_ERROR, "令牌不能为空");
            }

            String key = oauthConfig.getAccessTokenPrefix() + token;
            Map<Object, Object> tokenData = null;

            try {
                tokenData = redisTemplate.opsForHash().entries(key);
            } catch (RedisConnectionFailureException e) {
                redisErrorCount.incrementAndGet();
                log.error("Redis 连接失败: {}", e.getMessage());
                return Result.errorWithMessage(ResultEnum.SERVICE_UNAVAILABLE, "缓存服务不可用");
            } catch (DataAccessException e) {
                redisErrorCount.incrementAndGet();
                log.error("Redis 访问异常: {}", e.getMessage());
                return Result.errorWithMessage(ResultEnum.SERVICE_ERROR, "缓存访问失败");
            }

            TokenIntrospection ti = new TokenIntrospection();

            // 令牌不存在
            if (tokenData.isEmpty()) {
                ti.setActive(false);
                log.debug("令牌不存在或已过期: {}", maskToken(token));
                return Result.success(ti);
            }

            // 解析令牌数据
            long now = System.currentTimeMillis();
            long exp = parseLong((String) tokenData.get("expires_at"), 0L);

            // 检查是否过期
            boolean expired = exp > 0 && exp <= now;

            // 检查是否在黑名单
            boolean blacklisted = false;
            try {
                blacklisted = jwtBlacklistService.isBlacklisted(token);
            } catch (Exception e) {
                log.warn("检查黑名单失败，默认为不在黑名单: {}", e.getMessage());
            }

            boolean active = !expired && !blacklisted;

            // 填充令牌信息
            ti.setActive(active);
            ti.setSubject(parseLong((String) tokenData.get("user_id"), null));
            ti.setClientId((String) tokenData.get("client_id"));
            ti.setScope((String) tokenData.get("scope"));
            ti.setIssuedAt(new Date(parseLong((String) tokenData.get("issued_at"), now)));
            ti.setExpireAt(new Date(exp == 0L ? now : exp));
            ti.setIssuer("platform-identity");

            // 获取角色和权限信息
            Long userId = ti.getSubject();
            if (userId != null && active) {
                try {
                    List<String> roles = stpInterface.getRoleList(userId, null);
                    List<String> perms = stpInterface.getPermissionList(userId, null);
                    ti.setRoles(roles != null ? roles : Collections.emptyList());
                    ti.setPermissions(perms != null ? perms : Collections.emptyList());
                } catch (Exception e) {
                    log.warn("获取用户角色权限失败: userId={}, error={}", userId, e.getMessage());
                    ti.setRoles(Collections.emptyList());
                    ti.setPermissions(Collections.emptyList());
                }
            } else {
                ti.setRoles(Collections.emptyList());
                ti.setPermissions(Collections.emptyList());
            }

            long elapsedTime = System.currentTimeMillis() - startTime;
            if (elapsedTime > 1000) {
                log.warn("令牌自省耗时过长: {}ms", elapsedTime);
            }

            log.debug("令牌自省成功: active={}, userId={}, elapsed={}ms",
                    active, userId, elapsedTime);
            return Result.success(ti);

        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("令牌自省发生异常: token={}", maskToken(token), e);
            return Result.errorWithMessage(ResultEnum.SYSTEM_ERROR, "令牌自省失败");
        } finally {
            // 定期输出统计
            if (introspectCount.get() % 1000 == 0) {
                logStatistics();
            }
        }
    }

    /**
     * 校验令牌并返回主体信息
     */
    @Override
    public Result<UserPrincipal> validateToken(String token) {
        validateCount.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            // 参数验证
            if (token == null || token.trim().isEmpty()) {
                log.debug("令牌验证失败：令牌为空");
                return Result.errorWithMessage(ResultEnum.PARAMETER_ERROR, "令牌不能为空");
            }

            String key = oauthConfig.getAccessTokenPrefix() + token;
            Map<Object, Object> tokenData = null;

            try {
                tokenData = redisTemplate.opsForHash().entries(key);
            } catch (RedisConnectionFailureException e) {
                redisErrorCount.incrementAndGet();
                log.error("Redis 连接失败: {}", e.getMessage());
                return Result.errorWithMessage(ResultEnum.SERVICE_UNAVAILABLE, "认证服务暂时不可用");
            } catch (DataAccessException e) {
                redisErrorCount.incrementAndGet();
                log.error("Redis 访问异常: {}", e.getMessage());
                return Result.errorWithMessage(ResultEnum.SERVICE_ERROR, "认证服务访问失败");
            }

            // 令牌不存在
            if (tokenData.isEmpty()) {
                log.debug("令牌不存在: {}", maskToken(token));
                return Result.errorWithMessage(ResultEnum.PERMISSION_TOKEN_INVALID, "令牌无效或不存在");
            }

            // 检查过期
            long now = System.currentTimeMillis();
            long exp = parseLong((String) tokenData.get("expires_at"), 0L);
            if (exp > 0 && exp <= now) {
                log.debug("令牌已过期: token={}, expireAt={}", maskToken(token), new Date(exp));
                return Result.errorWithMessage(ResultEnum.PERMISSION_TOKEN_EXPIRED,
                        String.format("令牌已于 %s 过期", new Date(exp)));
            }

            // 检查黑名单
            try {
                if (jwtBlacklistService.isBlacklisted(token)) {
                    log.warn("令牌在黑名单中: {}", maskToken(token));
                    return Result.errorWithMessage(ResultEnum.PERMISSION_TOKEN_INVALID, "令牌已被撤销");
                }
            } catch (Exception e) {
                log.warn("检查黑名单失败，继续验证: {}", e.getMessage());
            }

            // 构建用户主体信息
            UserPrincipal up = new UserPrincipal();
            Long userId = parseLong((String) tokenData.get("user_id"), null);

            if (userId == null) {
                log.warn("令牌缺少用户ID: {}", maskToken(token));
                return Result.errorWithMessage(ResultEnum.PERMISSION_TOKEN_INVALID, "令牌信息不完整");
            }

            up.setUserId(userId);
            up.setUsername((String) tokenData.get("username"));
            up.setEmail((String) tokenData.get("email"));
            up.setTokenId(token);
            up.setIssuedAt(new Date(parseLong((String) tokenData.get("issued_at"), now)));
            up.setExpireAt(new Date(exp));
            up.setClientId((String) tokenData.get("client_id"));
            up.setScope((String) tokenData.get("scope"));

            // 获取角色和权限
            try {
                List<String> roles = stpInterface.getRoleList(userId, null);
                List<String> perms = stpInterface.getPermissionList(userId, null);
                up.setRoles(roles != null ? roles : Collections.emptyList());
                up.setPermissions(perms != null ? perms : Collections.emptyList());
            } catch (Exception e) {
                log.warn("获取用户角色权限失败，使用空列表: userId={}, error={}", userId, e.getMessage());
                up.setRoles(Collections.emptyList());
                up.setPermissions(Collections.emptyList());
            }

            long elapsedTime = System.currentTimeMillis() - startTime;
            log.debug("令牌验证成功: userId={}, username={}, elapsed={}ms",
                    userId, up.getUsername(), elapsedTime);

            return Result.success(up);

        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("令牌验证发生异常: token={}", maskToken(token), e);
            return Result.errorWithMessage(ResultEnum.SYSTEM_ERROR, "令牌验证失败");
        }
    }

    /**
     * 基于令牌校验权限
     */
    @Override
    public Result<Boolean> checkPermissionByToken(String token, String permission) {
        try {
            // 参数验证
            if (token == null || token.trim().isEmpty() || permission == null || permission.trim().isEmpty()) {
                log.debug("权限检查参数无效: token={}, permission={}", token != null, permission);
                return Result.success(false);
            }

            // 验证令牌
            Result<UserPrincipal> validResult = validateToken(token);
            if (!validResult.isSuccess() || validResult.getData() == null) {
                log.debug("权限检查失败：令牌无效");
                return Result.success(false);
            }

            UserPrincipal principal = validResult.getData();
            if (principal.getUserId() == null) {
                log.debug("权限检查失败：用户ID为空");
                return Result.success(false);
            }

            // 检查权限
            List<String> perms = principal.getPermissions();
            boolean hasPermission = perms != null && perms.contains(permission);

            log.debug("权限检查结果: userId={}, permission={}, result={}",
                    principal.getUserId(), permission, hasPermission);

            return Result.success(hasPermission);

        } catch (Exception e) {
            log.error("权限检查异常: permission={}", permission, e);
            return Result.success(false);
        }
    }

    /**
     * 基于用户ID校验权限
     */
    @Override
    public Result<Boolean> checkPermissionByUser(Long userId, String permission) {
        try {
            // 参数验证
            if (userId == null || permission == null || permission.trim().isEmpty()) {
                log.debug("用户权限检查参数无效: userId={}, permission={}", userId, permission);
                return Result.success(false);
            }

            List<String> perms = stpInterface.getPermissionList(userId, null);
            boolean hasPermission = perms != null && perms.contains(permission);

            log.debug("用户权限检查结果: userId={}, permission={}, result={}",
                    userId, permission, hasPermission);

            return Result.success(hasPermission);

        } catch (Exception e) {
            log.error("用户权限检查异常: userId={}, permission={}", userId, permission, e);
            return Result.success(false);
        }
    }

    /**
     * 基于令牌校验角色
     */
    @Override
    public Result<Boolean> checkRoleByToken(String token, String role) {
        try {
            // 参数验证
            if (token == null || token.trim().isEmpty() || role == null || role.trim().isEmpty()) {
                log.debug("角色检查参数无效: token={}, role={}", token != null, role);
                return Result.success(false);
            }

            // 验证令牌
            Result<UserPrincipal> validResult = validateToken(token);
            if (!validResult.isSuccess() || validResult.getData() == null) {
                log.debug("角色检查失败：令牌无效");
                return Result.success(false);
            }

            UserPrincipal principal = validResult.getData();
            if (principal.getUserId() == null) {
                log.debug("角色检查失败：用户ID为空");
                return Result.success(false);
            }

            // 检查角色
            List<String> roles = principal.getRoles();
            boolean hasRole = roles != null && roles.contains(role);

            log.debug("角色检查结果: userId={}, role={}, result={}",
                    principal.getUserId(), role, hasRole);

            return Result.success(hasRole);

        } catch (Exception e) {
            log.error("角色检查异常: role={}", role, e);
            return Result.success(false);
        }
    }

    /**
     * 基于用户ID校验角色
     */
    @Override
    public Result<Boolean> checkRoleByUser(Long userId, String role) {
        try {
            // 参数验证
            if (userId == null || role == null || role.trim().isEmpty()) {
                log.debug("用户角色检查参数无效: userId={}, role={}", userId, role);
                return Result.success(false);
            }

            List<String> roles = stpInterface.getRoleList(userId, null);
            boolean hasRole = roles != null && roles.contains(role);

            log.debug("用户角色检查结果: userId={}, role={}, result={}",
                    userId, role, hasRole);

            return Result.success(hasRole);

        } catch (Exception e) {
            log.error("用户角色检查异常: userId={}, role={}", userId, role, e);
            return Result.success(false);
        }
    }

    /**
     * 撤销令牌（加入黑名单并删除存储）
     */
    @Override
    public Result<Void> revokeToken(String token) {
        revokeCount.incrementAndGet();

        try {
            // 参数验证
            if (token == null || token.trim().isEmpty()) {
                log.warn("撤销令牌失败：令牌为空");
                return Result.errorWithMessage(ResultEnum.PARAMETER_ERROR, "令牌不能为空");
            }

            String accessKey = oauthConfig.getAccessTokenPrefix() + token;
            String refreshKey = oauthConfig.getRefreshTokenPrefix() + token;

            Long accessTtl = null;
            Long refreshTtl = null;
            boolean accessDeleted = false;
            boolean refreshDeleted = false;

            try {
                // 获取过期时间
                accessTtl = redisTemplate.getExpire(accessKey, TimeUnit.SECONDS);
                refreshTtl = redisTemplate.getExpire(refreshKey, TimeUnit.SECONDS);

                // 删除令牌
                if (redisTemplate.hasKey(accessKey)) {
                    accessDeleted = redisTemplate.delete(accessKey);
                }
                if (redisTemplate.hasKey(refreshKey)) {
                    refreshDeleted = redisTemplate.delete(refreshKey);
                }
            } catch (RedisConnectionFailureException e) {
                redisErrorCount.incrementAndGet();
                log.error("Redis 连接失败，无法撤销令牌: {}", e.getMessage());
                return Result.errorWithMessage(ResultEnum.SERVICE_UNAVAILABLE, "撤销服务暂时不可用");
            } catch (DataAccessException e) {
                redisErrorCount.incrementAndGet();
                log.error("Redis 访问异常，撤销失败: {}", e.getMessage());
                return Result.errorWithMessage(ResultEnum.SERVICE_ERROR, "撤销操作失败");
            }

            // 计算黑名单TTL
            long ttl;
            if (accessDeleted && accessTtl > 0) {
                ttl = accessTtl;
            } else if (refreshDeleted && refreshTtl > 0) {
                ttl = refreshTtl;
            } else {
                ttl = 7200L; // 默认2小时
            }

            // 加入黑名单
            try {
                jwtBlacklistService.blacklistToken(token, ttl);
                log.info("令牌已撤销并加入黑名单: token={}, ttl={}s", maskToken(token), ttl);
            } catch (Exception e) {
                log.error("加入黑名单失败，但令牌已删除: token={}", maskToken(token), e);
                // 即使黑名单失败，令牌已删除，返回成功
            }

            if (!accessDeleted && !refreshDeleted) {
                log.warn("撤销令牌时未找到令牌数据: {}", maskToken(token));
                // 即使不存在，也返回成功（幂等性）
            }

            return Result.success(null);

        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("撤销令牌发生异常: token={}", maskToken(token), e);
            return Result.errorWithMessage(ResultEnum.SYSTEM_ERROR, "撤销令牌失败");
        }
    }

    /**
     * 判断令牌是否在黑名单
     */
    @Override
    public Result<Boolean> isBlacklisted(String token) {
        try {
            // 参数验证
            if (token == null || token.trim().isEmpty()) {
                log.debug("黑名单检查失败：令牌为空");
                return Result.success(false);
            }

            boolean blacklisted = jwtBlacklistService.isBlacklisted(token);
            log.debug("黑名单检查结果: token={}, blacklisted={}", maskToken(token), blacklisted);
            return Result.success(blacklisted);

        } catch (Exception e) {
            log.error("黑名单检查异常: token={}", maskToken(token), e);
            // 安全起见，检查失败时返回true
            return Result.success(true);
        }
    }

    /**
     * 掩码令牌（用于日志输出）
     */
    private String maskToken(String token) {
        if (token == null || token.length() <= 8) {
            return "***";
        }
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }

    /**
     * 安全地解析 Long 值
     */
    private Long parseLong(String s, Long def) {
        if (s == null || s.trim().isEmpty()) {
            return def;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            log.debug("解析 Long 失败: value={}, default={}", s, def);
            return def;
        }
    }

    /**
     * 输出统计信息
     */
    private void logStatistics() {
        log.info("AuthFacade 统计 - 自省: {}, 验证: {}, 撤销: {}, 错误: {}, Redis错误: {}",
                introspectCount.get(), validateCount.get(), revokeCount.get(),
                errorCount.get(), redisErrorCount.get());
    }

    /**
     * 获取服务指标（可通过管理端点暴露）
     */
    public Map<String, Object> getMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("introspectCount", introspectCount.get());
        metrics.put("validateCount", validateCount.get());
        metrics.put("revokeCount", revokeCount.get());
        metrics.put("errorCount", errorCount.get());
        metrics.put("redisErrorCount", redisErrorCount.get());

        long total = introspectCount.get() + validateCount.get();
        double errorRate = total > 0 ? (double) errorCount.get() / total * 100 : 0;
        metrics.put("errorRate", String.format("%.2f%%", errorRate));

        return metrics;
    }
}