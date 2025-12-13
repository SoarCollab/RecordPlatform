package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 工单更新 VO
 */
@Data
@Schema(description = "工单更新请求")
public class TicketUpdateVO {

    @Size(max = 200, message = "工单标题不能超过200个字符")
    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "优先级: 0-低, 1-中, 2-高")
    private Integer priority;

    @Schema(description = "类别: 0-Bug, 1-功能请求, 2-问题咨询, 3-反馈建议, 99-其他")
    private Integer category;
}
