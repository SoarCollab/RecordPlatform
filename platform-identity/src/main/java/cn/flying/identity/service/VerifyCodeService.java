package cn.flying.identity.service;

import cn.flying.platformapi.constant.Result;

import java.util.Map;

/**
 * 验证码服务接口
 * 提供邮件验证码、短信验证码等功能
 * 
 * @author 王贝强
 */
public interface VerifyCodeService {
    
    /**
     * 发送邮件验证码
     * 
     * @param email 邮箱地址
     * @param type 验证码类型（register、reset、modify等）
     * @param clientIp 客户端IP
     * @return 发送结果
     */
    Result<Void> sendEmailVerifyCode(String email, String type, String clientIp);
    
    /**
     * 验证邮件验证码
     * 
     * @param email 邮箱地址
     * @param code 验证码
     * @param type 验证码类型
     * @return 验证结果
     */
    Result<Boolean> verifyEmailCode(String email, String code, String type);
    
    /**
     * 发送短信验证码
     * 
     * @param phone 手机号码
     * @param type 验证码类型
     * @param clientIp 客户端IP
     * @return 发送结果
     */
    Result<Void> sendSmsVerifyCode(String phone, String type, String clientIp);
    
    /**
     * 验证短信验证码
     * 
     * @param phone 手机号码
     * @param code 验证码
     * @param type 验证码类型
     * @return 验证结果
     */
    Result<Boolean> verifySmsCode(String phone, String code, String type);
    
    /**
     * 生成图形验证码
     * 
     * @param sessionId 会话ID
     * @return 验证码图片数据（Base64编码）
     */
    Result<Map<String, Object>> generateImageCaptcha(String sessionId);
    
    /**
     * 验证图形验证码
     * 
     * @param sessionId 会话ID
     * @param code 验证码
     * @return 验证结果
     */
    Result<Boolean> verifyImageCaptcha(String sessionId, String code);
    
    /**
     * 清除验证码
     * 
     * @param identifier 标识符（邮箱、手机号等）
     * @param type 验证码类型
     * @return 清除结果
     */
    Result<Void> clearVerifyCode(String identifier, String type);
    
    /**
     * 获取验证码发送统计
     * 
     * @param identifier 标识符
     * @param timeRange 时间范围（小时）
     * @return 发送统计
     */
    Result<Map<String, Object>> getVerifyCodeStats(String identifier, int timeRange);
    
    /**
     * 检查验证码发送频率限制
     * 
     * @param identifier 标识符
     * @param type 验证码类型
     * @param clientIp 客户端IP
     * @return 是否允许发送
     */
    Result<Boolean> checkSendLimit(String identifier, String type, String clientIp);
    
    /**
     * 获取验证码剩余有效时间
     * 
     * @param identifier 标识符
     * @param type 验证码类型
     * @return 剩余时间（秒）
     */
    Result<Long> getVerifyCodeTtl(String identifier, String type);
    
    /**
     * 批量清理过期验证码
     * 
     * @return 清理结果
     */
    Result<Map<String, Object>> cleanExpiredCodes();
    
    /**
     * 获取验证码配置信息
     * 
     * @return 配置信息
     */
    Result<Map<String, Object>> getVerifyCodeConfig();
}
