package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Date;
import java.util.List;

/**
 * 文件溯源信息 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Schema(description = "文件溯源信息")
public record FileProvenanceVO(
        @Schema(description = "当前文件ID")
        String fileId,
        @Schema(description = "文件哈希")
        String fileHash,
        @Schema(description = "文件名")
        String fileName,
        @Schema(description = "是否为原始文件（自己上传的）")
        Boolean isOriginal,
        @Schema(description = "原始上传者用户ID")
        String originUserId,
        @Schema(description = "原始上传者用户名")
        String originUserName,
        @Schema(description = "直接分享者用户ID")
        String sharedFromUserId,
        @Schema(description = "直接分享者用户名")
        String sharedFromUserName,
        @Schema(description = "分享链路深度（0=原始文件，1=一次分享，2=二次分享...）")
        Integer depth,
        @Schema(description = "保存时间")
        Date saveTime,
        @Schema(description = "使用的分享码")
        String shareCode,
        @Schema(description = "完整分享链路（从原始到当前）")
        List<ProvenanceNode> chain
) {

    public String getFileId() {
        return fileId;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getFileName() {
        return fileName;
    }

    public Boolean getIsOriginal() {
        return isOriginal;
    }

    public String getOriginUserId() {
        return originUserId;
    }

    public String getOriginUserName() {
        return originUserName;
    }

    public String getSharedFromUserId() {
        return sharedFromUserId;
    }

    public String getSharedFromUserName() {
        return sharedFromUserName;
    }

    public Integer getDepth() {
        return depth;
    }

    public Date getSaveTime() {
        return saveTime;
    }

    public String getShareCode() {
        return shareCode;
    }

    public List<ProvenanceNode> getChain() {
        return chain;
    }

    /**
     * 分享链路节点
     */
    @Schema(description = "分享链路节点")
    public record ProvenanceNode(
            @Schema(description = "用户ID")
            String userId,
            @Schema(description = "用户名")
            String userName,
            @Schema(description = "文件ID")
            String fileId,
            @Schema(description = "链路深度")
            Integer depth,
            @Schema(description = "分享码（传递时使用的）")
            String shareCode,
            @Schema(description = "获取时间")
            Date time
    ) {

        public String getUserId() {
            return userId;
        }

        public String getUserName() {
            return userName;
        }

        public String getFileId() {
            return fileId;
        }

        public Integer getDepth() {
            return depth;
        }

        public String getShareCode() {
            return shareCode;
        }

        public Date getTime() {
            return time;
        }
    }
}
