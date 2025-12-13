package cn.flying.dao.vo.ticket;

import cn.flying.common.constant.TicketPriority;
import cn.flying.common.constant.TicketStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 工单列表 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "工单信息")
public class TicketVO {

    @Schema(description = "工单ID")
    private String id;

    @Schema(description = "工单编号")
    private String ticketNo;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "优先级")
    private Integer priority;

    @Schema(description = "优先级描述")
    private String priorityDesc;

    @Schema(description = "状态")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "创建者ID")
    private String creatorId;

    @Schema(description = "创建者用户名")
    private String creatorUsername;

    @Schema(description = "处理人ID")
    private String assigneeId;

    @Schema(description = "处理人用户名")
    private String assigneeUsername;

    @Schema(description = "回复数量")
    private Integer replyCount;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "关闭时间")
    private Date closeTime;

    /**
     * 设置优先级并填充描述
     */
    public TicketVO setPriorityWithDesc(Integer priority) {
        this.priority = priority;
        if (priority != null) {
            this.priorityDesc = TicketPriority.fromCode(priority).getDescription();
        }
        return this;
    }

    /**
     * 设置状态并填充描述
     */
    public TicketVO setStatusWithDesc(Integer status) {
        this.status = status;
        if (status != null) {
            this.statusDesc = TicketStatus.fromCode(status).getDescription();
        }
        return this;
    }
}
