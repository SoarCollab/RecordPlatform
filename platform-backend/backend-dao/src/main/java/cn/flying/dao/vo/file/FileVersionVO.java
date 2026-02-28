package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

@Schema(description = "文件版本信息")
public record FileVersionVO(
        @Schema(description = "文件ID（外部ID）")
        String fileId,
        @Schema(description = "版本号")
        Integer version,
        @Schema(description = "文件名称")
        String fileName,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "文件大小（字节）")
        Long fileSize,
        @Schema(description = "文件类型")
        String contentType,
        @Schema(description = "是否最新版本")
        Integer isLatest,
        @Schema(description = "文件状态")
        Integer status,
        @Schema(description = "创建时间")
        Date createTime
) {
}
