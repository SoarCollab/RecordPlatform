package cn.flying.storage.service;

import cn.flying.storage.core.FaultDomainManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 降级写入追踪器
 * <p>
 * 当某个故障域完全不可用时，系统可降级写入到剩余健康域。
 * 此组件记录这些降级写入，以便在故障域恢复后自动补齐副本。
 * <p>
 * 使用 Redis Hash 存储，key 为对象哈希，value 为记录 JSON。
 *
 * @since v3.1.0
 */
@Slf4j
@Component
public class DegradedWriteTracker {

    private static final String DEGRADED_WRITE_KEY = "storage:degraded_writes";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private FaultDomainManager faultDomainManager;

    /**
     * 降级写入记录
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DegradedWriteRecord {
        /**
         * 对象哈希（分片哈希）
         */
        private String objectHash;

        /**
         * 成功写入的节点列表
         */
        private List<String> writtenNodes;

        /**
         * 未能写入的域列表
         */
        private List<String> missingDomains;

        /**
         * 租户 ID
         */
        private Long tenantId;

        /**
         * 创建时间 (epoch millis)
         */
        private Long createdAt;
    }

    /**
     * 记录一次降级写入
     *
     * @param objectHash   对象哈希
     * @param writtenNodes 成功写入的节点列表
     * @param tenantId     租户 ID
     */
    public void recordDegradedWrite(String objectHash, List<String> writtenNodes, Long tenantId) {
        List<String> missingDomains = calculateMissingDomains(writtenNodes);
        if (missingDomains.isEmpty()) {
            log.debug("No missing domains for {}, skip recording", objectHash);
            return;
        }

        DegradedWriteRecord record = new DegradedWriteRecord(
                objectHash,
                writtenNodes,
                missingDomains,
                tenantId,
                System.currentTimeMillis()
        );

        try {
            String json = objectMapper.writeValueAsString(record);
            stringRedisTemplate.opsForHash().put(DEGRADED_WRITE_KEY, objectHash, json);
            log.info("Recorded degraded write: hash={}, writtenNodes={}, missingDomains={}",
                    objectHash, writtenNodes, missingDomains);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize degraded write record: {}", objectHash, e);
        }
    }

    /**
     * 获取所有待同步的降级写入记录
     *
     * @return 待同步记录列表
     */
    public List<DegradedWriteRecord> getPendingSyncs() {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(DEGRADED_WRITE_KEY);
        if (entries.isEmpty()) {
            return Collections.emptyList();
        }

        return entries.values().stream()
                .map(v -> {
                    try {
                        return objectMapper.readValue((String) v, DegradedWriteRecord.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize degraded write record: {}", v, e);
                        return null;
                    }
                })
                .filter(r -> r != null)
                .collect(Collectors.toList());
    }

    /**
     * 获取指定域需要同步的记录
     *
     * @param domainName 域名称
     * @return 需要同步到该域的记录列表
     */
    public List<DegradedWriteRecord> getPendingSyncsForDomain(String domainName) {
        return getPendingSyncs().stream()
                .filter(r -> r.getMissingDomains().contains(domainName))
                .collect(Collectors.toList());
    }

    /**
     * 标记记录已同步完成
     *
     * @param objectHash 对象哈希
     */
    public void markSynced(String objectHash) {
        Long removed = stringRedisTemplate.opsForHash().delete(DEGRADED_WRITE_KEY, objectHash);
        if (removed != null && removed > 0) {
            log.info("Marked degraded write as synced: {}", objectHash);
        }
    }

    /**
     * 更新记录的缺失域（部分同步后）
     *
     * @param objectHash     对象哈希
     * @param syncedDomain   已同步的域
     */
    public void updateMissingDomains(String objectHash, String syncedDomain) {
        Object value = stringRedisTemplate.opsForHash().get(DEGRADED_WRITE_KEY, objectHash);
        if (value == null) {
            return;
        }

        try {
            DegradedWriteRecord record = objectMapper.readValue((String) value, DegradedWriteRecord.class);
            // 复制到可变列表以避免修改不可变列表
            List<String> mutableMissingDomains = new java.util.ArrayList<>(record.getMissingDomains());
            mutableMissingDomains.remove(syncedDomain);
            record.setMissingDomains(mutableMissingDomains);

            if (record.getMissingDomains().isEmpty()) {
                // 所有域已同步，移除记录
                markSynced(objectHash);
            } else {
                // 更新记录
                String json = objectMapper.writeValueAsString(record);
                stringRedisTemplate.opsForHash().put(DEGRADED_WRITE_KEY, objectHash, json);
                log.debug("Updated degraded write record: {}, remaining domains: {}",
                        objectHash, record.getMissingDomains());
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to update degraded write record: {}", objectHash, e);
        }
    }

    /**
     * 获取待同步记录数量
     *
     * @return 记录数量
     */
    public long getPendingCount() {
        Long size = stringRedisTemplate.opsForHash().size(DEGRADED_WRITE_KEY);
        return size != null ? size : 0;
    }

    /**
     * 计算缺失的域
     *
     * @param writtenNodes 已写入的节点
     * @return 未写入的域列表
     */
    private List<String> calculateMissingDomains(List<String> writtenNodes) {
        List<String> activeDomains = faultDomainManager.getActiveDomains();
        Set<String> writtenDomains = writtenNodes.stream()
                .map(faultDomainManager::getNodeDomain)
                .filter(d -> d != null)
                .collect(Collectors.toSet());

        return activeDomains.stream()
                .filter(d -> !writtenDomains.contains(d))
                .collect(Collectors.toList());
    }
}
