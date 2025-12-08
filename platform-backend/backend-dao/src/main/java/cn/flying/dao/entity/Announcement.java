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
 * 系统公告实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("announcement")
@Schema(name = "Announcement", description = "系统公告实体")
public class Announcement implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "公告标题")
    private String title;

    @Schema(description = "公告内容(支持Markdown)")
    private String content;

    @Schema(description = "优先级: 0-普通, 1-重要, 2-紧急")
    private Integer priority;

    @Schema(description = "是否置顶: 0-否, 1-是")
    private Integer isPinned;

    @Schema(description = "发布时间")
    private Date publishTime;

    @Schema(description = "过期时间")
    private Date expireTime;

    @Schema(description = "状态: 0-草稿, 1-已发布, 2-已过期")
    private Integer status;

    @Schema(description = "发布者ID")
    private Long publisherId;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Date updateTime;

    @TableLogic
    @Schema(description = "逻辑删除: 0-未删除, 1-已删除")
    private Integer deleted;
}
