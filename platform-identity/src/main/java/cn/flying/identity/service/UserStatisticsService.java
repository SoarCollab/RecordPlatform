package cn.flying.identity.service;

import cn.flying.platformapi.constant.Result;

import java.util.Map;

/**
 * 用户统计服务接口
 * 提供用户相关的统计分析功能
 * 
 * @author 王贝强
 */
public interface UserStatisticsService {
    
    /**
     * 获取用户总数统计
     * 
     * @return 用户总数统计
     */
    Result<Map<String, Object>> getUserCountStats();
    
    /**
     * 获取用户注册趋势
     * 
     * @param days 统计天数
     * @return 注册趋势数据
     */
    Result<Map<String, Object>> getRegistrationTrend(int days);
    
    /**
     * 获取用户活跃度统计
     * 
     * @param days 统计天数
     * @return 活跃度统计
     */
    Result<Map<String, Object>> getUserActivityStats(int days);
    
    /**
     * 获取用户角色分布
     * 
     * @return 角色分布统计
     */
    Result<Map<String, Object>> getUserRoleDistribution();
    
    /**
     * 获取用户地理分布
     * 
     * @return 地理分布统计
     */
    Result<Map<String, Object>> getUserGeographicDistribution();
    
    /**
     * 获取用户登录统计
     * 
     * @param days 统计天数
     * @return 登录统计
     */
    Result<Map<String, Object>> getUserLoginStats(int days);
    
    /**
     * 获取用户留存率
     * 
     * @param days 统计天数
     * @return 留存率统计
     */
    Result<Map<String, Object>> getUserRetentionRate(int days);
    
    /**
     * 获取用户增长率
     * 
     * @param days 统计天数
     * @return 增长率统计
     */
    Result<Map<String, Object>> getUserGrowthRate(int days);
    
    /**
     * 获取用户行为统计
     * 
     * @param userId 用户ID
     * @param days 统计天数
     * @return 行为统计
     */
    Result<Map<String, Object>> getUserBehaviorStats(Long userId, int days);
    
    /**
     * 获取用户设备统计
     * 
     * @param days 统计天数
     * @return 设备统计
     */
    Result<Map<String, Object>> getUserDeviceStats(int days);
}
