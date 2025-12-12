package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 系统统计信息 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "系统统计信息")
public class SystemStatsVO {

    @Schema(description = "总用户数")
    private Long totalUsers;

    @Schema(description = "总文件数")
    private Long totalFiles;

    @Schema(description = "总存储容量（字节）")
    private Long totalStorage;

    @Schema(description = "链上交易总数")
    private Long totalTransactions;

    @Schema(description = "今日上传文件数")
    private Long todayUploads;

    @Schema(description = "今日下载次数")
    private Long todayDownloads;
}
