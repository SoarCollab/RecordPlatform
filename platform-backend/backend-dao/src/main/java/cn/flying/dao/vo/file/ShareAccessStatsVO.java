package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 分享访问统计 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Schema(description = "分享访问统计")
public record ShareAccessStatsVO(
        @Schema(description = "分享码")
        String shareCode,
        @Schema(description = "查看次数")
        Long viewCount,
        @Schema(description = "下载次数")
        Long downloadCount,
        @Schema(description = "保存次数")
        Long saveCount,
        @Schema(description = "独立访问用户数")
        Long uniqueActors,
        @Schema(description = "总访问次数")
        Long totalAccess
) {

    public String getShareCode() {
        return shareCode;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public Long getDownloadCount() {
        return downloadCount;
    }

    public Long getSaveCount() {
        return saveCount;
    }

    public Long getUniqueActors() {
        return uniqueActors;
    }

    public Long getTotalAccess() {
        return totalAccess;
    }
}
