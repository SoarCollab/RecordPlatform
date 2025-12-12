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
 * 工单实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("ticket")
@Schema(name = "Ticket", description = "工单实体")
public class Ticket implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "工单ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "工单编号")
    private String ticketNo;

    @Schema(description = "工单标题")
    private String title;

    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "优先级: 0-低, 1-中, 2-高")
    private Integer priority;

    @Schema(description = "状态: 0-待处理, 1-处理中, 2-待确认, 3-已完成, 4-已关闭")
    private Integer status;

    @Schema(description = "创建者ID")
    private Long creatorId;

    @Schema(description = "处理人ID")
    private Long assigneeId;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Date updateTime;

    @Schema(description = "关闭时间")
    private Date closeTime;

    @TableLogic
    @Schema(description = "逻辑删除: 0-未删除, 1-已删除")
    private Integer deleted;
}
