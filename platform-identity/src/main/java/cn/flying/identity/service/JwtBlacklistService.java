package cn.flying.identity.service;

/**
 * JWT 黑名单服务
 * 提供将 Token 加入黑名单与黑名单校验的功能，配合无状态 JWT 模式实现登出后 Token 失效。
 */
public interface JwtBlacklistService {

    /**
     * 将指定的 JWT Token 加入黑名单
     *
     * @param token       需要加入黑名单的 Token
     * @param ttlSeconds  黑名单记录的过期时间（秒）
     */
    void blacklistToken(String token, long ttlSeconds);

    /**
     * 判断指定的 JWT Token 是否在黑名单中
     *
     * @param token 待检查的 Token
     * @return true 表示在黑名单中；false 表示不在黑名单
     */
    boolean isBlacklisted(String token);
}
