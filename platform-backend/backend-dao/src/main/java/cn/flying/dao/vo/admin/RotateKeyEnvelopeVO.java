package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Admin request for rotating file key envelopes.
 *
 * @param reason operator-provided reason for the rotation
 */
@Schema(description = "文件密钥信封轮换请求")
public record RotateKeyEnvelopeVO(
        @Schema(description = "轮换原因/备注")
        String reason
) {
}
