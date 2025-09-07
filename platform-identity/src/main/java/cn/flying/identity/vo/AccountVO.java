package cn.flying.identity.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 用户响应类
 * 用于返回用户信息给前端
 */
@Setter
@Getter
public class AccountVO {
    
    /**
     * 内部ID，在安全切面处理后不会返回给前端
     */
    private Long id;
    
    /**
     * 外部ID，由安全切面自动填充
     */
    private String externalId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 邮箱
     */
    private String email;
    
    /**
     * 角色
     */
    private String role;
    
    /**
     * 头像URL
     */
    private String avatar;
    
    /**
     * 注册时间
     */
    private Date registerTime;

    @Override
    public String toString() {
        return "AccountVO{" +
                "id=" + id +
                ", externalId='" + externalId + '\'' +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", role='" + role + '\'' +
                ", avatar='" + avatar + '\'' +
                ", registerTime=" + registerTime +
                '}';
    }
}