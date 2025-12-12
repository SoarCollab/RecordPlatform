package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 组件健康状态 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "组件健康状态")
public class ComponentHealthVO {

    @Schema(description = "健康状态", example = "UP")
    private String status;

    @Schema(description = "详细信息")
    private Map<String, Object> details;
}
