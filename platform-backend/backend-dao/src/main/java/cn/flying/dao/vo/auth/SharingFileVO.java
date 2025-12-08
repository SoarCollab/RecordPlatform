package cn.flying.dao.vo.auth;

import cn.flying.dao.dto.File;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * 文件分享视图对象
 */
@Getter
@Setter
@Schema(description = "文件分享视图对象")
public class SharingFileVO {
    @Schema(description = "分享用户ID（外部ID）")
    private String sharingUserId;

    @Schema(description = "分享用户名")
    private String sharingUsername;

    @Schema(description = "分享文件列表")
    private List<File> fileList;

}
