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
 * 私信消息实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("message")
@Schema(name = "Message", description = "私信消息实体")
public class Message implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "消息ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "会话ID")
    private Long conversationId;

    @Schema(description = "发送者ID")
    private Long senderId;

    @Schema(description = "接收者ID")
    private Long receiverId;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "内容类型: text, image, file")
    private String contentType;

    @Schema(description = "是否已读: 0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "阅读时间")
    private Date readTime;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableLogic
    @Schema(description = "逻辑删除: 0-未删除, 1-已删除")
    private Integer deleted;
}
