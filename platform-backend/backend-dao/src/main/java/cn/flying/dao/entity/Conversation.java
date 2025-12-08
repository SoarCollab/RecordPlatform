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
 * 私信会话实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("conversation")
@Schema(name = "Conversation", description = "私信会话实体")
public class Conversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "会话ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "参与者A(ID较小者)")
    private Long participantA;

    @Schema(description = "参与者B(ID较大者)")
    private Long participantB;

    @Schema(description = "最后一条消息ID")
    private Long lastMessageId;

    @Schema(description = "最后消息时间")
    private Date lastMessageAt;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Date updateTime;

    /**
     * 获取对方用户ID
     */
    public Long getOtherParticipant(Long userId) {
        return userId.equals(participantA) ? participantB : participantA;
    }
}
