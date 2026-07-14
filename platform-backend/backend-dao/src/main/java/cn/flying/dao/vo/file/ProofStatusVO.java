package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * 公开证明生命周期状态，不暴露租户或数据库内部标识。
 */
@Schema(description = "证明包当前状态")
public record ProofStatusVO(
        @Schema(description = "不可枚举证明标识", requiredMode = Schema.RequiredMode.REQUIRED)
        String proofId,
        @Schema(description = "证明当前生命周期状态",
                allowableValues = {"ACTIVE", "REVOKED", "SUPERSEDED", "INVALID"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String status,
        @Schema(description = "单调递增状态版本", type = "string",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long statusVersion,
        @Schema(description = "签发时不可变状态快照",
                allowableValues = {"ACTIVE", "SUPERSEDED"},
                requiredMode = Schema.RequiredMode.REQUIRED)
        String issuedStatus,
        @Schema(description = "签名 key id", requiredMode = Schema.RequiredMode.REQUIRED)
        String keyId,
        @Schema(description = "签名 key 版本", requiredMode = Schema.RequiredMode.REQUIRED)
        Integer keyVersion,
        @Schema(description = "状态原因；可能为空")
        String reason,
        @Schema(description = "签发时间", requiredMode = Schema.RequiredMode.REQUIRED)
        Date issuedAt,
        @Schema(description = "状态更新时间", requiredMode = Schema.RequiredMode.REQUIRED)
        Date updatedAt
) {
}
