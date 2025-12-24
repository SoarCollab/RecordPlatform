package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 文件分享VO
 *
 * @author flyingcoding
 * @since 2025-04-27
 */
@Getter
@Setter
@Schema(description = "文件分享VO")
public class FileSharingVO {

    @NotEmpty(message = "待分享文件Hash列表不能为空")
    @Schema(description = "待分享文件Hash列表")
    private List<String> fileHash;

    @NotNull(message = "分享有效期不能为空（分钟）")
    @Positive(message = "分享有效期必须为正整数（分钟）")
    @Max(value = 43200, message = "分享有效期不能超过 43200 分钟（30 天）")
    @Schema(description = "分享有效期（分钟），最大 43200（30 天）")
    private Integer expireMinutes;

    @Schema(description = "分享类型：0-公开（无需登录），1-私密（需要登录），默认公开")
    private Integer shareType = 0;
}
