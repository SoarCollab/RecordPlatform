package cn.flying.dao.vo.response;

import lombok.Data;

/**
 * 用户视图对象，用于API响应
 * 包含内部ID和外部ID两个字段
 */
@Data
public class UserVO {
    // 内部ID，在安全切面处理后不会返回给前端
    private Long id;
    
    // 外部ID，由安全切面自动填充
    private String externalId;
    
    private String username;
    private String email;
    private String role;
    private String avatar;
    
    // 其他用户信息字段...
} 