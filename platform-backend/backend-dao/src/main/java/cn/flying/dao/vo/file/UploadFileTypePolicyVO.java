package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Describes one server-authorized upload file extension and its preview capability.
 */
@Schema(description = "上传文件类型策略")
public record UploadFileTypePolicyVO(
        @Schema(description = "不含点号的小写文件扩展名", example = "pdf", requiredMode = Schema.RequiredMode.REQUIRED)
        String extension,
        @Schema(description = "能力分组编码", example = "document", requiredMode = Schema.RequiredMode.REQUIRED)
        String category,
        @Schema(description = "能力分组名称", example = "文档", requiredMode = Schema.RequiredMode.REQUIRED)
        String categoryLabel,
        @Schema(description = "预览模式", allowableValues = {"image", "video", "audio", "pdf", "text", "unsupported"}, requiredMode = Schema.RequiredMode.REQUIRED)
        String previewMode,
        @Schema(description = "与扩展名兼容的具体 MIME 别名", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> mimeTypes) {
}
