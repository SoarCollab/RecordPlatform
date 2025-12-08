package cn.flying.dao.vo.announcement;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Date;

/**
 * 公告创建/编辑 VO
 */
@Data
@Schema(description = "公告创建/编辑请求")
public class AnnouncementCreateVO {

    @NotBlank(message = "公告标题不能为空")
    @Size(max = 200, message = "公告标题不能超过200个字符")
    @Schema(description = "公告标题")
    private String title;

    @NotBlank(message = "公告内容不能为空")
    @Schema(description = "公告内容(支持Markdown)")
    private String content;

    @Schema(description = "优先级: 0-普通, 1-重要, 2-紧急", defaultValue = "0")
    private Integer priority = 0;

    @Schema(description = "是否置顶: 0-否, 1-是", defaultValue = "0")
    private Integer isPinned = 0;

    @Schema(description = "发布时间(为空则立即发布)")
    private Date publishTime;

    @Schema(description = "过期时间(为空则永不过期)")
    private Date expireTime;

    @Schema(description = "状态: 0-草稿, 1-发布", defaultValue = "1")
    private Integer status = 1;
}
