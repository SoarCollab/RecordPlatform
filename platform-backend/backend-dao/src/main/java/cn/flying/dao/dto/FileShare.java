package cn.flying.dao.dto;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * 文件分享元数据实体类
 * <p>
 * 存储分享类型等可变元数据，与区块链存储的核心分享数据配合使用。
 * 区块链存储：shareCode, fileHashes, expireTime, isValid（不可变）
 * 数据库存储：shareType, accessCount, status（可变）
 *
 * @author flyingcoding
 * @since 2025-12-23
 */
@Setter
@Getter
@TableName("file_share")
@Accessors(chain = true)
@Schema(name = "FileShare", description = "文件分享元数据实体类")
public class FileShare implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "分享创建者用户ID")
    private Long userId;

    @Schema(description = "区块链分享码（6字符）")
    private String shareCode;

    @Schema(description = "分享类型：0-公开，1-私密")
    private Integer shareType;

    @Schema(description = "分享的文件哈希列表（JSON数组）")
    private String fileHashes;

    @Schema(description = "过期时间")
    private Date expireTime;

    @Schema(description = "访问次数")
    private Integer accessCount;

    @Schema(description = "状态：0-已取消，1-有效，2-已过期")
    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    @Schema(description = "更新时间")
    private Date updateTime;

    @TableLogic
    @Schema(description = "逻辑删除：0-未删除，1-已删除")
    private Integer deleted;

    /**
     * 分享状态常量
     */
    public static final int STATUS_CANCELLED = 0;
    public static final int STATUS_ACTIVE = 1;
    public static final int STATUS_EXPIRED = 2;

    /**
     * 判断分享是否有效（未取消且未过期）
     */
    public boolean isValid() {
        return status != null && status == STATUS_ACTIVE
                && expireTime != null && expireTime.after(new Date());
    }

    /**
     * 判断是否为公开分享
     */
    public boolean isPublicShare() {
        return shareType != null && shareType == 0;
    }
}
