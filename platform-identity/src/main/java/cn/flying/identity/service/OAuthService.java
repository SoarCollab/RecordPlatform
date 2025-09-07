package cn.flying.identity.service;

import cn.flying.identity.dto.OAuthClient;
import cn.flying.identity.dto.OAuthCode;
import cn.flying.platformapi.constant.Result;

import java.util.Map;

/**
 * OAuth2.0服务接口
 * 定义SSO单点登录和第三方应用接入的核心功能
 */
public interface OAuthService {

    /**
     * 获取授权页面信息
     * @param clientKey 客户端标识符
     * @param redirectUri 重定向URI
     * @param scope 授权范围
     * @param state 状态参数
     * @return 授权页面信息
     */
    Result<Map<String, Object>> getAuthorizeInfo(String clientKey, String redirectUri, String scope, String state);

    /**
     * 用户授权确认
     * @param clientKey 客户端标识符
     * @param redirectUri 重定向URI
     * @param scope 授权范围
     * @param state 状态参数
     * @param approved 是否同意授权
     * @return 授权结果（包含授权码或错误信息）
     */
    Result<String> authorize(String clientKey, String redirectUri, String scope, String state, boolean approved);

    /**
     * 通过授权码获取访问令牌
     * @param grantType 授权类型
     * @param code 授权码
     * @param redirectUri 重定向URI
     * @param clientKey 客户端标识符
     * @param clientSecret 客户端密钥
     * @return 访问令牌信息
     */
    Result<Map<String, Object>> getAccessToken(String grantType, String code, String redirectUri, 
                                               String clientKey, String clientSecret);

    /**
     * 刷新访问令牌
     * @param grantType 授权类型
     * @param refreshToken 刷新令牌
     * @param clientKey 客户端标识符
     * @param clientSecret 客户端密钥
     * @return 新的访问令牌信息
     */
    Result<Map<String, Object>> refreshAccessToken(String grantType, String refreshToken, 
                                                   String clientKey, String clientSecret);

    /**
     * 客户端凭证模式获取访问令牌
     * @param grantType 授权类型
     * @param scope 授权范围
     * @param clientKey 客户端标识符
     * @param clientSecret 客户端密钥
     * @return 访问令牌信息
     */
    Result<Map<String, Object>> getClientCredentialsToken(String grantType, String scope, 
                                                          String clientKey, String clientSecret);

    /**
     * 获取用户信息（通过访问令牌）
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    Result<Map<String, Object>> getUserInfo(String accessToken);

    /**
     * 撤销令牌
     * @param token 令牌
     * @param tokenTypeHint 令牌类型提示
     * @param clientKey 客户端标识符
     * @param clientSecret 客户端密钥
     * @return 撤销结果
     */
    Result<Void> revokeToken(String token, String tokenTypeHint, String clientKey, String clientSecret);

    /**
     * 验证客户端
     * @param clientKey 客户端标识符
     * @param clientSecret 客户端密钥
     * @return 客户端信息
     */
    OAuthClient validateClient(String clientKey, String clientSecret);

    /**
     * 生成授权码
     * @param clientKey 客户端标识符
     * @param userId 用户ID
     * @param redirectUri 重定向URI
     * @param scope 授权范围
     * @param state 状态参数
     * @return 授权码记录
     */
    OAuthCode generateAuthorizationCode(String clientKey, Long userId, String redirectUri, String scope, String state);

    /**
     * 验证授权码
     * @param code 授权码
     * @param clientKey 客户端标识符
     * @param redirectUri 重定向URI
     * @return 授权码记录
     */
    OAuthCode validateAuthorizationCode(String code, String clientKey, String redirectUri);

    /**
     * 清理过期的授权码
     * @return 清理的记录数
     */
    int cleanExpiredCodes();

    /**
     * 注册OAuth客户端
     * @param client 客户端信息
     * @return 注册结果
     */
    Result<OAuthClient> registerClient(OAuthClient client);

    /**
     * 更新OAuth客户端
     * @param client 客户端信息
     * @return 更新结果
     */
    Result<OAuthClient> updateClient(OAuthClient client);

    /**
     * 删除OAuth客户端
     * @param clientKey 客户端标识符
     * @return 删除结果
     */
    Result<Void> deleteClient(String clientKey);

    /**
     * 获取客户端信息
     * @param clientKey 客户端标识符
     * @return 客户端信息
     */
    Result<OAuthClient> getClient(String clientKey);
}