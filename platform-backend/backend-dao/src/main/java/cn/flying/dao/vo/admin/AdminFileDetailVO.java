package cn.flying.dao.vo.admin;

import cn.flying.dao.vo.file.FileProvenanceVO.ProvenanceNode;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 管理员文件详情 VO（含完整审计信息）
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员文件详情VO")
public class AdminFileDetailVO {

    // ==================== 基本信息 ====================

    @Schema(description = "文件ID（外部ID）")
    private String id;

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "文件哈希")
    private String fileHash;

    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    @Schema(description = "文件类型")
    private String contentType;

    @Schema(description = "文件状态：0-处理中，1-已完成，2-已删除，-1-失败")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "更新时间")
    private Date updateTime;

    // ==================== 所有权信息 ====================

    @Schema(description = "当前所有者用户ID")
    private String ownerId;

    @Schema(description = "当前所有者用户名")
    private String ownerName;

    @Schema(description = "原始上传者用户ID")
    private String originOwnerId;

    @Schema(description = "原始上传者用户名")
    private String originOwnerName;

    @Schema(description = "直接分享者用户ID")
    private String sharedFromUserId;

    @Schema(description = "直接分享者用户名")
    private String sharedFromUserName;

    // ==================== 分享链路信息 ====================

    @Schema(description = "是否为原始文件（自己上传的）")
    private Boolean isOriginal;

    @Schema(description = "分享链路深度（0=原始文件）")
    private Integer depth;

    @Schema(description = "保存时使用的分享码")
    private String saveShareCode;

    @Schema(description = "完整分享链路")
    private List<ProvenanceNode> provenanceChain;

    // ==================== 区块链信息 ====================

    @Schema(description = "区块链交易哈希")
    private String transactionHash;

    @Schema(description = "区块号")
    private Long blockNumber;

    // ==================== 统计信息 ====================

    @Schema(description = "引用计数（被分享保存的次数）")
    private Integer refCount;

    @Schema(description = "相关分享列表")
    private List<RelatedShare> relatedShares;

    @Schema(description = "最近访问日志")
    private List<ShareAccessLogVO> recentAccessLogs;

    /**
     * 相关分享信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "相关分享信息")
    public static class RelatedShare {
        @Schema(description = "分享码")
        private String shareCode;

        @Schema(description = "分享者用户名")
        private String sharerName;

        @Schema(description = "分享类型：0-公开，1-私密")
        private Integer shareType;

        @Schema(description = "分享状态：0-已取消，1-有效，2-已过期")
        private Integer status;

        @Schema(description = "创建时间")
        private Date createTime;

        @Schema(description = "过期时间")
        private Date expireTime;

        @Schema(description = "访问次数")
        private Integer accessCount;
    }
}
