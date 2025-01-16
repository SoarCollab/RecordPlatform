package cn.flying.dao.vo.response;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 登录验证成功的用户信息响应
 */
@Setter
@Getter
public class AuthorizeVO {
    String username;
    String role;
    String token;
    Date expire;
}
