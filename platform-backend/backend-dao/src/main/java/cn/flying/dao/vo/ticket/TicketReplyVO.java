package cn.flying.dao.vo.ticket;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 工单回复 VO
 */
@Data
@Schema(description = "工单回复请求")
public class TicketReplyVO {

    @NotBlank(message = "回复内容不能为空")
    @Schema(description = "回复内容")
    private String content;

    @Schema(description = "是否内部备注(仅管理员可用)", defaultValue = "false")
    private Boolean isInternal = false;

    @Schema(description = "附件文件ID列表")
    private List<String> attachmentIds;
}
