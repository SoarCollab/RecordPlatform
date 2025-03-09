package cn.flying.dao.vo.audit;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 高频操作VO
 */
@Data
public class HighFrequencyOperationVO {
    
    /**
     * 用户ID
     */
    private String userId;
    
    /**
     * 用户名
     */
    private String username;
    
    /**
     * 请求IP
     */
    private String requestIp;
    
    /**
     * 操作次数
     */
    private Integer operationCount;
    
    /**
     * 开始时间
     */
    private LocalDateTime startTime;
    
    /**
     * 结束时间
     */
    private LocalDateTime endTime;
    
    /**
     * 时间跨度（秒）
     */
    private Integer timeSpanSeconds;
} 