package cn.flying.dao.vo.system;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * 系统健康状态 VO
 */
@Schema(description = "系统健康状态")
public record SystemHealthVO(
        @Schema(description = "总体状态", example = "UP")
        String status,
        @Schema(description = "各组件健康状态")
        Map<String, ComponentHealthVO> components,
        @Schema(description = "系统运行时长（秒）")
        Long uptime,
        @Schema(description = "检查时间戳")
        String timestamp
) {

    public String getStatus() {
        return status;
    }

    public Map<String, ComponentHealthVO> getComponents() {
        return components;
    }

    public Long getUptime() {
        return uptime;
    }

    public String getTimestamp() {
        return timestamp;
    }
}
