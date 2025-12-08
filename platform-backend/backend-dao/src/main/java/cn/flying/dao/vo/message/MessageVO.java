package cn.flying.dao.vo.message;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 消息 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "消息信息")
public class MessageVO {

    @Schema(description = "消息ID")
    private String id;

    @Schema(description = "发送者ID")
    private String senderId;

    @Schema(description = "发送者用户名")
    private String senderUsername;

    @Schema(description = "发送者头像")
    private String senderAvatar;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "内容类型: text, image, file")
    private String contentType;

    @Schema(description = "是否是自己发送的")
    private Boolean isMine;

    @Schema(description = "是否已读")
    private Boolean isRead;

    @Schema(description = "发送时间")
    private Date createTime;
}
