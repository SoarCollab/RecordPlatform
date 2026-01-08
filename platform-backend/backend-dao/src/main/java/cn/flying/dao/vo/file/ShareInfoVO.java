package cn.flying.dao.vo.file;

import cn.flying.dao.dto.File;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.List;

/**
 * 分享详情 VO（用于分享页展示）
 *
 * @author flyingcoding
 */
@Getter
@Setter
@Schema(description = "分享详情")
public class ShareInfoVO {

    @Schema(description = "分享码")
    private String shareCode;

    @Schema(description = "分享类型：0-公开，1-私密")
    private Integer shareType;

    @Schema(description = "过期时间")
    private Date expireTime;

    @Schema(description = "分享文件列表")
    private List<File> files;
}

