package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(name = "account", description = "用户实体类")
public class Account implements BaseData {
    @TableId(type = IdType.INPUT)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "邮箱")
    private String email;

    @Schema(description = "角色")
    private String role;

    @Schema(description = "头像Url")
    private String avatar;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "注册时间")
    Date registerTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    Date updateTime;

    @TableLogic
    @Schema(description = "逻辑删除字段：0—>未删除 , 1->已删除")
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
