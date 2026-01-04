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
 * 好友请求实体
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("friend_request")
@Schema(name = "FriendRequest", description = "好友请求实体")
public class FriendRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：待处理 */
    public static final int STATUS_PENDING = 0;
    /** 状态：已接受 */
    public static final int STATUS_ACCEPTED = 1;
    /** 状态：已拒绝 */
    public static final int STATUS_REJECTED = 2;
    /** 状态：已取消 */
    public static final int STATUS_CANCELLED = 3;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "请求ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "请求发起者ID")
    private Long requesterId;

    @Schema(description = "请求接收者ID")
    private Long addresseeId;

    @Schema(description = "请求消息")
    private String message;

    @Schema(description = "状态：0-待处理, 1-已接受, 2-已拒绝, 3-已取消")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Date updateTime;

    @TableLogic
    @Schema(description = "删除标记")
    private Integer deleted;

    /**
     * 判断是否为待处理状态
     */
    public boolean isPending() {
        return status != null && status == STATUS_PENDING;
    }

    /**
     * 判断是否已被处理
     */
    public boolean isProcessed() {
        return status != null && status != STATUS_PENDING;
    }
}
