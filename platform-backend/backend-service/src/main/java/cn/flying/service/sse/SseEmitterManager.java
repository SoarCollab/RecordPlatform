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

@Slf4j
@Component
public class SseEmitterManager {

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;

    private final Map<Long, Map<Long, SseEmitter>> emittersByTenant = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> onlineUsersByTenant = new ConcurrentHashMap<>();

    public SseEmitter createConnection(Long tenantId, Long userId) {
        removeConnection(tenantId, userId);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        emitter.onCompletion(() -> {
            log.info("SSE 连接完成: tenantId={}, userId={}", tenantId, userId);
            removeConnection(tenantId, userId);
        });

        emitter.onTimeout(() -> {
            log.info("SSE 连接超时: tenantId={}, userId={}", tenantId, userId);
            removeConnection(tenantId, userId);
        });

        emitter.onError(e -> {
            log.warn("SSE 连接错误: tenantId={}, userId={}, error={}", tenantId, userId, e.getMessage());
            removeConnection(tenantId, userId);
        });

        getTenantEmitters(tenantId).put(userId, emitter);
        getTenantOnlineUsers(tenantId).add(userId);

        log.info("SSE 连接建立: tenantId={}, userId={}, 租户在线用户数={}", tenantId, userId, getOnlineCount(tenantId));

        sendToUser(tenantId, userId, SseEvent.connected());

        return emitter;
    }

    private Map<Long, SseEmitter> getTenantEmitters(Long tenantId) {
        return emittersByTenant.computeIfAbsent(tenantId, id -> new ConcurrentHashMap<>());
    }

    private Set<Long> getTenantOnlineUsers(Long tenantId) {
        return onlineUsersByTenant.computeIfAbsent(tenantId, id -> new CopyOnWriteArraySet<>());
    }

    public void removeConnection(Long tenantId, Long userId) {
        Map<Long, SseEmitter> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters != null) {
            SseEmitter emitter = tenantEmitters.remove(userId);
            if (emitter != null) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
            if (tenantEmitters.isEmpty()) {
                emittersByTenant.remove(tenantId);
            }
        }
        Set<Long> tenantUsers = onlineUsersByTenant.get(tenantId);
        if (tenantUsers != null) {
            tenantUsers.remove(userId);
            if (tenantUsers.isEmpty()) {
                onlineUsersByTenant.remove(tenantId);
            }
        }
    }

    public void sendToUser(Long tenantId, Long userId, SseEvent event) {
        Map<Long, SseEmitter> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null) {
            return;
        }

        SseEmitter emitter = tenantEmitters.get(userId);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(JsonConverter.toJson(event)));
            } catch (IOException e) {
                log.warn("SSE 发送失败: tenantId={}, userId={}, error={}", tenantId, userId, e.getMessage());
                removeConnection(tenantId, userId);
            }
        }
    }

    public void sendToUsers(Long tenantId, Set<Long> userIds, SseEvent event) {
        for (Long userId : userIds) {
            sendToUser(tenantId, userId, event);
        }
    }

    public void broadcastToTenant(Long tenantId, SseEvent event) {
        Map<Long, SseEmitter> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null || tenantEmitters.isEmpty()) {
            return;
        }
        String eventData = JsonConverter.toJson(event);
        tenantEmitters.forEach((userId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(eventData));
            } catch (IOException e) {
                log.warn("SSE 广播失败: tenantId={}, userId={}, error={}", tenantId, userId, e.getMessage());
                removeConnection(tenantId, userId);
            }
        });
    }

    public boolean isOnline(Long tenantId, Long userId) {
        Set<Long> tenantUsers = onlineUsersByTenant.get(tenantId);
        return tenantUsers != null && tenantUsers.contains(userId);
    }

    public int getOnlineCount(Long tenantId) {
        Set<Long> tenantUsers = onlineUsersByTenant.get(tenantId);
        return tenantUsers != null ? tenantUsers.size() : 0;
    }

    public Set<Long> getOnlineUsers(Long tenantId) {
        Set<Long> tenantUsers = onlineUsersByTenant.get(tenantId);
        return tenantUsers != null ? Set.copyOf(tenantUsers) : Set.of();
    }

    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (!emittersByTenant.isEmpty()) {
            SseEvent heartbeat = SseEvent.heartbeat();
            String eventData = JsonConverter.toJson(heartbeat);

            emittersByTenant.forEach((tenantId, tenantEmitters) ->
                    tenantEmitters.forEach((userId, emitter) -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(heartbeat.getType())
                                    .data(eventData));
                        } catch (IOException e) {
                            log.debug("SSE 心跳失败，移除连接: tenantId={}, userId={}", tenantId, userId);
                            removeConnection(tenantId, userId);
                        }
                    })
            );

            log.debug("SSE 心跳发送完成，租户数={}, 在线用户总数={}", emittersByTenant.size(), getTotalOnlineCount());
        }
    }

    private int getTotalOnlineCount() {
        return onlineUsersByTenant.values().stream()
                .mapToInt(Set::size)
                .sum();
    }
}
