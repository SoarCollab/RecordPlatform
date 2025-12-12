package cn.flying.dao.vo.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * SSE 短期令牌响应
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "SSE短期令牌响应")
public class SseTokenVO {
    @Schema(description = "SSE专用短期令牌")
    private String sseToken;

    @Schema(description = "令牌有效期（秒）")
    private long expiresIn;
}
