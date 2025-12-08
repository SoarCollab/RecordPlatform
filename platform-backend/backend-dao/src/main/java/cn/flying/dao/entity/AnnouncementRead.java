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
 * 公告已读记录实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("announcement_read")
@Schema(name = "AnnouncementRead", description = "公告已读记录实体")
public class AnnouncementRead implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "公告ID")
    private Long announcementId;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "阅读时间")
    private Date readTime;
}
