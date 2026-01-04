package cn.flying.dao.vo.friend;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新好友备注 VO
 */
@Data
@Schema(description = "更新好友备注")
public class UpdateRemarkVO {

    @Size(max = 50, message = "备注不能超过50个字符")
    @Schema(description = "备注")
    private String remark;
}
