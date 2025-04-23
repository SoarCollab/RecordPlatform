package cn.flying.dao.vo.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 用户操作时间分布VO
 */
@Data
@Schema(description = "用户操作时间分布VO")
public class UserTimeDistributionVO {
    
    /**
     * 小时（0-23）
     */
    @Schema(description = "小时（0-23）")
    private Integer hourOfDay;
    
    /**
     * 星期几（0-6，0代表周一）
     */
    @Schema(description = "星期几（0-6，0代表周一）")
    private Integer dayOfWeek;
    
    /**
     * 操作次数
     */
    @Schema(description = "操作次数")
    private Integer operationCount;
} 