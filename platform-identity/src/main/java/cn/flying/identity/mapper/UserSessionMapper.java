package cn.flying.identity.mapper;

import cn.flying.identity.dto.UserSession;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户会话数据访问层
 * 提供用户会话信息的数据库操作接口
 * 
 * @author 王贝强
 */
@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {

    /**
     * 根据用户ID查找活跃的会话列表
     * 
     * @param userId 用户ID
     * @return 活跃会话列表
     */
    List<UserSession> findActiveSessionsByUserId(@Param("userId") Long userId);

    /**
     * 根据会话ID和用户ID查找会话
     * 
     * @param sessionId 会话ID
     * @param userId 用户ID
     * @return 用户会话信息
     */
    UserSession findBySessionIdAndUserId(@Param("sessionId") String sessionId, @Param("userId") Long userId);

    /**
     * 根据用户ID和设备指纹查找会话
     * 
     * @param userId 用户ID
     * @param deviceId 设备指纹
     * @return 用户会话信息
     */
    UserSession findByUserIdAndDeviceId(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    /**
     * 更新会话状态
     * 
     * @param sessionId 会话ID
     * @param status 新状态（0-失效，1-有效）
     * @return 影响的记录数
     */
    int updateSessionStatus(@Param("sessionId") String sessionId, @Param("status") Integer status);

    /**
     * 更新会话的最后活跃时间
     * 
     * @param sessionId 会话ID
     * @param lastActiveTime 最后活跃时间
     * @return 影响的记录数
     */
    int updateLastActiveTime(@Param("sessionId") String sessionId, @Param("lastActiveTime") LocalDateTime lastActiveTime);

    /**
     * 批量过期指定用户的所有会话
     * 
     * @param userId 用户ID
     * @return 影响的记录数
     */
    int expireAllUserSessions(@Param("userId") Long userId);

    /**
     * 清理过期的会话记录
     * 
     * @param beforeTime 过期时间点
     * @return 影响的记录数
     */
    int cleanExpiredSessions(@Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 根据时间范围统计用户会话数量
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 会话统计信息列表
     */
    List<java.util.Map<String, Object>> countSessionsByTimeRange(@Param("startTime") LocalDateTime startTime, 
                                                                 @Param("endTime") LocalDateTime endTime);

    /**
     * 根据登录类型统计会话数量
     * 
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @return 按登录类型分组的会话统计
     */
    List<java.util.Map<String, Object>> countSessionsByLoginType(@Param("startTime") LocalDateTime startTime, 
                                                                 @Param("endTime") LocalDateTime endTime);
}
