package cn.flying.dao.vo.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 高频操作VO
 */
@Data
@Schema(description = "高频操作VO")
public class HighFrequencyOperationVO {
    
    /**
     * 用户ID
     */
    @Schema(description = "用户ID")
    private String userId;
    
    /**
     * 用户名
     */
    @Schema(description = "用户名")
    private String username;
    
    /**
     * 请求IP
     */
    @Schema(description = "请求IP")
    private String requestIp;
    
    /**
     * 操作次数
     */
    @Schema(description = "操作次数")
    private Integer operationCount;
    
    /**
     * 开始时间
     */
    @Schema(description = "开始时间")
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    @Schema(description = "结束时间")
    private LocalDateTime endTime;
    
    /**
     * 时间跨度（秒）
     */
    @Schema(description = "时间跨度（秒）")
    private Integer timeSpanSeconds;
} 