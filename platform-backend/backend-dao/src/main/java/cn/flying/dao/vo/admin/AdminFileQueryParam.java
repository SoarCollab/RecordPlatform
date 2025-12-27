package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理员文件查询参数
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Schema(description = "管理员文件查询参数")
public class AdminFileQueryParam {

    @Schema(description = "关键词（搜索文件名、哈希）")
    private String keyword;

    @Schema(description = "文件状态：0-处理中，1-已完成，2-已删除，-1-失败")
    private Integer status;

    @Schema(description = "所有者用户ID")
    private String ownerId;

    @Schema(description = "所有者用户名（模糊匹配）")
    private String ownerName;

    @Schema(description = "是否仅显示原始文件（非分享保存的）")
    private Boolean originalOnly;

    @Schema(description = "是否仅显示分享保存的文件")
    private Boolean sharedOnly;

    @Schema(description = "开始时间")
    private String startTime;

    @Schema(description = "结束时间")
    private String endTime;
}
