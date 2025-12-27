package cn.flying.dao.vo.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

/**
 * 管理员分享列表 VO
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "管理员分享列表VO")
public class AdminShareVO {

    @Schema(description = "分享ID")
    private String id;

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "分享者用户ID")
    private String sharerId;

    @Schema(description = "分享者用户名")
    private String sharerName;

    @Schema(description = "分享类型：0-公开，1-私密")
    private Integer shareType;

    @Schema(description = "分享类型描述")
    private String shareTypeDesc;

    @Schema(description = "分享状态：0-已取消，1-有效，2-已过期")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "文件数量")
    private Integer fileCount;

    @Schema(description = "文件哈希列表")
    private List<String> fileHashes;

    @Schema(description = "文件名列表")
    private List<String> fileNames;

    @Schema(description = "访问次数")
    private Integer accessCount;

    @Schema(description = "最大访问次数限制")
    private Integer maxAccess;

    @Schema(description = "是否有密码保护")
    private Boolean hasPassword;

    @Schema(description = "创建时间")
    private Date createTime;

    @Schema(description = "过期时间")
    private Date expireTime;

    // ==================== 访问统计 ====================

    @Schema(description = "查看次数")
    private Long viewCount;

    @Schema(description = "下载次数")
    private Long downloadCount;

    @Schema(description = "保存次数")
    private Long saveCount;

    @Schema(description = "独立访问者数量")
    private Long uniqueActors;
}
