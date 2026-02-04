package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 组件健康状态 VO
 */
@Schema(description = "组件健康状态")
public record ComponentHealthVO(
        @Schema(description = "健康状态", example = "UP")
        String status,
        @Schema(description = "详细信息")
        Map<String, Object> details
) {

    public String getStatus() {
        return status;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
