package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result returned after rotating a file's active key envelopes.
 *
 * @param fileId external file id
 * @param fileHash file hash
 * @param targetKeyVersion configured wrapping key version
 * @param rotatedCount number of envelopes rewrapped
 * @param skippedCount number of envelopes already on the target version or otherwise skipped
 */
@Schema(description = "文件密钥信封轮换结果")
public record KeyEnvelopeRotationResultVO(
        @Schema(description = "文件ID")
        String fileId,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "目标密钥版本")
        Integer targetKeyVersion,
        @Schema(description = "已轮换信封数")
        int rotatedCount,
        @Schema(description = "跳过信封数")
        int skippedCount
) {
}
