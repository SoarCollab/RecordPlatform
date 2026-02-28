package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "创建新版本请求")
public record CreateVersionVO(
        @NotBlank(message = "文件名不能为空")
        @Schema(description = "文件名称", requiredMode = Schema.RequiredMode.REQUIRED)
        String fileName,
        @Min(value = 1, message = "文件大小必须大于0")
        @Schema(description = "文件大小（字节）", requiredMode = Schema.RequiredMode.REQUIRED)
        long fileSize,
        @NotBlank(message = "文件类型不能为空")
        @Schema(description = "文件类型", requiredMode = Schema.RequiredMode.REQUIRED)
        String contentType
) {
}
