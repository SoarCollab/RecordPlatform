package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 登录验证成功的用户信息响应
 */
@Setter
@Getter
@Schema(description = "登录验证成功的用户信息响应")
public class AuthorizeVO {
    @Schema(description = "用户名")
    String username;
    @Schema(description = "角色")
    String role;
    @Schema(
            description = "身份作用域：tenant 或 platform",
            allowableValues = {"tenant", "platform"},
            requiredMode = Schema.RequiredMode.REQUIRED)
    String scope;
    @Schema(description = "token")
    String token;
    @Schema(description = "token过期时间")
    Date expire;

    @Override
    public String toString() {
        return "AuthorizeVO{" +
                "username='" + username + '\'' +
                ", role='" + role + '\'' +
                ", scope='" + scope + '\'' +
                ", token='[REDACTED]'" +
                ", expire=" + expire +
                '}';
    }
}
