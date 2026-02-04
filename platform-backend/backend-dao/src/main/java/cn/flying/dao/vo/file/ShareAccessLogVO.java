package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;

/**
 * 分享访问日志 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Schema(description = "分享访问日志")
public record ShareAccessLogVO(
        @Schema(description = "日志ID")
        String id,
        @Schema(description = "分享码")
        String shareCode,
        @Schema(description = "操作类型：1=查看，2=下载，3=保存")
        Integer actionType,
        @Schema(description = "操作类型描述")
        String actionTypeDesc,
        @Schema(description = "操作者用户ID")
        String actorUserId,
        @Schema(description = "操作者用户名（匿名为'匿名用户'）")
        String actorUserName,
        @Schema(description = "操作者IP")
        String actorIp,
        @Schema(description = "文件哈希（下载/保存时）")
        String fileHash,
        @Schema(description = "文件名（下载/保存时）")
        String fileName,
        @Schema(description = "访问时间")
        Date accessTime
) {

    public String getId() {
        return id;
    }

    public String getShareCode() {
        return shareCode;
    }

    public Integer getActionType() {
        return actionType;
    }

    public String getActionTypeDesc() {
        return actionTypeDesc;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getActorUserName() {
        return actorUserName;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public Date getAccessTime() {
        return accessTime;
    }

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
