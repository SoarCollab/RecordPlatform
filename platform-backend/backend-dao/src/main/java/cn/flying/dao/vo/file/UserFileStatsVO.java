package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户文件统计信息 VO
 * 用于 Dashboard 展示
 *
 * @author flyingcoding
 * @since 2025-12-26
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "用户文件统计信息")
public class UserFileStatsVO {

    @Schema(description = "文件总数")
    private Long totalFiles;

    @Schema(description = "存储用量（字节）")
    private Long totalStorage;

    @Schema(description = "分享文件数")
    private Long sharedFiles;

    @Schema(description = "今日上传数")
    private Long todayUploads;
}
