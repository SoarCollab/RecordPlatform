package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 用户实体类
 * @author: flyingcoding
 * @create: 2025-01-16 14:55
 */
@Setter
@Getter
@NoArgsConstructor
@TableName("account")
public class Account implements BaseData {
    @TableId(type = IdType.INPUT)
    Long id;
    String username;
    String password;
    String email;
    String role;
    String avatar;
    @TableField(fill = FieldFill.INSERT)
    Date registerTime;
    @TableLogic
    Integer deleted;

    public Account(Long id, String username, String password, String email, String role, String avatar) {
        this.id=id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.role = role;
        this.avatar = avatar;
    }
}
