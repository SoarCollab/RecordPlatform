package cn.flying.common.util;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StringUtils;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 用于处理Jwt令牌的工具类
 */
@Slf4j
@Component
public class JwtUtils {

    //用于给Jwt令牌签名校验的秘钥
    @Value("${spring.security.jwt.key}")
    private String KEY;
    //令牌的过期时间，以小时为单位
    @Value("${spring.security.jwt.expire}")
    private int EXPIRE;
    //为用户生成Jwt令牌的冷却时间，防止刷接口频繁登录生成令牌，以秒为单位
    @Value("${spring.security.jwt.limit.base}")
    private int LIMIT_BASE;
    //用户如果继续恶意刷令牌，更严厉的封禁时间
    @Value("${spring.security.jwt.limit.upgrade}")
    private int LIMIT_UPGRADE;
    //判定用户在冷却时间内，继续恶意刷令牌的次数
    @Value("${spring.security.jwt.limit.frequency}")
    private int LIMIT_FREQUENCY;

    @Resource
    StringRedisTemplate template;

    @Resource
    FlowUtils utils;

    private static final int MIN_KEY_LENGTH = 32;
    private static final double MIN_ENTROPY_BITS = 128.0;
    private static final String ISSUER = "record-platform";
    private static final String AUDIENCE = "record-platform-api";

    private Algorithm algorithm;
    private JWTVerifier verifier;

    @PostConstruct
    void init() {
        validateKey();
        // 初始化算法和验证器（使用 HMAC512 更安全）
        this.algorithm = Algorithm.HMAC512(KEY);
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .build();
        log.info("JWT security initialized: issuer={}, audience={}", ISSUER, AUDIENCE);
    }

    /**
     * 验证 JWT 密钥强度
     */
    private void validateKey() {
        if (!StringUtils.hasText(KEY)) {
            throw new IllegalStateException(
                    "JWT key must be provided via JWT_KEY environment variable");
        }

        if (KEY.length() < MIN_KEY_LENGTH) {
            throw new IllegalStateException(
                    "JWT key must be at least " + MIN_KEY_LENGTH + " characters long, got " + KEY.length());
        }

        // 检查密钥熵值
        double entropy = calculateEntropy(KEY);
        if (entropy < MIN_ENTROPY_BITS) {
            log.warn("JWT key entropy ({} bits) is below recommended minimum ({} bits). " +
                    "Consider using a more random key.", String.format("%.2f", entropy), MIN_ENTROPY_BITS);
        }

        // 检查是否为常见弱密钥
        if (isWeakKey(KEY)) {
            throw new IllegalStateException(
                    "JWT key appears to be a weak/default key. Please use a strong random key.");
        }

        log.info("JWT key validation passed: length={}, entropy={} bits",
                KEY.length(), String.format("%.2f", entropy));
    }

    /**
     * 计算字符串的熵值（比特）
     * 使用 HashMap 以支持完整 Unicode 字符集，避免数组越界
     */
    private double calculateEntropy(String str) {
        java.util.HashMap<Character, Integer> freq = new java.util.HashMap<>();
        for (char c : str.toCharArray()) {
            freq.merge(c, 1, Integer::sum);
        }

        double entropy = 0.0;
        int len = str.length();
        for (int f : freq.values()) {
            if (f > 0) {
                double p = (double) f / len;
                entropy -= p * (Math.log(p) / Math.log(2));
            }
        }
        return entropy * len;
    }

    /**
     * 检查是否为弱密钥
     */
    private boolean isWeakKey(String key) {
        String lower = key.toLowerCase();
        String[] weakPatterns = {
                "secret", "password", "123456", "qwerty",
                "default", "changeme", "admin", "test"
        };
        for (String pattern : weakPatterns) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        // 检查是否全是相同字符
        if (key.chars().distinct().count() < 10) {
            return true;
        }
        return false;
    }

    /**
     * 让指定Jwt令牌失效
     * @param headerToken 请求头中携带的令牌
     * @return 是否操作成功
     */
    public boolean invalidateJwt(String headerToken){
        String token = this.convertToken(headerToken);
        try {
            DecodedJWT verify = verifier.verify(token);
            return deleteToken(verify.getId(), verify.getExpiresAt());
        } catch (JWTVerificationException e) {
            return false;
        }
    }

