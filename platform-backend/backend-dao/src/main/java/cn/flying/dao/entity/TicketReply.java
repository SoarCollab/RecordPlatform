package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 工单回复实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("ticket_reply")
@Schema(name = "TicketReply", description = "工单回复实体")
public class TicketReply implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "回复ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "工单ID")
    private Long ticketId;

    @Schema(description = "回复者ID")
    private Long replierId;

    @Schema(description = "回复内容")
    private String content;

    @Schema(description = "是否内部备注: 0-否, 1-是(仅管理员可见)")
    private Integer isInternal;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableLogic
    @Schema(description = "逻辑删除: 0-未删除, 1-已删除")
    private Integer deleted;
}
