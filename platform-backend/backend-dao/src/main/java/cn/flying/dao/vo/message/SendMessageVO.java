package cn.flying.dao.vo.message;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发送消息 VO
 */
@Data
@Schema(description = "发送消息请求")
public class SendMessageVO {

    @NotNull(message = "接收者ID不能为空")
    @Schema(description = "接收者ID")
    private String receiverId;

    @NotBlank(message = "消息内容不能为空")
    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "内容类型: text, image, file", defaultValue = "text")
    private String contentType = "text";
}
