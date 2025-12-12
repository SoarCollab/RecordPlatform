package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
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

    @Positive(message = "最大访问次数必须为正整数")
    @Schema(description = "分享码最大访问次数")
    private Integer maxAccesses;
}
