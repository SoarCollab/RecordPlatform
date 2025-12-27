package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 更新文件状态请求
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Schema(description = "更新文件状态请求")
public class UpdateFileStatusVO {

    @NotNull(message = "文件状态不能为空")
    @Schema(description = "文件状态：0-处理中，1-已完成，2-已删除，-1-失败")
    private Integer status;

    @Schema(description = "操作原因/备注")
    private String reason;
}
