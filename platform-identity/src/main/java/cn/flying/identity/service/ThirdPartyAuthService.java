package cn.flying.identity.service;

import cn.flying.platformapi.constant.Result;

import java.util.Map;

/**
 * 第三方认证服务接口
 * 支持多种第三方登录方式
 * 
 * @author 王贝强
 */
public interface ThirdPartyAuthService {
    
    /**
     * 获取第三方登录授权URL
     * 
     * @param provider 第三方提供商（github、google、wechat等）
     * @param redirectUri 回调地址
     * @param state 状态参数
     * @return 授权URL
     */
    Result<String> getAuthorizationUrl(String provider, String redirectUri, String state);
    
    /**
     * 处理第三方登录回调
     * 
     * @param provider 第三方提供商
     * @param code 授权码
     * @param state 状态参数
     * @return 登录结果
     */
    Result<Map<String, Object>> handleCallback(String provider, String code, String state);
    
    /**
     * 绑定第三方账号
     * 
     * @param userId 用户ID
     * @param provider 第三方提供商
     * @param code 授权码
     * @return 绑定结果
     */
    Result<Void> bindThirdPartyAccount(Long userId, String provider, String code);
    
    /**
     * 解绑第三方账号
     * 
     * @param userId 用户ID
     * @param provider 第三方提供商
     * @return 解绑结果
     */
    Result<Void> unbindThirdPartyAccount(Long userId, String provider);
    
    /**
     * 获取用户绑定的第三方账号列表
     * 
     * @param userId 用户ID
     * @return 第三方账号列表
     */
    Result<Map<String, Object>> getUserThirdPartyAccounts(Long userId);
    
    /**
     * 获取支持的第三方登录提供商列表
     * 
     * @return 提供商列表
     */
    Result<Map<String, Object>> getSupportedProviders();
    
    /**
     * 刷新第三方访问令牌
     * 
     * @param userId 用户ID
     * @param provider 第三方提供商
     * @return 刷新结果
     */
    Result<Map<String, Object>> refreshThirdPartyToken(Long userId, String provider);
    
    /**
     * 获取第三方用户信息
     * 
     * @param provider 第三方提供商
     * @param accessToken 访问令牌
     * @return 用户信息
     */
    Result<Map<String, Object>> getThirdPartyUserInfo(String provider, String accessToken);
    
    /**
     * 验证第三方访问令牌
     * 
     * @param provider 第三方提供商
     * @param accessToken 访问令牌
     * @return 验证结果
     */
    Result<Boolean> validateThirdPartyToken(String provider, String accessToken);
}
