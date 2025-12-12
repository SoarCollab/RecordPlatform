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
 * 工单附件实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("ticket_attachment")
@Schema(name = "TicketAttachment", description = "工单附件实体")
public class TicketAttachment implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "工单ID")
    private Long ticketId;

    @Schema(description = "回复ID(为空则属于工单)")
    private Long replyId;

    @Schema(description = "文件ID(关联file表)")
    private Long fileId;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件大小(字节)")
    private Long fileSize;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;
}
