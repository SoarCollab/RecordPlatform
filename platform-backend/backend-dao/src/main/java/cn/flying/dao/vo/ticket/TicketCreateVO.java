package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 工单创建 VO
 */
@Data
@Schema(description = "工单创建请求")
public class TicketCreateVO {

    @NotBlank(message = "工单标题不能为空")
    @Size(max = 200, message = "工单标题不能超过200个字符")
    @Schema(description = "工单标题")
    private String title;

    @NotBlank(message = "工单内容不能为空")
    @Schema(description = "工单内容")
    private String content;

    @Schema(description = "优先级: 0-低, 1-中, 2-高", defaultValue = "1")
    private Integer priority = 1;

    @Schema(description = "类别: 0-Bug, 1-功能请求, 2-问题咨询, 3-反馈建议, 99-其他", defaultValue = "99")
    private Integer category = 99;

    @Schema(description = "附件文件ID列表")
    private List<String> attachmentIds;
}
