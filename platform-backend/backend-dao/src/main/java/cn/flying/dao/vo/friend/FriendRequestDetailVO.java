package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 好友请求详情 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "好友请求详情")
public class FriendRequestDetailVO {

    @Schema(description = "请求ID")
    private String id;

    @Schema(description = "请求发起者ID")
    private String requesterId;

    @Schema(description = "请求发起者用户名")
    private String requesterUsername;

    @Schema(description = "请求发起者头像")
    private String requesterAvatar;

    @Schema(description = "请求接收者ID")
    private String addresseeId;

    @Schema(description = "请求接收者用户名")
    private String addresseeUsername;

    @Schema(description = "请求接收者头像")
    private String addresseeAvatar;

    @Schema(description = "请求消息")
    private String message;

    @Schema(description = "状态：0-待处理, 1-已接受, 2-已拒绝, 3-已取消")
    private Integer status;

    @Schema(description = "请求时间")
    private Date createTime;

    @Schema(description = "处理时间")
    private Date updateTime;
}
