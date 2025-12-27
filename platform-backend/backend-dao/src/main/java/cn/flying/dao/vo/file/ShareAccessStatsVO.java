package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分享访问统计 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分享访问统计")
public class ShareAccessStatsVO {

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "查看次数")
    private Long viewCount;

    @Schema(description = "下载次数")
    private Long downloadCount;

    @Schema(description = "保存次数")
    private Long saveCount;

    @Schema(description = "独立访问用户数")
    private Long uniqueActors;

    @Schema(description = "总访问次数")
    private Long totalAccess;
}
