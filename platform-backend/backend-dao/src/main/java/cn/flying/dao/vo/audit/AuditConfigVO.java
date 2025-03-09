package cn.flying.dao.vo.audit;

import lombok.Data;

/**
 * 审计配置VO
 */
@Data
public class AuditConfigVO {
    
    /**
     * 配置ID
     */
    private Integer id;
    
    /**
     * 配置键
     */
    private String configKey;
    
    /**
     * 配置值
     */
    private String configValue;
    
    /**
     * 配置描述
     */
    private String description;
    
    /**
     * 创建时间
     */
    private String createTime;
    
    /**
     * 更新时间
     */
    private String updateTime;
} 