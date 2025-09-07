package cn.flying.identity.service;

import cn.flying.platformapi.constant.Result;

import java.util.Map;

/**
 * SSO 单点登录服务接口
 * 提供完整的单点登录功能
 * 
 * @author 王贝强
 */
public interface SSOService {
    
    /**
     * 获取 SSO 登录页面信息
     * 
     * @param clientId 客户端ID
     * @param redirectUri 重定向URI
     * @param scope 授权范围
     * @param state 状态参数
     * @return 登录页面信息或重定向信息
     */
    Result<Map<String, Object>> getSSOLoginInfo(String clientId, String redirectUri, String scope, String state);
    
    /**
     * 处理 SSO 登录
     * 
     * @param username 用户名
     * @param password 密码
     * @param clientId 客户端ID
     * @param redirectUri 重定向URI
     * @param scope 授权范围
     * @param state 状态参数
     * @return 登录结果
     */
    Result<Map<String, Object>> processSSOLogin(String username, String password, String clientId, 
                                               String redirectUri, String scope, String state);
    
    /**
     * 检查 SSO 登录状态
     * 
     * @param clientId 客户端ID
     * @param redirectUri 重定向URI
     * @param scope 授权范围
     * @param state 状态参数
     * @return 登录状态信息
     */
    Result<Map<String, Object>> checkSSOLoginStatus(String clientId, String redirectUri, String scope, String state);
    
    /**
     * SSO 单点注销
     * 
     * @param redirectUri 注销后重定向URI
     * @param clientId 客户端ID
     * @return 注销结果
     */
    Result<Map<String, Object>> ssoLogout(String redirectUri, String clientId);
    
    /**
     * 获取 SSO 用户信息
     * 
     * @param token SSO Token
     * @return 用户信息
     */
    Result<Map<String, Object>> getSSOUserInfo(String token);
    
    /**
     * 验证 SSO Token
     * 
     * @param token SSO Token
     * @return 验证结果
     */
    Result<Map<String, Object>> validateSSOToken(String token);
    
    /**
     * 刷新 SSO Token
     * 
     * @param refreshToken 刷新令牌
     * @param clientId 客户端ID
     * @return 新的 Token 信息
     */
    Result<Map<String, Object>> refreshSSOToken(String refreshToken, String clientId);
    
    /**
     * 获取所有已登录的客户端列表
     * 
     * @return 客户端列表
     */
    Result<Map<String, Object>> getLoggedInClients();
    
    /**
     * 从指定客户端注销
     * 
     * @param clientId 客户端ID
     * @return 注销结果
     */
    Result<Void> logoutFromClient(String clientId);
}
