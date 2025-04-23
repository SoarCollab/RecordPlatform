package cn.flying.dao.vo.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 审计配置VO
 */
@Data
@Schema(description = "审计配置VO")
public class AuditConfigVO {
    
    /**
     * 配置ID
     */
    @Schema(description = "配置ID")
    private Integer id;
    
    /**
     * 配置键
     */
    @Schema(description = "配置键")
    private String configKey;
    
    /**
     * 配置值
     */
    @Schema(description = "配置值")
    private String configValue;
    
    /**
     * 配置描述
     */
    @Schema(description = "配置描述")
    private String description;
    
    /**
     * 创建时间
     */
    @Schema(description = "创建时间")
    private String createTime;
    
    /**
     * 更新时间
     */
    @Schema(description = "更新时间")
    private String updateTime;
} 