    /**
     * 根据配置快速计算过期时间
     * @return 过期时间
     */
    public Date expireTime() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.HOUR, EXPIRE);
        return calendar.getTime();
    }

    /**
     * 根据UserDetails生成对应的Jwt令牌
     * @param user 用户信息
     * @return 令牌
     */
    public String createJwt(UserDetails user, String username, Long userId, Long tenantId) {
        if(this.frequencyCheck(userId)) {
            Date expire = this.expireTime();
            return JWT.create()
                    .withJWTId(UUID.randomUUID().toString())
                    .withIssuer(ISSUER)
                    .withAudience(AUDIENCE)
                    .withClaim("id", userId)
                    .withClaim("tenantId", tenantId)
                    .withClaim("name", username)
                    .withClaim("authorities", user.getAuthorities()
                            .stream()
                            .map(GrantedAuthority::getAuthority).toList())
                    .withExpiresAt(expire)
                    .withIssuedAt(new Date())
                    .sign(algorithm);
        } else {
            return null;
        }
    }

    /**
     * 解析Jwt令牌
     * @param headerToken 请求头中携带的令牌
     * @return DecodedJWT
     */
    public DecodedJWT resolveJwt(String headerToken){
        String token = this.convertToken(headerToken);
        if(token == null) {
            return null;
        }
        try {
            DecodedJWT verify = verifier.verify(token);
            if(this.isInvalidToken(verify.getId())) {
                return null;
            }
            Map<String, Claim> claims = verify.getClaims();
            return new Date().after(claims.get("exp").asDate()) ? null : verify;
        } catch (JWTVerificationException e) {
            log.debug("JWT verification failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 将jwt对象中的内容封装为UserDetails
     * @param jwt 已解析的Jwt对象
     * @return UserDetails
     */
    public UserDetails toUser(DecodedJWT jwt) {
        Map<String, Claim> claims = jwt.getClaims();
        return User
                .withUsername(claims.get("name").asString())
                .password("******")
                .authorities(claims.get("authorities").asArray(String.class))
                .build();
    }

    /**
     * 将jwt对象中的用户ID提取出来
     * @param jwt 已解析的Jwt对象
     * @return 用户ID
     */
    public Long toId(DecodedJWT jwt) {
        Map<String, Claim> claims = jwt.getClaims();
        return claims.get("id").asLong();
    }

    /**
     * 将jwt对象中的用户role提取出来
     * @param jwt 已解析的Jwt对象
     * @return 用户角色（如 "admin", "monitor", "user"）
     */
    public String toRole(DecodedJWT jwt) {
        Map<String, Claim> claims = jwt.getClaims();
        Claim authoritiesClaim = claims.get("authorities");
        if (authoritiesClaim == null || authoritiesClaim.isNull()) {
            return null;
        }
        java.util.List<String> authorities = authoritiesClaim.asList(String.class);
        if (authorities == null || authorities.isEmpty()) {
            return null;
        }
        // 提取第一个角色，并去除 "ROLE_" 前缀以匹配 UserRole 枚举
        String role = authorities.get(0);
        if (role != null && role.startsWith("ROLE_")) {
            return role.substring(5); // 去除 "ROLE_" 前缀
        }
        return role;
    }

    /**
     * 将jwt对象中的租户ID提取出来
     * @param jwt 已解析的Jwt对象
     * @return 租户ID
     */
    public Long toTenantId(DecodedJWT jwt) {
        Map<String, Claim> claims = jwt.getClaims();
        Claim tenantClaim = claims.get("tenantId");
        return tenantClaim == null ? null : tenantClaim.asLong();
    }

    /**
     * 刷新Jwt令牌
     * 基于现有的有效令牌生成新令牌，同时使旧令牌失效
     * @param headerToken 请求头中携带的令牌
     * @return 新的令牌，如果刷新失败返回null
     */
    public String refreshJwt(String headerToken) {
        DecodedJWT jwt = resolveJwt(headerToken);
        if (jwt == null) {
            return null;
        }

        Map<String, Claim> claims = jwt.getClaims();
        Long userId = claims.get("id").asLong();
        Long tenantId = claims.get("tenantId") != null ? claims.get("tenantId").asLong() : null;
        String username = claims.get("name").asString();
        String[] authorities = claims.get("authorities").asArray(String.class);

        // 频率检测
        if (!this.frequencyCheck(userId)) {
            log.warn("Token refresh rate limit exceeded for user: {}", userId);
            return null;
        }

        // 使旧令牌失效
        deleteToken(jwt.getId(), jwt.getExpiresAt());

        // 生成新令牌
        Date expire = this.expireTime();
        String newToken = JWT.create()
                .withJWTId(UUID.randomUUID().toString())
                .withIssuer(ISSUER)
                .withAudience(AUDIENCE)
                .withClaim("id", userId)
                .withClaim("tenantId", tenantId)
                .withClaim("name", username)
                .withClaim("authorities", java.util.Arrays.asList(authorities))
                .withExpiresAt(expire)
                .withIssuedAt(new Date())
                .sign(algorithm);

        log.info("Token refreshed for user: {}", userId);
        return newToken;
    }

    /**
     * 频率检测，防止用户高频申请Jwt令牌，并且采用阶段封禁机制
     * 如果已经提示无法登录的情况下用户还在刷，那么就封禁更长时间
     * @param userId 用户ID
     * @return 是否通过频率检测
     */
    private boolean frequencyCheck(Long userId){
        // 使用租户隔离的 Key
        String key = TenantKeyUtils.tenantKey(Const.JWT_FREQUENCY + userId);
        return utils.limitOnceUpgradeCheck(key, LIMIT_FREQUENCY, LIMIT_BASE, LIMIT_UPGRADE);
    }

    /**
     * 校验并转换请求头中的Token令牌
     * @param headerToken 请求头中的Token
     * @return 转换后的令牌
     */
    private String convertToken(String headerToken){
        if(headerToken == null || !headerToken.startsWith("Bearer ")) {
            return null;
        }
        return headerToken.substring(7);
    }

    // 黑名单冗余时间（1小时），防止边界问题
    private static final long BLACKLIST_BUFFER_MS = 3600_000L;

    /**
     * 将Token列入Redis黑名单中
     * 增加1小时冗余时间，确保即使存在时钟偏差或 Redis 重启恢复延迟，
     * 令牌仍然能被正确识别为无效
     * @param uuid 令牌ID
     * @param time 过期时间
     * @return 是否操作成功
     */
    private boolean deleteToken(String uuid, Date time){
        if(this.isInvalidToken(uuid)) {
            return false;
        }
        Date now = new Date();
        // 计算过期时间并添加冗余缓冲
        long expire = Math.max(time.getTime() - now.getTime() + BLACKLIST_BUFFER_MS, 0);
        // 使用租户隔离的 Key
        String key = TenantKeyUtils.tenantKey(Const.JWT_BLACK_LIST + uuid);
        template.opsForValue().set(key, "", expire, TimeUnit.MILLISECONDS);
        return true;
    }

    /**
     * 验证Token是否被列入Redis黑名单
     * @param uuid 令牌ID
     * @return 是否操作成功
     */
    private boolean isInvalidToken(String uuid){
        // 使用租户隔离的 Key
        String key = TenantKeyUtils.tenantKey(Const.JWT_BLACK_LIST + uuid);
        return Boolean.TRUE.equals(template.hasKey(key));
    }

    // ===== SSE 短期令牌相关方法 =====

    /**
     * 生成 SSE 短期令牌
     * 该令牌仅用于建立 SSE 连接，有效期极短（30秒），且为一次性使用
     *
     * @param userId   用户ID
     * @param tenantId 租户ID
     * @param role     用户角色
     * @return SSE 短期令牌
     */
    public String createSseToken(Long userId, Long tenantId, String role) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = TenantKeyUtils.tenantKey(Const.SSE_TOKEN_PREFIX + token);

        // 存储用户信息到 Redis，格式：userId:tenantId:role
        String value = userId + ":" + tenantId + ":" + role;
        template.opsForValue().set(key, value, Const.SSE_TOKEN_TTL, TimeUnit.SECONDS);

        log.debug("SSE token created for user {}: {}", userId, token);
        return token;
    }

    /**
     * 验证并消费 SSE 短期令牌（一次性使用）
     * 验证成功后立即删除令牌，防止重放攻击
     *
     * @param token SSE 令牌
     * @return 用户信息数组 [userId, tenantId, role]，验证失败返回 null
     */
    public String[] validateAndConsumeSseToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        String key = TenantKeyUtils.tenantKey(Const.SSE_TOKEN_PREFIX + token);

        // 原子性地获取并删除（防止并发消费）
        String value = template.opsForValue().getAndDelete(key);
        if (value == null) {
            log.debug("SSE token not found or already consumed: {}", token);
            return null;
        }

        String[] parts = value.split(":");
        if (parts.length != 3) {
            log.warn("Invalid SSE token format: {}", token);
            return null;
        }

        log.debug("SSE token consumed for user {}: {}", parts[0], token);
        return parts;
    }
}
