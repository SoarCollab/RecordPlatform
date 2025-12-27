package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 管理员分享查询参数
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Schema(description = "管理员分享查询参数")
public class AdminShareQueryParam {

    @Schema(description = "关键词（搜索分享码、文件名）")
    private String keyword;

    @Schema(description = "分享状态：0-已取消，1-有效，2-已过期")
    private Integer status;

    @Schema(description = "分享类型：0-公开，1-私密")
    private Integer shareType;

    @Schema(description = "分享者用户ID")
    private String sharerId;

    @Schema(description = "分享者用户名（模糊匹配）")
    private String sharerName;

    @Schema(description = "开始时间")
    private String startTime;

    @Schema(description = "结束时间")
    private String endTime;
}
