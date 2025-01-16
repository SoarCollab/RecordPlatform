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
    String id;
    String username;
    String email;
    String role;
    String avatar;
    Date registerTime;
}
