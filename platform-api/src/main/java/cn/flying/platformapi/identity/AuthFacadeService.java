package cn.flying.platformapi.identity;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.identity.dto.TokenIntrospection;
import cn.flying.platformapi.identity.dto.UserPrincipal;
import org.apache.dubbo.config.annotation.DubboService;

/**
 * 认证与授权对内 Dubbo 门面接口
 * 仅用于微服务内部调用，提供令牌自省、主体校验、权限与角色判定、令牌撤销等能力。
 */
@DubboService
public interface AuthFacadeService {

    /**
     * 令牌自省
     */
    Result<TokenIntrospection> introspectToken(String token);

    /**
     * 校验令牌并返回主体信息
     */
    Result<UserPrincipal> validateToken(String token);

    /**
     * 基于令牌校验权限
     */
    Result<Boolean> checkPermissionByToken(String token, String permission);

    /**
     * 基于用户ID校验权限
     */
    Result<Boolean> checkPermissionByUser(Long userId, String permission);

    /**
     * 基于令牌校验角色
     */
    Result<Boolean> checkRoleByToken(String token, String role);

    /**
     * 基于用户ID校验角色
     */
    Result<Boolean> checkRoleByUser(Long userId, String role);

    /**
     * 撤销令牌（加入黑名单并删除存储）
     */
    Result<Void> revokeToken(String token);

    /**
     * 判断令牌是否在黑名单
     */
    Result<Boolean> isBlacklisted(String token);
}
