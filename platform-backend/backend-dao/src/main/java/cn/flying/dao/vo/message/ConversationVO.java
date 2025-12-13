package cn.flying.dao.vo.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 会话列表 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "会话信息")
public class ConversationVO {

    @Schema(description = "会话ID")
    private String id;

    @Schema(description = "对方用户ID")
    private String otherUserId;

    @Schema(description = "对方用户名")
    private String otherUsername;

    @Schema(description = "对方头像")
    private String otherAvatar;

    @Schema(description = "最后一条消息内容")
    private String lastMessageContent;

    @Schema(description = "最后一条消息类型")
    private String lastMessageType;

    @Schema(description = "最后消息时间")
    private Date lastMessageTime;

    @Schema(description = "未读消息数")
    private Integer unreadCount;
}
