package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 用户文件统计信息 VO
 * 用于 Dashboard 展示
 *
 * @author flyingcoding
 * @since 2025-12-26
 */
@Schema(description = "用户文件统计信息")
public record UserFileStatsVO(
        @Schema(description = "文件总数")
        Long totalFiles,
        @Schema(description = "存储用量（字节）")
        Long totalStorage,
        @Schema(description = "分享文件数")
        Long sharedFiles,
        @Schema(description = "今日上传数")
        Long todayUploads
) {

    public Long getTotalFiles() {
        return totalFiles;
    }

    public Long getTotalStorage() {
        return totalStorage;
    }

    public Long getSharedFiles() {
        return sharedFiles;
    }

    public Long getTodayUploads() {
        return todayUploads;
    }
}
