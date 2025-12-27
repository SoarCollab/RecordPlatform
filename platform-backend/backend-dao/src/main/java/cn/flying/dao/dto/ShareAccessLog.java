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
 * 分享访问日志实体类
 * <p>
 * 记录分享链接的访问、下载、保存操作。
 * 用于审计和追踪文件的传播路径。
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Setter
@Getter
@TableName("share_access_log")
@Accessors(chain = true)
@Schema(name = "ShareAccessLog", description = "分享访问日志实体类")
public class ShareAccessLog implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "分享创建者用户ID")
    private Long shareOwnerId;

    @Schema(description = "操作类型：1=查看，2=下载，3=保存")
    private Integer actionType;

    @Schema(description = "操作者用户ID（匿名访问为NULL）")
    private Long actorUserId;

    @Schema(description = "操作者IP地址")
    private String actorIp;

    @Schema(description = "操作者User-Agent")
    private String actorUa;

    @Schema(description = "文件哈希（下载/保存时记录）")
    private String fileHash;

    @Schema(description = "文件名（下载/保存时记录）")
    private String fileName;

    @Schema(description = "访问时间")
    private Date accessTime;

    /**
     * 操作类型常量
     */
    public static final int ACTION_VIEW = 1;
    public static final int ACTION_DOWNLOAD = 2;
    public static final int ACTION_SAVE = 3;

    /**
     * 非持久化字段：操作者用户名
     */
    @TableField(exist = false)
    @Schema(description = "操作者用户名")
    private String actorUserName;
}
