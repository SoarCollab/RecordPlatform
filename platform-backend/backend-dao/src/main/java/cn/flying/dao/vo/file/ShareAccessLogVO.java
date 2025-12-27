package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 分享访问日志 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "分享访问日志")
public class ShareAccessLogVO {

    @Schema(description = "日志ID")
    private String id;

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "操作类型：1=查看，2=下载，3=保存")
    private Integer actionType;

    @Schema(description = "操作类型描述")
    private String actionTypeDesc;

    @Schema(description = "操作者用户ID")
    private String actorUserId;

    @Schema(description = "操作者用户名（匿名为'匿名用户'）")
    private String actorUserName;

    @Schema(description = "操作者IP")
    private String actorIp;

    @Schema(description = "文件哈希（下载/保存时）")
    private String fileHash;

    @Schema(description = "文件名（下载/保存时）")
    private String fileName;

    @Schema(description = "访问时间")
    private Date accessTime;

    /**
     * 获取操作类型描述
     */
    public static String getActionTypeDesc(Integer actionType) {
        if (actionType == null) return "未知";
        return switch (actionType) {
            case 1 -> "查看";
            case 2 -> "下载";
            case 3 -> "保存";
            default -> "未知";
        };
    }
}
