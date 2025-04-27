package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 文件分享VO
 * @author: flyingcoding
 * @create: 2025-04-27 19:19
 */
@Getter
@Setter
@Schema(description = "文件分享VO")
public class fileSharingVO {
    @Schema(description = "待分享文件Hash列表")
    List<String> fileHash;
    @Schema(description = "分享码最大访问次数")
    Integer maxAccesses;
}
