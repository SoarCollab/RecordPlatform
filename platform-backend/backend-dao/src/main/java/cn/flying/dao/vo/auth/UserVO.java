package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户视图对象，用于API响应
 * 包含内部ID和外部ID两个字段
 */
@Data
@Schema(description = "用户视图对象")
public class UserVO {
    // 内部ID，在安全切面处理后不会返回给前端
    @Schema(description = "内部ID")
    private Long id;
    // 外部ID，由安全切面自动填充
    @Schema(description = "外部ID")
    private String externalId;
    @Schema(description = "用户名")
    private String username;
    @Schema(description = "邮箱")
    private String email;
    @Schema(description = "角色")
    private String role;
    @Schema(description = "头像Url")
    private String avatar;
} 