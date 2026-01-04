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
 * 好友文件分享实体
 * 直接分享文件给好友，无需分享码
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("friend_file_share")
@Schema(name = "FriendFileShare", description = "好友文件分享实体")
public class FriendFileShare implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态：已取消 */
    public static final int STATUS_CANCELLED = 0;
    /** 状态：有效 */
    public static final int STATUS_ACTIVE = 1;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "分享ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "分享者ID")
    private Long sharerId;

    @Schema(description = "接收好友ID")
    private Long friendId;

    @Schema(description = "文件哈希列表(JSON数组)")
    private String fileHashes;

    @Schema(description = "分享消息")
    private String message;

    @Schema(description = "是否已读：0-未读, 1-已读")
    private Integer isRead;

    @Schema(description = "状态：0-已取消, 1-有效")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "首次阅读时间")
    private Date readTime;

    /**
     * 判断是否有效
     */
    public boolean isActive() {
        return status != null && status == STATUS_ACTIVE;
    }

    /**
     * 判断是否未读
     */
    public boolean isUnread() {
        return isRead == null || isRead == 0;
    }
}
