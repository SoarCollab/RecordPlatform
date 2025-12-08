package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 工单查询 VO
 */
@Data
@Schema(description = "工单查询条件")
public class TicketQueryVO {

    @Schema(description = "工单编号")
    private String ticketNo;

    @Schema(description = "状态: 0-待处理, 1-处理中, 2-待确认, 3-已完成, 4-已关闭")
    private Integer status;

    @Schema(description = "优先级: 0-低, 1-中, 2-高")
    private Integer priority;

    @Schema(description = "关键词(标题/内容)")
    private String keyword;
}
