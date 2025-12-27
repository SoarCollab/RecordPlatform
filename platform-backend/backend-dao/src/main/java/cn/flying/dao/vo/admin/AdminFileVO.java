package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 管理员文件列表 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员文件列表VO")
public class AdminFileVO {

    @Schema(description = "文件ID（外部ID）")
    private String id;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件哈希")
    private String fileHash;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "文件类型")
    private String contentType;

    @Schema(description = "文件状态：0-处理中，1-已完成，2-已删除，-1-失败")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "所有者用户ID")
    private String ownerId;

    @Schema(description = "所有者用户名")
    private String ownerName;

    @Schema(description = "原始上传者用户ID（如果是分享保存的文件）")
    private String originOwnerId;

    @Schema(description = "原始上传者用户名")
    private String originOwnerName;

    @Schema(description = "直接分享者用户ID")
    private String sharedFromUserId;

    @Schema(description = "直接分享者用户名")
    private String sharedFromUserName;

    @Schema(description = "是否为原始文件（自己上传的）")
    private Boolean isOriginal;

    @Schema(description = "分享链路深度")
    private Integer depth;

    @Schema(description = "区块链交易哈希")
    private String transactionHash;

    @Schema(description = "区块号")
    private Long blockNumber;

    @Schema(description = "引用计数（被分享保存的次数）")
    private Integer refCount;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;
}
