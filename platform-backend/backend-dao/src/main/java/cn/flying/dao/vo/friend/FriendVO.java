package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 好友信息 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "好友信息")
public class FriendVO {

    @Schema(description = "好友用户ID")
    private String id;

    @Schema(description = "好友关系ID")
    private String friendshipId;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "成为好友的时间")
    private Date friendSince;
}
