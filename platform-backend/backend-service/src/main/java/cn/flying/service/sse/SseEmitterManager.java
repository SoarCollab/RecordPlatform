package cn.flying.service.sse;

import cn.flying.common.util.JsonConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * SSE 连接管理器
 */
@Slf4j
@Component
public class SseEmitterManager {

    /**
     * SSE 超时时间（30分钟）
     */
    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    /**
     * 用户ID -> SseEmitter 映射
     */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * 在线用户ID集合
     */
    private final Set<Long> onlineUsers = new CopyOnWriteArraySet<>();

    /**
     * 创建连接
     *
     * @param userId 用户ID
     * @return SseEmitter
     */
    public SseEmitter createConnection(Long userId) {
        // 移除旧连接
        removeConnection(userId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 设置回调
        emitter.onCompletion(() -> {
            log.info("SSE 连接完成: userId={}", userId);
            removeConnection(userId);
        });

        emitter.onTimeout(() -> {
            log.info("SSE 连接超时: userId={}", userId);
            removeConnection(userId);
        });

        emitter.onError(e -> {
            log.warn("SSE 连接错误: userId={}, error={}", userId, e.getMessage());
            removeConnection(userId);
        });

        emitters.put(userId, emitter);
        onlineUsers.add(userId);

        log.info("SSE 连接建立: userId={}, 在线用户数={}", userId, onlineUsers.size());

        // 发送连接成功事件
        sendToUser(userId, SseEvent.connected());

        return emitter;
    }

    /**
     * 移除连接
     *
     * @param userId 用户ID
     */
    public void removeConnection(Long userId) {
        SseEmitter emitter = emitters.remove(userId);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        onlineUsers.remove(userId);
    }

    /**
     * 发送消息给指定用户
     *
     * @param userId 用户ID
     * @param event  事件
     */
    public void sendToUser(Long userId, SseEvent event) {
        SseEmitter emitter = emitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(JsonConverter.toJson(event)));
            } catch (IOException e) {
                log.warn("SSE 发送失败: userId={}, error={}", userId, e.getMessage());
                removeConnection(userId);
            }
        }
    }

    /**
     * 发送消息给多个用户
     *
     * @param userIds 用户ID集合
     * @param event   事件
     */
    public void sendToUsers(Set<Long> userIds, SseEvent event) {
        for (Long userId : userIds) {
            sendToUser(userId, event);
        }
    }

    /**
     * 广播给所有在线用户
     *
     * @param event 事件
     */
    public void broadcast(SseEvent event) {
        String eventData = JsonConverter.toJson(event);
        emitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(eventData));
            } catch (IOException e) {
                log.warn("SSE 广播失败: userId={}, error={}", userId, e.getMessage());
                removeConnection(userId);
            }
        });
    }

    /**
     * 检查用户是否在线
     *
     * @param userId 用户ID
     * @return 是否在线
     */
    public boolean isOnline(Long userId) {
        return onlineUsers.contains(userId);
    }

    /**
     * 获取在线用户数
     *
     * @return 在线用户数
     */
    public int getOnlineCount() {
        return onlineUsers.size();
    }

    /**
     * 获取所有在线用户ID
     *
     * @return 在线用户ID集合
     */
    public Set<Long> getOnlineUsers() {
        return Set.copyOf(onlineUsers);
    }

    /**
     * 定时发送心跳（每30秒）
     */
    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (!emitters.isEmpty()) {
            SseEvent heartbeat = SseEvent.heartbeat();
            String eventData = JsonConverter.toJson(heartbeat);

            emitters.forEach((userId, emitter) -> {
                try {
                    emitter.send(SseEmitter.event()
                            .name(heartbeat.getType())
                            .data(eventData));
                } catch (IOException e) {
                    log.debug("SSE 心跳失败，移除连接: userId={}", userId);
                    removeConnection(userId);
                }
            });

            log.debug("SSE 心跳发送完成，在线用户数={}", onlineUsers.size());
        }
    }
}
