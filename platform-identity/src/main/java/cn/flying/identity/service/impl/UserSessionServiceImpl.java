package cn.flying.identity.service.impl;

import cn.flying.identity.dto.UserSession;
import cn.flying.identity.mapper.UserSessionMapper;
import cn.flying.identity.service.UserSessionService;
import cn.flying.platformapi.constant.Result;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用户会话管理服务实现类
 * 实现用户会话的创建、查询、更新、失效等具体业务逻辑
 *
 * @author flying
 * @date 2025-01-16
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserSessionServiceImpl extends ServiceImpl<UserSessionMapper, UserSession> implements UserSessionService {

    private final UserSessionMapper userSessionMapper;

    /**
     * 创建用户会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<UserSession> createSession(Long userId, String username, String clientIp,
                                             String userAgent, String deviceFingerprint, String location) {
        try {
            // 生成唯一的会话ID
            String sessionId = UUID.randomUUID().toString().replace("-", "");

            // 创建会话对象
            UserSession session = new UserSession();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setUsername(username);
            session.setClientIp(clientIp);
            session.setUserAgent(userAgent);
            session.setDeviceFingerprint(deviceFingerprint);
            session.setLocation(location);
            session.setLoginTime(LocalDateTime.now());
            session.setLastAccessTime(LocalDateTime.now());
            session.setExpireTime(LocalDateTime.now().plusHours(24)); // 默认24小时有效期
            session.setStatus(UserSession.Status.VALID.getCode());

            // 保存到数据库
            boolean saved = this.save(session);
            if (saved) {
                log.info("用户会话创建成功：userId={}, sessionId={}, clientIp={}", userId, sessionId, clientIp);
                return Result.success(session);
            } else {
                log.error("用户会话创建失败：userId={}, clientIp={}", userId, clientIp);
                return new Result<>(500, "创建会话失败", null);
            }
        } catch (Exception e) {
            log.error("创建用户会话异常：userId={}, clientIp={}", userId, clientIp, e);
            return new Result<>(500, "创建会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 根据会话ID查找会话
     */
    @Override
    public Result<UserSession> findBySessionId(String sessionId) {
        try {
            UserSession session = this.lambdaQuery()
                    .eq(UserSession::getSessionId, sessionId)
                    .one();

            if (session != null) {
                return Result.success(session);
            } else {
                return new Result<>(404, "会话不存在", null);
            }
        } catch (Exception e) {
            log.error("查询会话异常：sessionId={}", sessionId, e);
            return new Result<>(500, "查询会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 根据用户ID查找活跃会话列表
     */
    @Override
    public Result<List<UserSession>> findActiveSessionsByUserId(Long userId) {
        try {
            List<UserSession> sessions = userSessionMapper.findActiveSessionsByUserId(userId);
            return Result.success(sessions);
        } catch (Exception e) {
            log.error("查询用户活跃会话异常：userId={}", userId, e);
            return new Result<>(500, "查询活跃会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 根据用户ID和设备指纹查找会话
     */
    @Override
    public Result<UserSession> findByUserIdAndDeviceFingerprint(Long userId, String deviceFingerprint) {
        try {
            UserSession session = userSessionMapper.findByUserIdAndDeviceId(userId, deviceFingerprint);
            if (session != null) {
                return Result.success(session);
            } else {
                return new Result<>(404, "未找到匹配的会话", null);
            }
        } catch (Exception e) {
            log.error("根据用户ID和设备指纹查找会话异常：userId={}, deviceFingerprint={}", userId, deviceFingerprint, e);
            return new Result<>(500, "查询会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 更新会话最后访问时间
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateLastAccessTime(String sessionId) {
        try {
            int updated = userSessionMapper.updateLastActiveTime(sessionId, LocalDateTime.now());
            if (updated > 0) {
                return Result.success();
            } else {
                return new Result<>(500, "更新会话访问时间失败", null);
            }
        } catch (Exception e) {
            log.error("更新会话访问时间异常：sessionId={}", sessionId, e);
            return new Result<>(500, "更新会话访问时间异常：" + e.getMessage(), null);
        }
    }

    /**
     * 更新会话状态
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> updateSessionStatus(String sessionId, Integer status) {
        try {
            int updated = userSessionMapper.updateSessionStatus(sessionId, status);
            if (updated > 0) {
                log.info("会话状态更新成功：sessionId={}, status={}", sessionId, status);
                return Result.success();
            } else {
                return new Result<>(500, "更新会话状态失败", null);
            }
        } catch (Exception e) {
            log.error("更新会话状态异常：sessionId={}, status={}", sessionId, status, e);
            return new Result<>(500, "更新会话状态异常：" + e.getMessage(), null);
        }
    }

    /**
     * 注销单个会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> logoutSession(String sessionId, UserSession.LogoutReason reason) {
        try {
            UserSession session = this.lambdaQuery()
                    .eq(UserSession::getSessionId, sessionId)
                    .one();

            if (session != null) {
                session.logout(reason);
                boolean updated = this.updateById(session);
                if (updated) {
                    log.info("会话注销成功：sessionId={}, reason={}", sessionId, reason.getCode());
                    return Result.success();
                } else {
                    return new Result<>(500, "注销会话失败", null);
                }
            } else {
                return new Result<>(404, "会话不存在", null);
            }
        } catch (Exception e) {
            log.error("注销会话异常：sessionId={}, reason={}", sessionId, reason.getCode(), e);
            return new Result<>(500, "注销会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 注销用户的所有会话
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> logoutAllUserSessions(Long userId, UserSession.LogoutReason reason) {
        try {
            int updated = userSessionMapper.expireAllUserSessions(userId);
            log.info("用户所有会话注销成功：userId={}, reason={}, count={}", userId, reason.getCode(), updated);
            return Result.success();
        } catch (Exception e) {
            log.error("注销用户所有会话异常：userId={}, reason={}", userId, reason.getCode(), e);
            return new Result<>(500, "注销所有会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 清理过期的会话记录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Integer> cleanExpiredSessions(LocalDateTime beforeTime) {
        try {
            int deleted = userSessionMapper.cleanExpiredSessions(beforeTime);
            log.info("清理过期会话完成：beforeTime={}, count={}", beforeTime, deleted);
            return Result.success(deleted);
        } catch (Exception e) {
            log.error("清理过期会话异常：beforeTime={}", beforeTime, e);
            return new Result<>(500, "清理过期会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 检查会话是否有效
     */
    @Override
    public Result<Boolean> isSessionValid(String sessionId) {
        try {
            UserSession session = this.lambdaQuery()
                    .eq(UserSession::getSessionId, sessionId)
                    .one();

            if (session != null) {
                boolean isValid = session.isValid();
                return Result.success(isValid);
            } else {
                return Result.success(false);
            }
        } catch (Exception e) {
            log.error("检查会话有效性异常：sessionId={}", sessionId, e);
            return new Result<>(500, "检查会话有效性异常：" + e.getMessage(), null);
        }
    }

    /**
     * 延长会话有效期
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Void> extendSession(String sessionId, LocalDateTime expireTime) {
        try {
            boolean updated = this.lambdaUpdate()
                    .eq(UserSession::getSessionId, sessionId)
                    .set(UserSession::getExpireTime, expireTime)
                    .set(UserSession::getLastAccessTime, LocalDateTime.now())
                    .update();

            if (updated) {
                log.info("会话有效期延长成功：sessionId={}, newExpireTime={}", sessionId, expireTime);
                return Result.success();
            } else {
                return new Result<>(500, "延长会话有效期失败", null);
            }
        } catch (Exception e) {
            log.error("延长会话有效期异常：sessionId={}, expireTime={}", sessionId, expireTime, e);
            return new Result<>(500, "延长会话有效期异常：" + e.getMessage(), null);
        }
    }

    /**
     * 根据时间范围统计用户会话数量
     */
    @Override
    public Result<List<Map<String, Object>>> countSessionsByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = userSessionMapper.countSessionsByTimeRange(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("统计时间范围会话异常：startTime={}, endTime={}", startTime, endTime, e);
            return new Result<>(500, "统计会话异常：" + e.getMessage(), null);
        }
    }

    /**
     * 根据客户端IP统计会话数量
     */
    @Override
    public Result<List<Map<String, Object>>> countSessionsByClientIp(LocalDateTime startTime, LocalDateTime endTime) {
        try {
            List<Map<String, Object>> stats = userSessionMapper.countSessionsByLoginType(startTime, endTime);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("根据IP统计会话异常：startTime={}, endTime={}", startTime, endTime, e);
            return new Result<>(500, "统计会话异常：" + e.getMessage(), null);
        }
    }
}
