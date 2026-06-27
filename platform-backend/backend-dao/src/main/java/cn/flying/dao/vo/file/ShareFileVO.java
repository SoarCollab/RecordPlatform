package cn.flying.dao.vo.file;

import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * 分享文件展示响应。
 * <p>
 * 仅包含分享页展示和后续下载定位所需字段，不暴露 File.fileParam、
 * transactionHash 或内部数值 ID，避免泄露解密密钥和存储元数据。
 * </p>
 */
@Schema(description = "分享文件展示响应")
public record ShareFileVO(
        @Schema(description = "文件外部ID")
        String id,
        @Schema(description = "文件名称")
        String fileName,
        @Schema(description = "文件分类")
        String classification,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "文件大小")
        Long fileSize,
        @Schema(description = "文件MIME类型")
        String contentType,
        @Schema(description = "文件所有者用户名")
        String ownerName,
        @Schema(description = "原上传者用户名")
        String originOwnerName,
        @Schema(description = "直接分享者用户名")
        String sharedFromUserName,
        @Schema(description = "创建时间")
        Date createTime
) {

    /**
     * 将文件实体转换为分享页安全展示对象。
     *
     * @param file 文件实体
     * @return 不含敏感存储元数据的分享文件响应
     */
    public static ShareFileVO fromFile(File file) {
        if (file == null) {
            return null;
        }
        return new ShareFileVO(
                IdUtils.toExternalId(file.getId()),
                file.getFileName(),
                file.getClassification(),
                file.getFileHash(),
                file.getFileSize(),
                file.getContentType(),
                file.getOwnerName(),
                file.getOriginOwnerName(),
                file.getSharedFromUserName(),
                file.getCreateTime()
        );
    }
}
