package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 用户搜索结果 VO
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户搜索结果")
public class UserSearchVO {

    @Schema(description = "用户ID")
    private String id;

    @Schema(description = "用户名")
    private String username;

    @Schema(description = "头像")
    private String avatar;

    @Schema(description = "昵称")
    private String nickname;

    @Schema(description = "是否已是好友")
    private Boolean isFriend;

    @Schema(description = "是否有待处理的请求")
    private Boolean hasPendingRequest;
}
