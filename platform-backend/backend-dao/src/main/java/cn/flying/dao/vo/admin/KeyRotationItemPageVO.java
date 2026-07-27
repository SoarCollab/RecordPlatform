package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Bounded opaque-cursor page of rotation item outcomes.
 */
@Schema(description = "Automated key rotation item cursor page")
public record KeyRotationItemPageVO(
        List<KeyRotationItemVO> records,
        String nextCursor
) {
}
