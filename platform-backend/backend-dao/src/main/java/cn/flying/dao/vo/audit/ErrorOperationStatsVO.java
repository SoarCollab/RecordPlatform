package cn.flying.dao.vo.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 错误操作统计VO
 */
@Data
@Schema(description = "错误操作统计VO")
public class ErrorOperationStatsVO {
    
    /**
     * 操作模块
     */
    @Schema(description = "操作模块")
    private String module;
    
    /**
     * 操作类型
     */
    @Schema(description = "操作类型")
    private String operationType;
    
    /**
     * 错误信息
     */
    @Schema(description = "错误信息")
    private String errorMsg;
    
    /**
     * 错误次数
     */
    @Schema(description = "错误次数")
    private Integer errorCount;
    
    /**
     * 首次出现时间
     */
    @Schema(description = "首次出现时间")
    private LocalDateTime firstOccurrence;
    
    /**
     * 最后出现时间
     */
    @Schema(description = "最后出现时间")
    private LocalDateTime lastOccurrence;
} 