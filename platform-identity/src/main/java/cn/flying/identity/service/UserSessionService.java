package cn.flying.identity.service;

import cn.flying.identity.dto.UserSession;
import cn.flying.platformapi.constant.Result;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户会话管理服务接口
 * 提供用户会话的创建、查询、更新、失效等功能
 *
 * @author flying
 * @date 2025-01-16
 */
public interface UserSessionService {

    /**
     * 创建用户会话
     *
     * @param userId            用户ID
     * @param username          用户名
     * @param clientIp          客户端IP
     * @param userAgent         用户代理
     * @param deviceFingerprint 设备指纹
     * @param location          地理位置
     * @return 创建的会话信息
     */
    Result<UserSession> createSession(Long userId, String username, String clientIp,
                                      String userAgent, String deviceFingerprint, String location);

    /**
     * 根据会话ID查找会话
     *
     * @param sessionId 会话ID
     * @return 会话信息
     */
    Result<UserSession> findBySessionId(String sessionId);

    /**
     * 根据用户ID查找活跃会话列表
     *
     * @param userId 用户ID
     * @return 活跃会话列表
     */
    Result<List<UserSession>> findActiveSessionsByUserId(Long userId);

    /**
     * 根据用户ID和设备指纹查找会话
     *
     * @param userId            用户ID
     * @param deviceFingerprint 设备指纹
     * @return 会话信息
     */
    Result<UserSession> findByUserIdAndDeviceFingerprint(Long userId, String deviceFingerprint);

    /**
     * 更新会话最后访问时间
     *
     * @param sessionId 会话ID
     * @return 更新结果
     */
    Result<Void> updateLastAccessTime(String sessionId);

    /**
     * 更新会话状态
     *
     * @param sessionId 会话ID
     * @param status    状态（0-失效，1-有效）
     * @return 更新结果
     */
    Result<Void> updateSessionStatus(String sessionId, Integer status);

    /**
     * 注销单个会话
     *
     * @param sessionId 会话ID
     * @param reason    注销原因
     * @return 注销结果
     */
    Result<Void> logoutSession(String sessionId, UserSession.LogoutReason reason);

    /**
     * 注销用户的所有会话
     *
     * @param userId 用户ID
     * @param reason 注销原因
     * @return 注销结果
     */
    Result<Void> logoutAllUserSessions(Long userId, UserSession.LogoutReason reason);

    /**
     * 清理过期的会话记录
     *
     * @param beforeTime 过期时间点
     * @return 清理的记录数
     */
    Result<Integer> cleanExpiredSessions(LocalDateTime beforeTime);

    /**
     * 检查会话是否有效
     *
     * @param sessionId 会话ID
     * @return 是否有效
     */
    Result<Boolean> isSessionValid(String sessionId);

    /**
     * 延长会话有效期
     *
     * @param sessionId  会话ID
     * @param expireTime 新的过期时间
     * @return 更新结果
     */
    Result<Void> extendSession(String sessionId, LocalDateTime expireTime);

    /**
     * 根据时间范围统计用户会话数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 会话统计信息
     */
    Result<List<Map<String, Object>>> countSessionsByTimeRange(LocalDateTime startTime, LocalDateTime endTime);

    /**
     * 根据客户端IP统计会话数量
     *
     * @param startTime 开始时间
     * @param endTime   结束时间
     * @return 按IP分组的会话统计
     */
    Result<List<Map<String, Object>>> countSessionsByClientIp(LocalDateTime startTime, LocalDateTime endTime);
}
