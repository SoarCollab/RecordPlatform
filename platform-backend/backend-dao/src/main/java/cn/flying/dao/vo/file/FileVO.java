package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * File view object for external API responses.
 * Excludes internal fields (uid, origin, tenantId, deleted, sharedFromUserId as raw Long).
 */
@Schema(description = "文件信息")
public record FileVO(
        @Schema(description = "文件ID（外部ID）")
        String id,
        @Schema(description = "文件名称")
        String fileName,
        @Schema(description = "文件分类")
        String classification,
        @Schema(description = "文件参数(JSON)")
        String fileParam,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "交易哈希")
        String transactionHash,
        @Schema(description = "文件上传状态")
        Integer status,
        @Schema(description = "文件大小（字节）")
        Long fileSize,
        @Schema(description = "文件类型")
        String contentType,
        @Schema(description = "版本号")
        Integer version,
        @Schema(description = "是否最新版本：1=是，0=否")
        Integer isLatest,
        @Schema(description = "版本链分组ID（外部ID）")
        String versionGroupId,
        @Schema(description = "上一版本文件ID（外部ID）")
        String parentVersionId,
        @Schema(description = "文件所有者用户名（分享场景）")
        String ownerName,
        @Schema(description = "原上传者用户名（来自分享保存场景）")
        String originOwnerName,
        @Schema(description = "直接分享者用户名")
        String sharedFromUserName,
        @Schema(description = "创建时间")
        Date createTime
) {
}
