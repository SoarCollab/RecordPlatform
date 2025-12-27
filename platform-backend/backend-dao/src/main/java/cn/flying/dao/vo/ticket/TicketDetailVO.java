package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * 工单详情 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "工单详情")
public class TicketDetailVO {

    @Schema(description = "工单ID")
    private String id;

    @Schema(description = "工单编号")
    private String ticketNo;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "优先级")
    private Integer priority;

    @Schema(description = "优先级描述")
    private String priorityDesc;

    @Schema(description = "类别: 0-Bug, 1-功能请求, 2-问题咨询, 3-反馈建议, 99-其他")
    private Integer category;

    @Schema(description = "类别描述")
    private String categoryDesc;

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

    @Schema(description = "附件列表")
    private List<TicketAttachmentVO> attachments;

    @Schema(description = "回复列表")
    private List<TicketReplyDetailVO> replies;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "关闭时间")
    private Date closeTime;
}
