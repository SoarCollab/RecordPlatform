package cn.flying.identity.dto;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 用户实体类
 * 用于存储用户基本信息和认证数据
 * 从 platform-backend 迁移而来
 */
@Setter
@Getter
@NoArgsConstructor
@TableName("account")
public class Account implements BaseData {
    
    @TableId(type = IdType.INPUT)
    private Long id;

    /**
     * 用户名
     */
    private String username;

    /**
     * 密码（加密存储）
     */
    private String password;

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
    @TableField(fill = FieldFill.INSERT)
    private Date registerTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updateTime;

    /**
     * 逻辑删除字段：0->未删除，1->已删除
     */
    @TableLogic
    private Integer deleted;

    /**
     * 构造函数
     * @param id 用户ID
     * @param username 用户名
     * @param password 密码
     * @param email 邮箱
     * @param role 角色
     * @param avatar 头像
     */
    public Account(Long id, String username, String password, String email, String role, String avatar) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.role = role;
        this.avatar = avatar;
    }
}