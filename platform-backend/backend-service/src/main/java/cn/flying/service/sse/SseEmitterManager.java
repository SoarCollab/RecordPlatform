package cn.flying.service.sse;

import cn.flying.common.constant.UserRole;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.Account;
import cn.flying.dao.mapper.AccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SseEmitterManager {

    private static final long SSE_TIMEOUT = 30 * 60 * 1000L;
    private static final int MAX_CONNECTIONS_PER_USER = 5;

    @Resource
    private AccountMapper accountMapper;

    // tenantId -> userId -> connectionId -> emitter
    private final Map<Long, Map<Long, Map<String, SseEmitter>>> emittersByTenant = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> onlineUsersByTenant = new ConcurrentHashMap<>();

    /**
     * 创建SSE连接，支持同一用户多个连接（多设备/多标签页）
     *
     * @param tenantId 租户ID
     * @param userId 用户ID
     * @param connectionId 连接ID（唯一标识每个连接）
     * @return SseEmitter
     */
    public SseEmitter createConnection(Long tenantId, Long userId, String connectionId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 使用同步块确保 size 检查和添加是原子操作
        Map<String, SseEmitter> userConnections = getOrCreateUserConnections(tenantId, userId);
        List<SseEmitter> emittersToComplete = new ArrayList<>();
        synchronized (userConnections) {
            // 限制每个用户的最大连接数，防止滥用
            while (userConnections.size() >= MAX_CONNECTIONS_PER_USER) {
                SseEmitter oldEmitter = removeOldestConnectionLocked(tenantId, userId, userConnections);
                if (oldEmitter != null) {
                    emittersToComplete.add(oldEmitter);
                }
            }
            userConnections.put(connectionId, emitter);
        }

        // 在锁外完成旧的 emitter，避免死锁（complete() 会触发 onCompletion 回调）
        for (SseEmitter oldEmitter : emittersToComplete) {
            try {
                oldEmitter.complete();
            } catch (Exception e) {
                log.debug("Failed to complete old SSE emitter during connection limit cleanup: {}", e.getMessage());
            }
        }

        emitter.onCompletion(() -> {
            log.info("SSE 连接完成: tenantId={}, userId={}, connectionId={}", tenantId, userId, connectionId);
            removeConnection(tenantId, userId, connectionId);
        });

        emitter.onTimeout(() -> {
            log.info("SSE 连接超时: tenantId={}, userId={}, connectionId={}", tenantId, userId, connectionId);
            removeConnection(tenantId, userId, connectionId);
        });

        emitter.onError(e -> {
            log.warn("SSE 连接错误: tenantId={}, userId={}, connectionId={}, error={}",
                    tenantId, userId, connectionId, e.getMessage());
            removeConnection(tenantId, userId, connectionId);
        });

        getTenantOnlineUsers(tenantId).add(userId);

        log.info("SSE 连接建立: tenantId={}, userId={}, connectionId={}, 用户连接数={}, 租户在线用户数={}",
                tenantId, userId, connectionId, userConnections.size(), getOnlineCount(tenantId));

        sendToConnection(emitter, SseEvent.connected());

        return emitter;
    }

    /**
     * 获取或创建用户的连接 Map
     * 使用 synchronizedMap 包装 LinkedHashMap 保证线程安全和插入顺序
     */
    private Map<String, SseEmitter> getOrCreateUserConnections(Long tenantId, Long userId) {
        return emittersByTenant
                .computeIfAbsent(tenantId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(userId, id -> Collections.synchronizedMap(new LinkedHashMap<>()));
    }

    /**
     * 移除最老的连接（必须在持有 userConnections 锁的情况下调用）
     * @return 被移除的 emitter，由调用者在锁外调用 complete()
     */
    private SseEmitter removeOldestConnectionLocked(Long tenantId, Long userId, Map<String, SseEmitter> connections) {
        var iterator = connections.entrySet().iterator();
        if (iterator.hasNext()) {
            var entry = iterator.next();
            String oldestConnId = entry.getKey();
            SseEmitter oldEmitter = entry.getValue();
            iterator.remove(); // 直接从迭代器移除，避免 ConcurrentModification

            log.info("SSE 移除最老连接（达到上限）: tenantId={}, userId={}, connectionId={}",
                    tenantId, userId, oldestConnId);

            return oldEmitter;
        }
        return null;
    }

    private Map<Long, Map<String, SseEmitter>> getTenantEmitters(Long tenantId) {
        return emittersByTenant.computeIfAbsent(tenantId, id -> new ConcurrentHashMap<>());
    }

    private Set<Long> getTenantOnlineUsers(Long tenantId) {
        return onlineUsersByTenant.computeIfAbsent(tenantId, id -> new CopyOnWriteArraySet<>());
    }

    /**
     * 移除指定连接
     */
    public void removeConnection(Long tenantId, Long userId, String connectionId) {
        Map<Long, Map<String, SseEmitter>> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null) return;

        Map<String, SseEmitter> userConnections = tenantEmitters.get(userId);
        if (userConnections == null) return;

        SseEmitter emitter;
        boolean isEmpty;
        synchronized (userConnections) {
            emitter = userConnections.remove(connectionId);
            isEmpty = userConnections.isEmpty();
        }

        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception e) {
                log.debug("Failed to complete SSE emitter during connection removal: {}", e.getMessage());
            }
        }

        // 清理空的 map（在同步块外执行，减少锁持有时间）
        if (isEmpty) {
            tenantEmitters.remove(userId);
            getTenantOnlineUsers(tenantId).remove(userId);
            if (tenantEmitters.isEmpty()) {
                emittersByTenant.remove(tenantId);
            }
        }
    }

    /**
     * 静默移除连接，不调用 emitter.complete()
     * 用于在发送心跳失败等场景下安全清理连接状态，
     * 避免在连接已处于错误状态时触发 AsyncRequestNotUsableException
     */
    private void removeConnectionSilently(Long tenantId, Long userId, String connectionId) {
        Map<Long, Map<String, SseEmitter>> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null) return;

        Map<String, SseEmitter> userConnections = tenantEmitters.get(userId);
        if (userConnections == null) return;

        boolean isEmpty;
        synchronized (userConnections) {
            userConnections.remove(connectionId);
            isEmpty = userConnections.isEmpty();
        }

        // 清理空的 map
        if (isEmpty) {
            tenantEmitters.remove(userId);
            getTenantOnlineUsers(tenantId).remove(userId);
            if (tenantEmitters.isEmpty()) {
                emittersByTenant.remove(tenantId);
            }
        }
    }

    /**
     * 发送事件到单个连接
     */
    private void sendToConnection(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.getType())
                    .data(JsonConverter.toJson(event.getPayload())));
        } catch (IOException e) {
            log.warn("SSE 发送到连接失败: error={}", e.getMessage());
        }
    }

    /**
     * 发送事件到用户的所有连接（广播到所有设备/标签页）
     */
    public void sendToUser(Long tenantId, Long userId, SseEvent event) {
        Map<Long, Map<String, SseEmitter>> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null) return;

        Map<String, SseEmitter> userConnections = tenantEmitters.get(userId);
        if (userConnections == null) return;

        // 在同步块中创建快照
        List<Map.Entry<String, SseEmitter>> snapshot;
        synchronized (userConnections) {
            if (userConnections.isEmpty()) return;
            snapshot = new ArrayList<>(userConnections.entrySet());
        }

        String eventData = JsonConverter.toJson(event.getPayload());
        List<String> failedConnections = new ArrayList<>();

        // 发送到用户的所有连接（使用快照）
        for (var entry : snapshot) {
            String connectionId = entry.getKey();
            SseEmitter emitter = entry.getValue();
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(eventData));
            } catch (IOException e) {
                log.warn("SSE 发送失败: tenantId={}, userId={}, connectionId={}, error={}",
                        tenantId, userId, connectionId, e.getMessage());
                failedConnections.add(connectionId);
            }
        }

        // 批量移除失败的连接
        failedConnections.forEach(connId -> removeConnectionSilently(tenantId, userId, connId));
    }

    public void sendToUsers(Long tenantId, Set<Long> userIds, SseEvent event) {
        for (Long userId : userIds) {
            sendToUser(tenantId, userId, event);
        }
    }

    public void broadcastToTenant(Long tenantId, SseEvent event) {
        Map<Long, Map<String, SseEmitter>> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null || tenantEmitters.isEmpty()) return;

        String eventData = JsonConverter.toJson(event.getPayload());
        List<String[]> failedConnections = new ArrayList<>();

        // 遍历所有用户
        for (var userEntry : new ArrayList<>(tenantEmitters.entrySet())) {
            Long userId = userEntry.getKey();
            Map<String, SseEmitter> userConnections = userEntry.getValue();

            // 在同步块中创建用户连接的快照
            List<Map.Entry<String, SseEmitter>> snapshot;
            synchronized (userConnections) {
                if (userConnections.isEmpty()) continue;
                snapshot = new ArrayList<>(userConnections.entrySet());
            }

            for (var connEntry : snapshot) {
                String connectionId = connEntry.getKey();
                SseEmitter emitter = connEntry.getValue();
                try {
                    emitter.send(SseEmitter.event()
                            .name(event.getType())
                            .data(eventData));
                } catch (IOException e) {
                    log.warn("SSE 广播失败: tenantId={}, userId={}, connectionId={}, error={}",
                            tenantId, userId, connectionId, e.getMessage());
                    failedConnections.add(new String[]{String.valueOf(userId), connectionId});
                }
            }
        }

        // 批量移除失败的连接
        failedConnections.forEach(pair ->
                removeConnectionSilently(tenantId, Long.parseLong(pair[0]), pair[1]));
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

    /**
     * 获取用户的连接数
     */
    public int getUserConnectionCount(Long tenantId, Long userId) {
        Map<Long, Map<String, SseEmitter>> tenantEmitters = emittersByTenant.get(tenantId);
        if (tenantEmitters == null) return 0;
        Map<String, SseEmitter> userConnections = tenantEmitters.get(userId);
        if (userConnections == null) return 0;
        synchronized (userConnections) {
            return userConnections.size();
        }
    }

    @Scheduled(fixedRate = 30000)
    public void sendHeartbeat() {
        if (emittersByTenant.isEmpty()) return;

        SseEvent heartbeat = SseEvent.heartbeat();
        String eventData = JsonConverter.toJson(heartbeat.getPayload());
        List<Object[]> failedConnections = new ArrayList<>();

        // 遍历所有租户和用户
        for (var tenantEntry : new ArrayList<>(emittersByTenant.entrySet())) {
            Long tenantId = tenantEntry.getKey();
            Map<Long, Map<String, SseEmitter>> tenantEmitters = tenantEntry.getValue();

            for (var userEntry : new ArrayList<>(tenantEmitters.entrySet())) {
                Long userId = userEntry.getKey();
                Map<String, SseEmitter> userConnections = userEntry.getValue();

                // 在同步块中创建用户连接的快照
                List<Map.Entry<String, SseEmitter>> snapshot;
                synchronized (userConnections) {
                    if (userConnections.isEmpty()) continue;
                    snapshot = new ArrayList<>(userConnections.entrySet());
                }

                for (var connEntry : snapshot) {
                    String connectionId = connEntry.getKey();
                    SseEmitter emitter = connEntry.getValue();
                    try {
                        emitter.send(SseEmitter.event()
                                .name(heartbeat.getType())
                                .data(eventData));
                    } catch (IOException | IllegalStateException e) {
                        // IOException: 网络错误
                        // IllegalStateException: 连接已关闭/完成
                        log.debug("SSE 心跳失败，移除连接: tenantId={}, userId={}, connectionId={}, reason={}",
                                tenantId, userId, connectionId, e.getClass().getSimpleName());
                        failedConnections.add(new Object[]{tenantId, userId, connectionId});
                    } catch (Exception e) {
                        // 捕获其他运行时异常，避免中断心跳任务
                        log.warn("SSE 心跳发送异常: tenantId={}, userId={}, connectionId={}, error={}",
                                tenantId, userId, connectionId, e.getMessage());
                        failedConnections.add(new Object[]{tenantId, userId, connectionId});
                    }
                }
            }
        }

        // 批量移除失败的连接
        failedConnections.forEach(arr ->
                removeConnectionSilently((Long) arr[0], (Long) arr[1], (String) arr[2]));

        log.debug("SSE 心跳发送完成，租户数={}, 在线用户总数={}, 总连接数={}",
                emittersByTenant.size(), getTotalOnlineCount(), getTotalConnectionCount());
    }

    private int getTotalOnlineCount() {
        return onlineUsersByTenant.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    private int getTotalConnectionCount() {
        return emittersByTenant.values().stream()
                .flatMap(tenantEmitters -> tenantEmitters.values().stream())
                .mapToInt(Map::size)
                .sum();
    }

    /**
     * 向租户内所有在线管理员和监控员广播消息
     *
     * @param tenantId 租户ID
     * @param event SSE事件
     */
    public void broadcastToAdmins(Long tenantId, SseEvent event) {
        Set<Long> onlineUsers = getOnlineUsers(tenantId);
        if (onlineUsers.isEmpty()) {
            log.debug("租户 {} 没有在线用户，跳过管理员广播", tenantId);
            return;
        }

        // 查询当前租户的所有管理员和监控员
        LambdaQueryWrapper<Account> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(Account::getTenantId, tenantId)
                    .in(Account::getRole, UserRole.ROLE_ADMINISTER.getRole(), UserRole.ROLE_MONITOR.getRole())
                    .in(Account::getId, onlineUsers);

        List<Account> admins = accountMapper.selectList(queryWrapper);
        if (admins.isEmpty()) {
            log.debug("租户 {} 没有在线管理员/监控员，跳过广播", tenantId);
            return;
        }

        Set<Long> adminIds = admins.stream()
                .map(Account::getId)
                .collect(Collectors.toSet());

        sendToUsers(tenantId, adminIds, event);
        log.info("向租户 {} 的 {} 个在线管理员/监控员广播审计告警", tenantId, adminIds.size());
    }

    /**
     * 向所有租户的在线管理员和监控员广播消息（跨租户全局广播）
     * 自动处理租户上下文切换，确保数据库查询正确执行
     *
     * @param event SSE事件
     */
    public void broadcastToAllAdmins(SseEvent event) {
        List<Long> tenantIds = new ArrayList<>(emittersByTenant.keySet());
        if (tenantIds.isEmpty()) {
            log.debug("没有活跃的SSE连接，跳过全局管理员广播");
            return;
        }

        int successCount = 0;
        for (Long tenantId : tenantIds) {
            try {
                TenantContext.callWithTenant(tenantId, () -> {
                    broadcastToAdmins(tenantId, event);
                    return null;
                });
                successCount++;
            } catch (Exception e) {
                log.warn("向租户 {} 的管理员广播失败: {}", tenantId, e.getMessage());
            }
        }
        log.info("全局管理员广播完成，成功租户数={}/总租户数={}", successCount, tenantIds.size());
    }
}
