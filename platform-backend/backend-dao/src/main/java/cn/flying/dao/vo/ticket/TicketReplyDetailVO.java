package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * 工单回复详情 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "工单回复详情")
public class TicketReplyDetailVO {

    @Schema(description = "回复ID")
    private String id;

    @Schema(description = "回复者ID")
    private String replierId;

    @Schema(description = "回复者用户名")
    private String replierName;

    @Schema(description = "回复者头像")
    private String replierAvatar;

    @Schema(description = "回复内容")
    private String content;

    @Schema(description = "是否内部备注")
    private Boolean isInternal;

    @Schema(description = "是否是管理员回复")
    private Boolean isAdmin;

    @Schema(description = "附件列表")
    private List<TicketAttachmentVO> attachments;

    @Schema(description = "创建时间")
    private Date createTime;
}
