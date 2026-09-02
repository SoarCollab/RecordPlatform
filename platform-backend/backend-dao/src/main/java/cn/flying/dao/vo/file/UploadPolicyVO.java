package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Exposes the current server-authoritative upload policy to authenticated clients.
 */
@Schema(description = "上传策略")
public record UploadPolicyVO(
        @Schema(description = "单文件最大字节数", example = "4294967296", requiredMode = Schema.RequiredMode.REQUIRED)
        long maxFileSizeBytes,
        @Schema(description = "允许的文件类型及预览能力", requiredMode = Schema.RequiredMode.REQUIRED)
        List<UploadFileTypePolicyVO> fileTypes) {
}
