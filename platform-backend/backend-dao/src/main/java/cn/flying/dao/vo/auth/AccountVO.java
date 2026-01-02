package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 用户响应类
 * @author flyingcoding
 * @create: 2025-01-16 14:57
 */
@Setter
@Getter
@Schema(description = "用户响应类")
public class AccountVO {
    // 内部ID，在安全切面处理后不会返回给前端
    @Schema(description = "内部ID")
    private Long id;
    // 外部ID，由安全切面自动填充
    @Schema(description = "外部ID")
    private String externalId;
    @Schema(description = "用户名")
    String username;
    @Schema(description = "邮箱")
    String email;
    @Schema(description = "角色")
    String role;
    @Schema(description = "头像Url")
    String avatar;
    @Schema(description = "昵称")
    String nickname;
    @Schema(description = "注册时间")
    Date registerTime;
    @Schema(description = "状态：0-正常，1-已禁用")
    Integer deleted;

    @Override
    public String toString() {
        return "AccountVO{" +
                "id=" + id +
                ", externalId='" + externalId + '\'' +
                ", username='" + username + '\'' +
                ", email='" + email + '\'' +
                ", role='" + role + '\'' +
                ", avatar='" + avatar + '\'' +
                ", nickname='" + nickname + '\'' +
                ", registerTime=" + registerTime +
                ", deleted=" + deleted +
                '}';
    }
}
