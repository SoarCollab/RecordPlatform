package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.Date;
import java.util.List;

/**
 * 分享记录响应 VO
 */
@Getter
@Setter
@Accessors(chain = true)
@Schema(description = "分享记录响应VO")
public class FileShareVO {

    @Schema(description = "分享记录ID（外部ID）")
    private String id;

    @Schema(description = "分享码")
    private String sharingCode;

    @Schema(description = "分享的文件哈希列表")
    private List<String> fileHashes;

    @Schema(description = "分享的文件名列表")
    private List<String> fileNames;

    @Schema(description = "最大访问次数（NULL表示无限制）")
    private Integer maxAccesses;

    @Schema(description = "已访问次数")
    private Integer accessCount;

    @Schema(description = "是否设置了密码")
    private Boolean hasPassword;

    @Schema(description = "过期时间")
    private Date expireTime;

    @Schema(description = "状态：0-已取消，1-有效，2-已过期")
    private Integer status;

    @Schema(description = "状态描述")
    private String statusDesc;

    @Schema(description = "是否有效（未被取消）")
    private Boolean isValid;

    @Schema(description = "分享类型：0-公开，1-私密")
    private Integer shareType;

    @Schema(description = "分享类型描述")
    private String shareTypeDesc;

    @Schema(description = "创建时间")
    private Date createTime;
}
