package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * 好友文件分享详情 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "好友文件分享详情")
public class FriendFileShareDetailVO {

    @Schema(description = "分享ID")
    private String id;

    @Schema(description = "分享者ID")
    private String sharerId;

    @Schema(description = "分享者用户名")
    private String sharerUsername;

    @Schema(description = "分享者头像")
    private String sharerAvatar;

    @Schema(description = "接收者ID")
    private String friendId;

    @Schema(description = "接收者用户名")
    private String friendUsername;

    @Schema(description = "文件哈希列表")
    private List<String> fileHashes;

    @Schema(description = "文件名列表")
    private List<String> fileNames;

    @Schema(description = "文件数量")
    private Integer fileCount;

    @Schema(description = "分享消息")
    private String message;

    @Schema(description = "是否已读")
    private Boolean isRead;

    @Schema(description = "分享时间")
    private Date createTime;

    @Schema(description = "阅读时间")
    private Date readTime;
}
