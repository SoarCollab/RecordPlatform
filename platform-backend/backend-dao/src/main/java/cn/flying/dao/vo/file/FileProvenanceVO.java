package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 文件溯源信息 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文件溯源信息")
public class FileProvenanceVO {

    @Schema(description = "当前文件ID")
    private String fileId;

    @Schema(description = "文件哈希")
    private String fileHash;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "是否为原始文件（自己上传的）")
    private Boolean isOriginal;

    @Schema(description = "原始上传者用户ID")
    private String originUserId;

    @Schema(description = "原始上传者用户名")
    private String originUserName;

    @Schema(description = "直接分享者用户ID")
    private String sharedFromUserId;

    @Schema(description = "直接分享者用户名")
    private String sharedFromUserName;

    @Schema(description = "分享链路深度（0=原始文件，1=一次分享，2=二次分享...）")
    private Integer depth;

    @Schema(description = "保存时间")
    private Date saveTime;

    @Schema(description = "使用的分享码")
    private String shareCode;

    @Schema(description = "完整分享链路（从原始到当前）")
    private List<ProvenanceNode> chain;

    /**
     * 分享链路节点
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "分享链路节点")
    public static class ProvenanceNode {

        @Schema(description = "用户ID")
        private String userId;

        @Schema(description = "用户名")
        private String userName;

        @Schema(description = "文件ID")
        private String fileId;

        @Schema(description = "链路深度")
        private Integer depth;

        @Schema(description = "分享码（传递时使用的）")
        private String shareCode;

        @Schema(description = "获取时间")
        private Date time;
    }
}
