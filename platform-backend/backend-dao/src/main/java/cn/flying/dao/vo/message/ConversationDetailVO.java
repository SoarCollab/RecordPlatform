package cn.flying.dao.vo.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 会话详情 VO（包含消息列表）
 */
@Data
@Accessors(chain = true)
@Schema(description = "会话详情")
public class ConversationDetailVO {

    @Schema(description = "会话ID")
    private String id;

    @Schema(description = "对方用户ID")
    private String otherUserId;

    @Schema(description = "对方用户名")
    private String otherUsername;

    @Schema(description = "对方头像")
    private String otherAvatar;

    @Schema(description = "消息列表")
    private List<MessageVO> messages;

    @Schema(description = "是否有更多消息")
    private Boolean hasMore;

    @Schema(description = "总消息数")
    private Long totalMessages;
}
