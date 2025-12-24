package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

/**
 * 更新分享设置 VO
 *
 * @author flyingcoding
 * @since 2025-12-23
 */
@Getter
@Setter
@Schema(description = "更新分享设置VO")
public class UpdateShareVO {

    @NotBlank(message = "分享码不能为空")
    @Schema(description = "分享码", requiredMode = Schema.RequiredMode.REQUIRED)
    private String shareCode;

    @Min(value = 0, message = "分享类型必须是 0 或 1")
    @Max(value = 1, message = "分享类型必须是 0 或 1")
    @Schema(description = "分享类型：0-公开，1-私密")
    private Integer shareType;

    @Positive(message = "延长时间必须为正整数")
    @Max(value = 43200, message = "延长时间不能超过 43200 分钟（30 天）")
    @Schema(description = "延长有效期（分钟），可选，设置后从当前时间开始计算新的过期时间")
    private Integer extendMinutes;
}
