package cn.flying.identity.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 登录验证成功的用户信息响应VO
 * 用于返回登录成功后的用户信息和Token
 * 从 platform-backend 迁移而来，适配 SA-Token
 */
@Setter
@Getter
public class AuthorizeVO {
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 用户角色
     */
    private String role;
    
    /**
     * 访问令牌
     */
    private String token;
    
    /**
     * 令牌过期时间
     */
    private Date expire;
    
    /**
     * 用户ID（外部ID）
     */
    private String userId;
    
    /**
     * 用户邮箱
     */
    private String email;
    
    /**
     * 用户头像
     */
    private String avatar;

    @Override
    public String toString() {
        return "AuthorizeVO{" +
                "username='" + username + '\'' +
                ", role='" + role + '\'' +
                ", token='" + token + '\'' +
                ", expire=" + expire +
                ", userId='" + userId + '\'' +
                ", email='" + email + '\'' +
                ", avatar='" + avatar + '\'' +
                '}';
    }
}
