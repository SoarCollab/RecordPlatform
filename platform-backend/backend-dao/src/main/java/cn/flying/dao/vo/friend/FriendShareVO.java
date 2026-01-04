package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 好友文件分享请求 VO
 */
@Data
@Schema(description = "好友文件分享请求")
public class FriendShareVO {

    @NotBlank(message = "好友ID不能为空")
    @Schema(description = "好友用户ID")
    private String friendId;

    @NotEmpty(message = "文件列表不能为空")
    @Schema(description = "文件哈希列表")
    private List<String> fileHashes;

    @Size(max = 255, message = "分享消息不能超过255个字符")
    @Schema(description = "分享消息")
    private String message;
}
