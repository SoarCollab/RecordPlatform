package cn.flying.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 好友关系实体
 * 使用较小ID作为userA，较大ID作为userB，确保唯一性
 */
@Setter
@Getter
@NoArgsConstructor
@Accessors(chain = true)
@TableName("friendship")
@Schema(name = "Friendship", description = "好友关系实体")
public class Friendship implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "关系ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "用户A(ID较小者)")
    private Long userA;

    @Schema(description = "用户B(ID较大者)")
    private Long userB;

    @Schema(description = "原始好友请求ID")
    private Long requestId;

    @Schema(description = "用户A对用户B的备注")
    private String remarkA;

    @Schema(description = "用户B对用户A的备注")
    private String remarkB;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "建立时间")
    private Date createTime;

    @TableLogic
    @Schema(description = "删除标记(解除好友)")
    private Integer deleted;

    /**
     * 获取好友ID
     * @param userId 当前用户ID
     * @return 好友ID
     */
    public Long getFriendId(Long userId) {
        return userId.equals(userA) ? userB : userA;
    }

    /**
     * 获取当前用户对好友的备注
     * @param userId 当前用户ID
     * @return 备注
     */
    public String getRemark(Long userId) {
        return userId.equals(userA) ? remarkA : remarkB;
    }

    /**
     * 设置当前用户对好友的备注
     * @param userId 当前用户ID
     * @param remark 备注
     */
    public Friendship setRemark(Long userId, String remark) {
        if (userId.equals(userA)) {
            this.remarkA = remark;
        } else {
            this.remarkB = remark;
        }
        return this;
    }

    /**
     * 静态方法：创建好友关系（自动排序ID）
     */
    public static Friendship create(Long userId1, Long userId2, Long requestId) {
        Long userA = Math.min(userId1, userId2);
        Long userB = Math.max(userId1, userId2);
        return new Friendship()
                .setUserA(userA)
                .setUserB(userB)
                .setRequestId(requestId);
    }
}
