package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 系统统计信息 VO
 */
@Schema(description = "系统统计信息")
public record SystemStatsVO(
        @Schema(description = "总用户数")
        Long totalUsers,
        @Schema(description = "总文件数")
        Long totalFiles,
        @Schema(description = "总存储容量（字节）")
        Long totalStorage,
        @Schema(description = "链上交易总数")
        Long totalTransactions,
        @Schema(description = "今日上传文件数")
        Long todayUploads,
        @Schema(description = "今日下载次数")
        Long todayDownloads
) {

    public Long getTotalUsers() {
        return totalUsers;
    }

    public Long getTotalFiles() {
        return totalFiles;
    }

    public Long getTotalStorage() {
        return totalStorage;
    }

    public Long getTotalTransactions() {
        return totalTransactions;
    }

    public Long getTodayUploads() {
        return todayUploads;
    }

    public Long getTodayDownloads() {
        return todayDownloads;
    }
}
