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
 * 文件来源链实体类
 * <p>
 * 用于追踪文件分享的完整传递链路。
 * 当用户保存他人分享的文件时，记录：
 * - 最初上传者的文件ID (origin_file_id)
 * - 直接分享者的文件ID (source_file_id)
 * - 直接分享者的用户ID (source_user_id)
 * - 链路深度 (depth)
 * <p>
 * 通过递归查询可还原完整传递路径：A -> B -> C -> D
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Setter
@Getter
@TableName("file_source")
@Accessors(chain = true)
@Schema(name = "FileSource", description = "文件来源链实体类")
public class FileSource implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "租户ID")
    private Long tenantId;

    @Schema(description = "当前文件ID")
    private Long fileId;

    @Schema(description = "最初上传者的文件ID")
    private Long originFileId;

    @Schema(description = "直接来源文件ID（分享者的文件）")
    private Long sourceFileId;

    @Schema(description = "分享给我的用户ID")
    private Long sourceUserId;

    @Schema(description = "使用的分享码")
    private String shareCode;

    @Schema(description = "链路深度（1=首次分享，2=二次分享...）")
    private Integer depth;

    @TableField(fill = FieldFill.INSERT)
    @Schema(description = "创建时间")
    private Date createTime;

    /**
     * 非持久化字段：来源用户名
     */
    @TableField(exist = false)
    @Schema(description = "来源用户名")
    private String sourceUserName;
}
