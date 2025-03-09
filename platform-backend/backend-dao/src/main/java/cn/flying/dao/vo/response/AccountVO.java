package cn.flying.dao.vo.response;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 用户响应类
 * @author: flyingcoding
 * @create: 2025-01-16 14:57
 */
@Setter
@Getter
public class AccountVO {
    // 内部ID，在安全切面处理后不会返回给前端
    private Long id;
    // 外部ID，由安全切面自动填充
    private String externalId;
    String username;
    String email;
    String role;
    String avatar;
    Date registerTime;

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
