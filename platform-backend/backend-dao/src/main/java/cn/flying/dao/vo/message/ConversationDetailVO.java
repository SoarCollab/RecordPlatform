package cn.flying.dao.vo.message;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 会话详情 VO（包含消息列表）
 */
@Data
@Accessors(chain = true)
@Schema(description = "会话详情")
public class ConversationDetailVO {

    @Schema(description = "会话信息")
    private ConversationVO conversation;

    @Schema(description = "消息分页数据")
    private IPage<MessageVO> messages;

    @Schema(description = "是否有更多消息")
    private Boolean hasMore;

    @Schema(description = "总消息数")
    private Long totalMessages;
}
