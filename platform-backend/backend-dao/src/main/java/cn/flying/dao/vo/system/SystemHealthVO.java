package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 系统健康状态 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "系统健康状态")
public class SystemHealthVO {

    @Schema(description = "总体状态", example = "UP")
    private String status;

    @Schema(description = "各组件健康状态")
    private Map<String, ComponentHealthVO> components;

    @Schema(description = "系统运行时长（秒）")
    private Long uptime;

    @Schema(description = "检查时间戳")
    private String timestamp;
}
