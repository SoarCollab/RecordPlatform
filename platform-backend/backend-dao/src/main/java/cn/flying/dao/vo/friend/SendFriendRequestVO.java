package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送好友请求 VO
 */
@Data
@Schema(description = "发送好友请求")
public class SendFriendRequestVO {

    @NotBlank(message = "用户ID不能为空")
    @Schema(description = "目标用户ID")
    private String addresseeId;

    @Size(max = 255, message = "请求消息不能超过255个字符")
    @Schema(description = "请求消息")
    private String message;
}
