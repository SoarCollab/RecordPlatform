package cn.flying.identity.service;

/**
 * 密码加密服务接口
 * 替代 Spring Security 的 PasswordEncoder，使用轻量级加密库
 * 
 * @author 王贝强
 */
public interface PasswordService {
    
    /**
     * 加密密码
     * 
     * @param rawPassword 原始密码
     * @return 加密后的密码
     */
    String encode(String rawPassword);
    
    /**
     * 验证密码
     * 
     * @param rawPassword 原始密码
     * @param encodedPassword 加密后的密码
     * @return 是否匹配
     */
    boolean matches(String rawPassword, String encodedPassword);
}
