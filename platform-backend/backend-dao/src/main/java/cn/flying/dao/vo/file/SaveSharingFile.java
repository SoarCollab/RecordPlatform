package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 保存分享文件列表
 * @author flyingcoding
 * @create: 2025-04-27 23:31
 */
@Getter
@Setter
@Schema(description = "保存分享文件VO")
public class SaveSharingFile {
    @NotEmpty(message = "分享文件ID列表不能为空")
    @Schema(description = "分享文件ID列表")
    List<String> sharingFileIdList;
}
