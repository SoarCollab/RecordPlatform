package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * Token刷新响应
 */
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Token刷新响应")
public class RefreshTokenVO {

    @Schema(description = "新的访问令牌")
    private String token;

    @Schema(description = "过期时间")
    private Date expire;
}
