package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * @program: RecordPlatform
 * @description: 用户实体类
 * @author: 王贝强
 * @create: 2025-01-16 14:55
 */
@Setter
@Getter
@TableName("account")
public class Account implements BaseData {
    @TableId(type = IdType.ASSIGN_ID)
    String id;
    String username;
    String password;
    String email;
    String role;
    String avatar;
    @TableField(fill = FieldFill.INSERT)
    Date registerTime;
    @TableField(fill = FieldFill.INSERT)
    Integer isDelete;

    public Account(String username, String password, String email, String role, String avatar) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.role = role;
        this.avatar = avatar;
    }
}
