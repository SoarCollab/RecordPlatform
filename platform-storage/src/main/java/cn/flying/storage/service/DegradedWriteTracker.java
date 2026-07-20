package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 降级写入追踪器
 * <p>
 * 当某个故障域完全不可用时，系统可降级写入到剩余健康域。
 * 此组件记录这些降级写入，以便在故障域恢复后自动补齐副本。
 * <p>
 * 使用 Redis Hash 存储，field 为租户 ID 与对象哈希的组合，value 为记录 JSON。
 *
 * @since v3.1.0
 */
@Slf4j
@Component
public class DegradedWriteTracker {

    private static final String DEGRADED_WRITE_KEY = "storage:degraded_writes";
    private static final String DUE_INDEX_KEY = "storage:degraded_writes:due";
    private static final String BACKFILL_CURSOR_KEY = "storage:degraded_writes:due:backfill_cursor";
    private static final String REPAIR_FAILURE_KEY = "storage:degraded_writes:repair_failures";
    private static final String DEAD_LETTER_KEY = "storage:degraded_writes:dead_letter";
    private static final String REVISION_KEY = "storage:degraded_writes:revisions";
    private static final String UPDATE_LOCK_PREFIX = "storage:degraded_writes:update:";
    private static final int MAX_FAILURE_REASON_LENGTH = 512;
    private static final int UPDATE_LOCK_WAIT_SECONDS = 5;
    private static final int MAX_UPDATE_CAS_ATTEMPTS = 3;

    private static final String MUTATION_KEEP = "K";
    private static final String MUTATION_SET = "S";
    private static final String MUTATION_DELETE = "D";

    private static final RedisScript<Long> BACKFILL_DUE_INDEX_SCRIPT = new DefaultRedisScript<>("""
            local cursor = redis.call('GET', KEYS[3]) or '0'
            local scan = redis.call('HSCAN', KEYS[1], cursor, 'COUNT', ARGV[1])
            local entries = scan[2]
            local added = 0
            for i = 1, #entries, 2 do
                local field = entries[i]
                if not redis.call('ZSCORE', KEYS[2], field) then
                    redis.call('ZADD', KEYS[2], ARGV[2], field)
                    added = added + 1
                end
            end
            redis.call('SET', KEYS[3], scan[1])
            return added
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM_DUE_SCRIPT = new DefaultRedisScript<>("""
            local fields = redis.call(
                'ZRANGEBYSCORE', KEYS[2], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2]
            )
            local claimed = {}
            for _, field in ipairs(fields) do
                local json = redis.call('HGET', KEYS[1], field)
                if json then
                    redis.call('ZADD', KEYS[2], ARGV[3], field)
                    claimed[#claimed + 1] = field
                    claimed[#claimed + 1] = json
                else
                    redis.call('ZREM', KEYS[2], field)
                end
            end
            return claimed
            """, List.class);

    private static final RedisScript<Long> RESCHEDULE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1 then
                redis.call('ZADD', KEYS[2], ARGV[2], ARGV[1])
                return 1
            end
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 0
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> READ_REVISIONED_STATE_SCRIPT = new DefaultRedisScript<>("""
            -- DEGRADED_STATE_SNAPSHOT
            local activeType = redis.call('TYPE', KEYS[1]).ok
            local revisionType = redis.call('TYPE', KEYS[2]).ok
            if (activeType ~= 'none' and activeType ~= 'hash')
                    or (revisionType ~= 'none' and revisionType ~= 'hash') then
                return {'-2', '0', '', '0', ''}
            end
            local revision = redis.call('HGET', KEYS[2], ARGV[3]) or '0'
            local primaryRaw = redis.call('HGET', KEYS[1], ARGV[1])
            local legacyRaw = nil
            if ARGV[2] == ARGV[1] then
                legacyRaw = primaryRaw
            else
                legacyRaw = redis.call('HGET', KEYS[1], ARGV[2])
            end
            return {
                revision,
                primaryRaw and '1' or '0',
                primaryRaw or '',
                legacyRaw and '1' or '0',
                legacyRaw or ''
            }
            """, List.class);

    private static final RedisScript<Long> REVISIONED_TRANSITION_SCRIPT = new DefaultRedisScript<>("""
            -- DEGRADED_TRANSITION_CAS
            local storageField = ARGV[1]
            local fenceField = ARGV[2]
            local expectedRevision = ARGV[3]
            local nextRevision = ARGV[4]
            local primaryField = ARGV[5]
            local primaryExpectedExists = ARGV[6]
            local primaryExpectedRaw = ARGV[7]
            local legacyField = ARGV[8]
            local legacyExpectedExists = ARGV[9]
            local legacyExpectedRaw = ARGV[10]
            local activeType = redis.call('TYPE', KEYS[1]).ok
            local dueType = redis.call('TYPE', KEYS[2]).ok
            local failureType = redis.call('TYPE', KEYS[3]).ok
            local deadLetterType = redis.call('TYPE', KEYS[4]).ok
            local revisionType = redis.call('TYPE', KEYS[5]).ok
            if (activeType ~= 'none' and activeType ~= 'hash')
                    or (dueType ~= 'none' and dueType ~= 'zset')
                    or (failureType ~= 'none' and failureType ~= 'hash')
                    or (deadLetterType ~= 'none' and deadLetterType ~= 'hash')
                    or (revisionType ~= 'none' and revisionType ~= 'hash') then
                return -2
            end
            local currentRevision = redis.call('HGET', KEYS[5], fenceField) or '0'
            if currentRevision ~= expectedRevision then
                return 0
            end
            local currentPrimaryRaw = redis.call('HGET', KEYS[1], primaryField)
            if primaryExpectedExists == '1' then
                if not currentPrimaryRaw or currentPrimaryRaw ~= primaryExpectedRaw then
                    return 0
                end
            elseif currentPrimaryRaw then
                return 0
            end
            local currentLegacyRaw = currentPrimaryRaw
            if legacyField ~= primaryField then
                currentLegacyRaw = redis.call('HGET', KEYS[1], legacyField)
            end
            if legacyExpectedExists == '1' then
                if not currentLegacyRaw or currentLegacyRaw ~= legacyExpectedRaw then
                    return 0
                end
            elseif currentLegacyRaw then
                return 0
            end

            local index = 11
            local activeMutation = ARGV[index]
            index = index + 1
            local activeJson = ARGV[index]
            index = index + 1
            local dueMutation = ARGV[index]
            index = index + 1
            local dueScore = ARGV[index]
            index = index + 1

            if activeMutation == 'S' then
                redis.call('HSET', KEYS[1], storageField, activeJson)
            elseif activeMutation == 'D' then
                redis.call('HDEL', KEYS[1], storageField)
            end
            if dueMutation == 'S' then
                redis.call('ZADD', KEYS[2], dueScore, storageField)
            elseif dueMutation == 'D' then
                redis.call('ZREM', KEYS[2], storageField)
            end

            local failurePutCount = tonumber(ARGV[index])
            index = index + 1
            for current = 1, failurePutCount do
                redis.call('HSET', KEYS[3], ARGV[index], ARGV[index + 1])
                index = index + 2
            end
            local failureDeleteCount = tonumber(ARGV[index])
            index = index + 1
            for current = 1, failureDeleteCount do
                redis.call('HDEL', KEYS[3], ARGV[index])
                index = index + 1
            end
            local deadLetterPutCount = tonumber(ARGV[index])
            index = index + 1
            for current = 1, deadLetterPutCount do
                redis.call('HSET', KEYS[4], ARGV[index], ARGV[index + 1])
                index = index + 2
            end
            local deadLetterDeleteCount = tonumber(ARGV[index])
            index = index + 1
            for current = 1, deadLetterDeleteCount do
                redis.call('HDEL', KEYS[4], ARGV[index])
                index = index + 1
            end

            redis.call('HSET', KEYS[5], fenceField, nextRevision)
            return 1
            """, Long.class);

    /**
     * 向同包真实 Redis 集成测试暴露生产 CAS Lua，避免测试复制脚本文本。
     */
    static RedisScript<Long> revisionedTransitionScriptForTesting() {
        return REVISIONED_TRANSITION_SCRIPT;
    }

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private FaultDomainManager faultDomainManager;

    @Resource
    private StorageProperties storageProperties;

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
     * 单个租户、对象、缺失域和精确目标节点的连续修复失败状态。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RepairFailureState {
        private String objectHash;
        private Long tenantId;
        private String domain;
        private String targetNode;
        private int attempts;
        private Long firstFailureAt;
        private Long lastFailureAt;
        private String lastFailureReason;
    }

    /**
     * 达到失败阈值后保留的显式死信记录。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DegradedWriteDeadLetter {
        private DegradedWriteRecord degradedWrite;
        private String failedDomain;
        private String targetNode;
        private int attempts;
        private Long firstFailureAt;
        private Long lastFailureAt;
        private String lastFailureReason;
        private Long deadLetteredAt;
    }

    /**
     * 修复失败状态写入结果，供监听器输出准确的生命周期日志。
     */
    public enum RepairFailureDisposition {
        PENDING,
        DEAD_LETTERED,
        ALREADY_DEAD_LETTERED,
        IGNORED
    }

    /**
     * 已通过 Redis ZSET 原子租约领取的单条待恢复记录。
     *
     * @param storageField Hash/ZSET 共用的稳定 field
     * @param record Hash 中保存的 JSON truth
     */
    public record ClaimedDegradedWrite(String storageField, DegradedWriteRecord record) {
    }

    /**
     * 一次业务重算所依据的 active 原文与单调 revision 快照。
     */
    private record RevisionedState(
            String storageField,
            String fenceField,
            long revision,
            String primaryField,
            String primaryRaw,
            String legacyField,
            String legacyRaw,
            String activeRaw,
            DegradedWriteRecord activeRecord
    ) {
    }

    /**
     * 单次 Lua CAS 要提交的完整状态转移；所有 JSON 均在执行 Lua 前完成序列化。
     */
    private static final class RevisionedTransition<T> {
        private T result;
        private String activeMutation = MUTATION_KEEP;
        private String activeJson = "";
        private String dueMutation = MUTATION_KEEP;
        private long dueScore;
        private final Map<String, String> failurePuts = new LinkedHashMap<>();
        private final Set<String> failureDeletes = new LinkedHashSet<>();
        private final Map<String, String> deadLetterPuts = new LinkedHashMap<>();
        private final Set<String> deadLetterDeletes = new LinkedHashSet<>();

        private RevisionedTransition(T result) {
            this.result = result;
        }

        /** 记录仅在 CAS 提交成功后才可返回给调用方的结果。 */
        private void setResult(T result) {
            this.result = result;
        }

        /** 设置提交后的 active JSON。 */
        private void setActive(String json) {
            activeMutation = MUTATION_SET;
            activeJson = json;
        }

        /** 删除提交时观察到的 active 记录。 */
        private void deleteActive() {
            activeMutation = MUTATION_DELETE;
            activeJson = "";
        }

        /** 将 active field 排入指定到期时间。 */
        private void schedule(long score) {
            dueMutation = MUTATION_SET;
            dueScore = score;
        }

        /** 从 due index 删除 active field。 */
        private void removeDue() {
            dueMutation = MUTATION_DELETE;
            dueScore = 0L;
        }

        /** 写入精确 target 的失败状态，并取消同 field 删除。 */
        private void putFailure(String field, String json) {
            failureDeletes.remove(field);
            failurePuts.put(field, json);
        }

        /** 删除精确 target 或旧版域级失败状态。 */
        private void deleteFailure(String field) {
            failurePuts.remove(field);
            failureDeletes.add(field);
        }

        /** 写入精确 target 的死信，并取消同 field 删除。 */
        private void putDeadLetter(String field, String json) {
            deadLetterDeletes.remove(field);
            deadLetterPuts.put(field, json);
        }

        /** 删除精确 target 或旧版域级死信。 */
        private void deleteDeadLetter(String field) {
            deadLetterPuts.remove(field);
            deadLetterDeletes.add(field);
        }
    }

    /**
     * 使用同一拓扑快照与每次重读的 Redis 快照重新计算完整状态转移。
     */
    @FunctionalInterface
    private interface PlacementTransitionPlanner<T> {
        RevisionedTransition<T> plan(
                Map<String, String> plannedTargets,
                RevisionedState state
        );
    }

    /**
     * 记录一次降级写入
     *
     * @param objectHash   对象哈希
     * @param writtenNodes 成功写入的节点列表
     * @param tenantId     租户 ID
     */
    public void recordDegradedWrite(String objectHash, List<String> writtenNodes, Long tenantId) {
        recordDegradedWrite(objectHash, writtenNodes, tenantId, false);
    }

    /**
     * 使用本轮已验证节点作为当前 placement 的权威观察，持久化仍未验证的精确目标。
     *
     * <p>历史非当前目标仍可作为后续修复源；历史当前目标只有本轮重新验证成功时才计入，
     * 避免已经丢失的副本被旧 writtenNodes 永久掩盖。</p>
     *
     * @param objectHash 对象哈希
     * @param verifiedNodes 本轮真实验证成功的节点
     * @param tenantId 租户 ID
     */
    public void recordAuthoritativeDegradedWrite(
            String objectHash,
            List<String> verifiedNodes,
            Long tenantId
    ) {
        recordDegradedWrite(objectHash, verifiedNodes, tenantId, true);
    }

    /**
     * 在对象级更新锁内合并普通写入，或替换当前 placement 的历史观察。
     */
    private void recordDegradedWrite(
            String objectHash,
            List<String> writtenNodes,
            Long tenantId,
            boolean authoritativeCurrentPlacement
    ) {
        DegradedWriteRecord committed = withPlacementUpdateLock(
                objectHash,
                tenantId,
                true,
                (plannedTargets, state) -> {
            DegradedWriteRecord existing = state.activeRecord();
            LinkedHashSet<String> mergedWrittenNodes = new LinkedHashSet<>();
            if (existing != null && existing.getWrittenNodes() != null) {
                if (authoritativeCurrentPlacement) {
                    existing.getWrittenNodes().stream()
                            .filter(node -> !plannedTargets.containsValue(node))
                            .forEach(mergedWrittenNodes::add);
                } else {
                    mergedWrittenNodes.addAll(existing.getWrittenNodes());
                }
            }
            if (writtenNodes != null) {
                mergedWrittenNodes.addAll(writtenNodes);
            }

            RevisionedTransition<DegradedWriteRecord> transition = new RevisionedTransition<>(null);
            stageSatisfiedTargetLifecycleCleanup(
                    objectHash,
                    tenantId,
                    mergedWrittenNodes,
                    plannedTargets,
                    transition
            );

            List<String> mergedMissingDomains = calculateMissingDomains(
                    plannedTargets,
                    new ArrayList<>(mergedWrittenNodes)
            )
                    .stream()
                    .filter(domain -> !isDeadLettered(
                            objectHash,
                            tenantId,
                            domain,
                            plannedTargets
                    ))
                    .toList();
            if (mergedMissingDomains.isEmpty()) {
                transition.deleteActive();
                transition.removeDue();
                return transition;
            }

            DegradedWriteRecord record = new DegradedWriteRecord(
                    objectHash,
                    new ArrayList<>(mergedWrittenNodes),
                    mergedMissingDomains,
                    tenantId,
                    existing != null && existing.getCreatedAt() != null
                            ? existing.getCreatedAt()
                            : System.currentTimeMillis()
            );
            transition.setResult(record);
            transition.setActive(serializeJson(record, "degraded write record", state.storageField()));
            transition.schedule(System.currentTimeMillis());
            return transition;
        });
        if (committed == null) {
            log.debug("No missing placement remains after degraded write update: hash={}, tenant={}",
                    objectHash, tenantId);
        } else {
            log.info("Recorded degraded write: hash={}, tenant={}, writtenNodes={}, missingDomains={}",
                    objectHash, tenantId, committed.getWrittenNodes(), committed.getMissingDomains());
        }
    }

    /**
     * 解析当前 hash ring 中每个故障域的精确目标节点；无法解析的域保留为空并继续视为缺失。
     */
    private Map<String, String> resolveCurrentPlannedTargets(String objectHash) {
        Map<String, String> plannedTargets = faultDomainManager.getPlannedTargetsSnapshot(objectHash);
        if (plannedTargets == null) {
            throw new IllegalStateException("planned target snapshot must not be null");
        }
        return plannedTargets;
    }

    /**
     * 领取固定上限的到期恢复记录，并把领取项的 score 原子移动到租约到期时间。
     *
     * <p>每轮领取前执行一次带持久化 cursor 的 HSCAN backfill，使旧版本只写 Hash 的记录
     * 能逐批进入 due index；整个生产路径不会执行 HGETALL。</p>
     *
     * @return 本实例在当前租约内独占的待恢复记录
     */
    @SuppressWarnings("unchecked")
    public List<ClaimedDegradedWrite> claimPendingSyncs() {
        StorageProperties.DegradedWriteConfig config = degradedWriteConfig();
        int batchSize = config.getEffectiveSyncBatchSize();
        long now = System.currentTimeMillis();
        backfillDueIndex(batchSize, now);
        long leaseUntil = safeAddMillis(now, TimeUnit.SECONDS.toMillis(
                config.getEffectiveClaimLeaseSeconds()));

        List<Object> rawClaims = (List<Object>) stringRedisTemplate.execute(
                CLAIM_DUE_SCRIPT,
                List.of(DEGRADED_WRITE_KEY, DUE_INDEX_KEY),
                String.valueOf(now),
                String.valueOf(batchSize),
                String.valueOf(leaseUntil)
        );
        if (rawClaims == null || rawClaims.isEmpty()) {
            return Collections.emptyList();
        }

        List<ClaimedDegradedWrite> claims = new ArrayList<>(Math.min(batchSize, rawClaims.size() / 2));
        for (int index = 0; index + 1 < rawClaims.size() && claims.size() < batchSize; index += 2) {
            String storageField = String.valueOf(rawClaims.get(index));
            DegradedWriteRecord record = readJsonValue(
                    rawClaims.get(index + 1),
                    DegradedWriteRecord.class,
                    "claimed degraded write record"
            );
            if (record == null) {
                rescheduleClaim(storageField);
                continue;
            }
            claims.add(new ClaimedDegradedWrite(storageField, record));
        }
        return List.copyOf(claims);
    }

    /**
     * 立即释放不属于当前事件域的 claim，供其他实例或后续轮询继续领取。
     *
     * @param storageField 被释放的 Hash/ZSET field
     */
    public void releaseClaim(String storageField) {
        rescheduleFieldIfPending(storageField, System.currentTimeMillis());
    }

    /**
     * 对无法安全解释或暂时无法处理的 claim 执行有界延迟重排。
     *
     * @param storageField 需要重排的 Hash/ZSET field
     */
    public void rescheduleClaim(String storageField) {
        long delay = TimeUnit.SECONDS.toMillis(degradedWriteConfig().getEffectiveRetryBackoffSeconds());
        rescheduleFieldIfPending(storageField, safeAddMillis(System.currentTimeMillis(), delay));
    }

    /**
     * 在等待修复 future 超时或中断后按完整 claim 租约重排，确保尚未退出的 worker
     * 在 request deadline 前不会与下一实例重复复制。
     *
     * @param storageField 需要延后重排的 Hash/ZSET field
     */
    public void rescheduleClaimAfterRepairTimeout(String storageField) {
        long delay = TimeUnit.SECONDS.toMillis(degradedWriteConfig().getEffectiveClaimLeaseSeconds());
        rescheduleFieldIfPending(storageField, safeAddMillis(System.currentTimeMillis(), delay));
    }

    /**
     * 标记记录已同步完成
     *
     * @param objectHash 对象哈希
     */
    public void markSynced(String objectHash) {
        deleteStorageField(objectHash, null);
    }

    /**
     * 按租户和对象哈希删除已同步记录，并兼容读取旧版仅 hash field。
     *
     * @param objectHash 对象哈希
     * @param tenantId 租户 ID
     */
    public void markSynced(String objectHash, Long tenantId) {
        deleteStorageField(objectHash, tenantId);
    }

    /**
     * 删除精确租户记录；仅当旧版记录租户匹配时才回收旧 field。
     */
    private void deleteStorageField(String objectHash, Long tenantId) {
        boolean removed = withUpdateLock(
                objectHash,
                tenantId,
                () -> applyRevisionedUpdate(objectHash, tenantId, false, state -> {
                    RevisionedTransition<Boolean> transition =
                            new RevisionedTransition<>(state.activeRaw() != null);
                    transition.deleteActive();
                    transition.removeDue();
                    return transition;
                })
        );
        if (removed) {
            log.info("Marked degraded write as synced: hash={}, tenant={}", objectHash, tenantId);
        }
    }

    /**
     * 更新记录的缺失域（部分同步后）
     *
     * @param objectHash     对象哈希
     * @param syncedDomain   已同步的域
     */
    public void updateMissingDomains(String objectHash, String syncedDomain) {
        updateMissingDomains(
                objectHash,
                null,
                syncedDomain,
                null,
                true
        );
    }

    /**
     * 更新精确租户记录的缺失域，并记录本次成功写入的目标节点。
     *
     * @param objectHash 对象哈希
     * @param tenantId 租户 ID
     * @param syncedDomain 已同步域
     * @param syncedNode 已写入节点
     */
    public void updateMissingDomains(
            String objectHash,
            Long tenantId,
            String syncedDomain,
            String syncedNode
    ) {
        updateMissingDomains(objectHash, tenantId, syncedDomain, syncedNode, false);
    }

    /**
     * 在一次 planned-target 快照内应用修复成功，避免目标校验与缺失域重算跨越拓扑版本。
     *
     * @param usePlannedTargetAsSyncedNode 兼容旧入口时是否把本快照目标视为已同步节点
     */
    private void updateMissingDomains(
            String objectHash,
            Long tenantId,
            String syncedDomain,
            String syncedNode,
            boolean usePlannedTargetAsSyncedNode
    ) {
        withPlacementUpdateLock(objectHash, tenantId, false, (plannedTargets, state) -> {
            RevisionedTransition<Void> transition = new RevisionedTransition<>(null);
            if (hasUnresolvedPlannedTargets(plannedTargets)) {
                log.error("Cannot update degraded placement while physical topology is unresolved: "
                                + "hash={}, tenant={}, domain={}",
                        objectHash, tenantId, syncedDomain);
                scheduleIfActive(transition, state, System.currentTimeMillis());
                return transition;
            }
            DegradedWriteRecord record = state.activeRecord();
            if (record != null) {
                if (record.getMissingDomains() == null) {
                    log.error("Cannot update degraded write with unknown missing domains: hash={}, tenant={}",
                            objectHash, tenantId);
                    transition.schedule(System.currentTimeMillis());
                    return transition;
                }
                String effectiveSyncedNode = usePlannedTargetAsSyncedNode
                        ? resolvePlannedTargetNode(plannedTargets, syncedDomain)
                        : syncedNode;
                if (effectiveSyncedNode != null && !effectiveSyncedNode.isBlank()) {
                    LinkedHashSet<String> writtenNodes = new LinkedHashSet<>(safeList(record.getWrittenNodes()));
                    writtenNodes.add(effectiveSyncedNode);
                    record.setWrittenNodes(new ArrayList<>(writtenNodes));
                }

                String currentTarget = resolvePlannedTargetNode(plannedTargets, syncedDomain);
                boolean currentTargetRepaired = currentTarget != null
                        && currentTarget.equals(effectiveSyncedNode);
                if (currentTargetRepaired) {
                    // 只允许当前 ring target 的成功清理当前 target 生命周期；旧节点迟到成功不得越权。
                    String repairField = buildRepairField(
                            objectHash,
                            tenantId,
                            syncedDomain,
                            currentTarget
                    );
                    transition.deleteFailure(repairField);
                    transition.deleteDeadLetter(repairField);
                    stageLegacyDomainLifecycleCleanup(
                            objectHash,
                            tenantId,
                            syncedDomain,
                            transition
                    );
                } else {
                    log.info("Ignore stale degraded repair success for placement completion: hash={}, tenant={}, "
                                    + "domain={}, syncedNode={}, currentTarget={}",
                            objectHash, tenantId, syncedDomain, effectiveSyncedNode, currentTarget);
                }

                List<String> recalculatedMissingDomains = calculateMissingDomains(
                        plannedTargets,
                        record.getWrittenNodes()
                )
                        .stream()
                        .filter(domain -> !isDeadLettered(
                                objectHash,
                                tenantId,
                                domain,
                                plannedTargets
                        ))
                        .toList();
                record.setMissingDomains(recalculatedMissingDomains);

                if (record.getMissingDomains().isEmpty()) {
                    transition.deleteActive();
                    transition.removeDue();
                } else {
                    transition.setActive(serializeJson(
                            record,
                            "degraded write record",
                            state.storageField()
                    ));
                    transition.schedule(System.currentTimeMillis());
                    log.debug("Updated degraded write record: hash={}, tenant={}, remaining domains={}",
                            objectHash, tenantId, record.getMissingDomains());
                }
            }
            return transition;
        });
    }

    /**
     * 根据成功修复的节点解析故障域并更新租户隔离的待同步记录。
     *
     * @param objectHash 对象哈希
     * @param tenantId 租户 ID
     * @param repairedNode 已写入的节点
     */
    public void markNodeRepaired(String objectHash, Long tenantId, String repairedNode) {
        String repairedDomain = faultDomainManager.getNodeDomain(repairedNode);
        if (repairedDomain == null || repairedDomain.isBlank()) {
            log.warn("Cannot resolve repaired node domain: hash={}, tenant={}, node={}",
                    objectHash, tenantId, repairedNode);
            return;
        }
        updateMissingDomains(objectHash, tenantId, repairedDomain, repairedNode);
    }

    /**
     * 记录指定缺失域的一次修复失败；达到阈值后在 tenant/hash 锁保护下转入死信生命周期。
     *
     * @param objectHash 对象哈希
     * @param tenantId 租户 ID
     * @param failedDomain 修复失败的域
     * @param reason 失败原因
     * @param maxFailures 进入死信的连续失败阈值
     * @return 本次失败处理结果
     */
    public RepairFailureDisposition recordRepairFailure(
            String objectHash,
            Long tenantId,
            String failedDomain,
            String reason,
            int maxFailures
    ) {
        return recordRepairFailure(
                objectHash,
                tenantId,
                failedDomain,
                null,
                reason,
                maxFailures,
                true
        );
    }

    /**
     * 记录一次绑定实际尝试 target 的失败；ring 已漂移时拒绝把旧目标失败归因到新目标。
     *
     * @param attemptedTargetNode 本次 provider 复制真实尝试的目标节点
     */
    public RepairFailureDisposition recordRepairFailure(
            String objectHash,
            Long tenantId,
            String failedDomain,
            String attemptedTargetNode,
            String reason,
            int maxFailures
    ) {
        return recordRepairFailure(
                objectHash,
                tenantId,
                failedDomain,
                attemptedTargetNode,
                reason,
                maxFailures,
                false
        );
    }

    /**
     * 在单个 planned-target 快照内归属修复失败并推进失败或死信生命周期。
     *
     * @param usePlannedTargetAsAttempt 兼容旧入口时是否把本快照目标视为实际尝试节点
     */
    private RepairFailureDisposition recordRepairFailure(
            String objectHash,
            Long tenantId,
            String failedDomain,
            String attemptedTargetNode,
            String reason,
            int maxFailures,
            boolean usePlannedTargetAsAttempt
    ) {
        if (failedDomain == null || failedDomain.isBlank()) {
            log.warn("Ignore degraded repair failure without domain: hash={}, tenant={}", objectHash, tenantId);
            return RepairFailureDisposition.IGNORED;
        }
        if (!usePlannedTargetAsAttempt
                && (attemptedTargetNode == null || attemptedTargetNode.isBlank())) {
            log.warn("Ignore degraded repair failure without attempted target: hash={}, tenant={}, domain={}",
                    objectHash, tenantId, failedDomain);
            return RepairFailureDisposition.IGNORED;
        }

        return withPlacementUpdateLock(objectHash, tenantId, false, (plannedTargets, state) -> {
            String effectiveAttemptedTargetNode = usePlannedTargetAsAttempt
                    ? resolvePlannedTargetNode(plannedTargets, failedDomain)
                    : attemptedTargetNode;
            if (effectiveAttemptedTargetNode == null || effectiveAttemptedTargetNode.isBlank()) {
                log.warn("Ignore degraded repair failure without attempted target: hash={}, tenant={}, domain={}",
                        objectHash, tenantId, failedDomain);
                return new RevisionedTransition<>(RepairFailureDisposition.IGNORED);
            }
            DegradedWriteRecord degradedWrite = state.activeRecord();
            String targetNode = resolvePlannedTargetNode(plannedTargets, failedDomain);
            if (targetNode == null || !targetNode.equals(effectiveAttemptedTargetNode)) {
                RevisionedTransition<RepairFailureDisposition> transition =
                        new RevisionedTransition<>(RepairFailureDisposition.IGNORED);
                scheduleIfActive(transition, state, System.currentTimeMillis());
                log.info("Ignore degraded repair failure after target drift: hash={}, tenant={}, domain={}, "
                                + "attemptedTarget={}, currentTarget={}",
                        objectHash, tenantId, failedDomain, effectiveAttemptedTargetNode, targetNode);
                return transition;
            }

            RevisionedTransition<RepairFailureDisposition> transition =
                    new RevisionedTransition<>(RepairFailureDisposition.IGNORED);
            // 旧版 domain-only 状态无法证明属于当前 target，必须与本次转移原子清除。
            stageLegacyDomainLifecycleCleanup(
                    objectHash,
                    tenantId,
                    failedDomain,
                    transition
            );
            String repairField = buildRepairField(
                    objectHash,
                    tenantId,
                    failedDomain,
                    targetNode
            );
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForHash().hasKey(DEAD_LETTER_KEY, repairField))) {
                transition.setResult(RepairFailureDisposition.ALREADY_DEAD_LETTERED);
                transition.deleteFailure(repairField);
                if (degradedWrite == null) {
                    transition.removeDue();
                } else if (degradedWrite.getMissingDomains() == null) {
                    transition.schedule(System.currentTimeMillis());
                } else if (degradedWrite.getMissingDomains().contains(failedDomain)) {
                    stagePendingDomainRemoval(state, degradedWrite, failedDomain, transition);
                } else {
                    transition.schedule(System.currentTimeMillis());
                }
                return transition;
            }

            if (degradedWrite == null || !safeList(degradedWrite.getMissingDomains()).contains(failedDomain)) {
                scheduleIfActive(transition, state, System.currentTimeMillis());
                return transition;
            }

            long now = System.currentTimeMillis();
            RepairFailureState previous = readRepairFailure(repairField);
            if (previous != null && !Objects.equals(previous.getTargetNode(), targetNode)) {
                transition.deleteFailure(repairField);
                previous = null;
            }
            int attempts = previous == null ? 1 : previous.getAttempts() + 1;
            long firstFailureAt = previous != null && previous.getFirstFailureAt() != null
                    ? previous.getFirstFailureAt()
                    : now;
            String safeReason = normalizeFailureReason(reason);
            RepairFailureState current = new RepairFailureState(
                    objectHash,
                    tenantId,
                    failedDomain,
                    targetNode,
                    attempts,
                    firstFailureAt,
                    now,
                    safeReason
            );

            int effectiveThreshold = Math.max(1, maxFailures);
            if (attempts < effectiveThreshold) {
                transition.setResult(RepairFailureDisposition.PENDING);
                transition.putFailure(
                        repairField,
                        serializeJson(current, "repair failure state", repairField)
                );
                transition.schedule(retryAt(now, attempts));
                log.warn("Degraded repair remains pending: hash={}, tenant={}, domain={}, attempts={}/{}",
                        objectHash, tenantId, failedDomain, attempts, effectiveThreshold);
                return transition;
            }

            DegradedWriteDeadLetter deadLetter = new DegradedWriteDeadLetter(
                    degradedWrite,
                    failedDomain,
                    targetNode,
                    attempts,
                    firstFailureAt,
                    now,
                    safeReason,
                    now
            );
            transition.setResult(RepairFailureDisposition.DEAD_LETTERED);
            transition.putDeadLetter(
                    repairField,
                    serializeJson(deadLetter, "degraded write dead letter", repairField)
            );
            transition.deleteFailure(repairField);
            // active、due、failure 与 DLQ 在同一 Lua 中全有或全无。
            stagePendingDomainRemoval(state, degradedWrite, failedDomain, transition);
            log.error("Degraded repair moved to dead letter: hash={}, tenant={}, domain={}, attempts={}",
                    objectHash, tenantId, failedDomain, attempts);
            return transition;
        });
    }

    /**
     * 获取显式死信数量，供监控和运维告警使用。
     *
     * @return 死信记录数量
     */
    public long getDeadLetterCount() {
        return stringRedisTemplate.opsForHash().size(DEAD_LETTER_KEY);
    }

    /**
     * 获取待同步记录数量
     *
     * @return 记录数量
     */
    public long getPendingCount() {
        return stringRedisTemplate.opsForHash().size(DEGRADED_WRITE_KEY);
    }

    /**
     * 构建不会在相同内容哈希的不同租户之间碰撞的 Redis field。
     */
    private String buildStorageField(String objectHash, Long tenantId) {
        return String.valueOf(tenantId != null ? tenantId : 0L) + ":" + objectHash;
    }

    /**
     * 构建租户、对象、域和精确目标节点四元组隔离的失败/死信 field。
     */
    private String buildRepairField(
            String objectHash,
            Long tenantId,
            String domain,
            String targetNode
    ) {
        return buildLegacyRepairField(objectHash, tenantId, domain)
                + ":"
                + encodeRepairIdentity(targetNode);
    }

    /**
     * 构建旧版 domain-only field，仅用于迁移清理，不能用于当前生命周期判断。
     */
    private String buildLegacyRepairField(String objectHash, Long tenantId, String domain) {
        return buildStorageField(objectHash, tenantId) + ":" + encodeRepairIdentity(domain);
    }

    /**
     * 对 Redis field 的域/节点身份执行 URL-safe Base64 编码。
     */
    private String encodeRepairIdentity(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 清理无法归属到精确 target 的旧版 domain-only 生命周期。
     */
    private void stageLegacyDomainLifecycleCleanup(
            String objectHash,
            Long tenantId,
            String domain,
            RevisionedTransition<?> transition
    ) {
        String legacyField = buildLegacyRepairField(objectHash, tenantId, domain);
        transition.deleteFailure(legacyField);
        transition.deleteDeadLetter(legacyField);
    }

    /**
     * 对已有当前 exact target 成功证据的域清理当前目标生命周期，避免旧 attempts/DLQ 污染下一轮缺口。
     * 其他 target 的精确状态保留为审计证据。
     */
    private void stageSatisfiedTargetLifecycleCleanup(
            String objectHash,
            Long tenantId,
            Collection<String> writtenNodes,
            Map<String, String> plannedTargets,
            RevisionedTransition<?> transition
    ) {
        Set<String> writtenNodeSet = new HashSet<>(writtenNodes);
        for (Map.Entry<String, String> plannedTarget : plannedTargets.entrySet()) {
            String domain = plannedTarget.getKey();
            String targetNode = plannedTarget.getValue();
            if (targetNode == null || !writtenNodeSet.contains(targetNode)) {
                continue;
            }
            String repairField = buildRepairField(objectHash, tenantId, domain, targetNode);
            transition.deleteFailure(repairField);
            transition.deleteDeadLetter(repairField);
            stageLegacyDomainLifecycleCleanup(objectHash, tenantId, domain, transition);
        }
    }

    /**
     * 判断当前精确目标是否已进入死信；旧 target 和旧版 domain-only 状态均不得抑制新目标。
     */
    private boolean isDeadLettered(
            String objectHash,
            Long tenantId,
            String domain,
            Map<String, String> plannedTargets
    ) {
        String targetNode = resolvePlannedTargetNode(plannedTargets, domain);
        if (targetNode == null) {
            return false;
        }
        return Boolean.TRUE.equals(stringRedisTemplate.opsForHash().hasKey(
                DEAD_LETTER_KEY,
                buildRepairField(objectHash, tenantId, domain, targetNode)
        ));
    }

    /**
     * 从本轮不可变快照解析精确 ring target，空值表示当前拓扑无法安全归属失败状态。
     */
    private String resolvePlannedTargetNode(Map<String, String> plannedTargets, String domain) {
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String targetNode = plannedTargets.get(domain);
        return targetNode == null || targetNode.isBlank() ? null : targetNode;
    }

    /**
     * 每次 CAS 尝试只获取一次 planned-target 快照，并将同一实例传给完整读改写过程。
     */
    private <T> T withPlacementUpdateLock(
            String objectHash,
            Long tenantId,
            boolean retryOnConflict,
            PlacementTransitionPlanner<T> planner
    ) {
        return withUpdateLock(
                objectHash,
                tenantId,
                () -> applyRevisionedUpdate(
                        objectHash,
                        tenantId,
                        retryOnConflict,
                        state -> planner.plan(resolveCurrentPlannedTargets(objectHash), state)
                )
        );
    }

    /**
     * 读取 revision/active 快照、重算转移并以 Lua CAS 提交；冲突时按调用方策略重试或失败关闭。
     */
    private <T> T applyRevisionedUpdate(
            String objectHash,
            Long tenantId,
            boolean retryOnConflict,
            Function<RevisionedState, RevisionedTransition<T>> planner
    ) {
        int maxAttempts = retryOnConflict ? MAX_UPDATE_CAS_ATTEMPTS : 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RevisionedState state = readRevisionedState(objectHash, tenantId);
            RevisionedTransition<T> transition = Objects.requireNonNull(
                    planner.apply(state),
                    "revisioned degraded-write transition must not be null"
            );
            Long committed = executeRevisionedTransition(state, transition);
            if (Long.valueOf(1L).equals(committed)) {
                return transition.result;
            }
            if (committed == null || committed < 0L) {
                throw new IllegalStateException("degraded write revision CAS returned an invalid result");
            }
            if (!retryOnConflict) {
                throw new IllegalStateException("degraded write revision CAS conflict");
            }
        }
        throw new IllegalStateException("degraded write revision CAS retries exhausted");
    }

    /**
     * 读取 active 原始 JSON 与跨删除保留的单调 revision；损坏值一律失败关闭。
     */
    @SuppressWarnings("unchecked")
    private RevisionedState readRevisionedState(String objectHash, Long tenantId) {
        String primaryField = tenantId == null ? objectHash : buildStorageField(objectHash, tenantId);
        String legacyField = objectHash;
        String fenceField = buildStorageField(objectHash, tenantId);
        List<Object> rawSnapshot = (List<Object>) stringRedisTemplate.execute(
                READ_REVISIONED_STATE_SCRIPT,
                List.of(DEGRADED_WRITE_KEY, REVISION_KEY),
                primaryField,
                legacyField,
                fenceField
        );
        if (rawSnapshot == null || rawSnapshot.size() != 5) {
            throw new IllegalStateException("invalid degraded write revision snapshot");
        }
        long revision = parseRevision(rawSnapshot.get(0), fenceField);
        String primaryRaw = readSnapshotRaw(rawSnapshot, 1, 2, "primary active");
        String legacyRaw = readSnapshotRaw(rawSnapshot, 3, 4, "legacy active");

        String storageField = primaryField;
        String activeRaw = primaryRaw;
        DegradedWriteRecord activeRecord = null;
        if (primaryRaw != null) {
            activeRecord = readRequiredJsonValue(
                    primaryRaw,
                    DegradedWriteRecord.class,
                    "degraded write record"
            );
        } else if (legacyRaw != null) {
            DegradedWriteRecord legacyRecord = readRequiredJsonValue(
                    legacyRaw,
                    DegradedWriteRecord.class,
                    "legacy degraded write record"
            );
            if (tenantId == null || Objects.equals(legacyRecord.getTenantId(), tenantId)) {
                storageField = legacyField;
                activeRaw = legacyRaw;
                activeRecord = legacyRecord;
            }
        }
        return new RevisionedState(
                storageField,
                fenceField,
                revision,
                primaryField,
                primaryRaw,
                legacyField,
                legacyRaw,
                activeRaw,
                activeRecord
        );
    }

    /**
     * 从 Lua 快照的存在标记与原文槽位还原可空 active JSON。
     */
    private String readSnapshotRaw(
            List<Object> rawSnapshot,
            int existsIndex,
            int rawIndex,
            String valueName
    ) {
        String exists = String.valueOf(rawSnapshot.get(existsIndex));
        if ("0".equals(exists)) {
            return null;
        }
        if (!"1".equals(exists)) {
            throw new IllegalStateException("invalid " + valueName + " existence marker");
        }
        return String.valueOf(rawSnapshot.get(rawIndex));
    }

    /**
     * 校验并解析 Redis revision；非法或负数值会阻止后续状态写入。
     */
    private long parseRevision(Object revisionValue, String fenceField) {
        if (revisionValue == null) {
            return 0L;
        }
        try {
            long revision = Long.parseLong(String.valueOf(revisionValue));
            if (revision < 0L) {
                throw new NumberFormatException("negative revision");
            }
            return revision;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid degraded write revision: " + fenceField, e);
        }
    }

    /**
     * 将一次业务转移编码为固定 KEYS 与有界 ARGV，并在单个 Redis Lua 中全量提交。
     */
    private Long executeRevisionedTransition(
            RevisionedState state,
            RevisionedTransition<?> transition
    ) {
        List<String> arguments = new ArrayList<>();
        arguments.add(state.storageField());
        arguments.add(state.fenceField());
        arguments.add(String.valueOf(state.revision()));
        if (state.revision() == Long.MAX_VALUE) {
            throw new IllegalStateException("degraded write revision is exhausted");
        }
        arguments.add(String.valueOf(state.revision() + 1L));
        arguments.add(state.primaryField());
        arguments.add(state.primaryRaw() == null ? "0" : "1");
        arguments.add(state.primaryRaw() == null ? "" : state.primaryRaw());
        arguments.add(state.legacyField());
        arguments.add(state.legacyRaw() == null ? "0" : "1");
        arguments.add(state.legacyRaw() == null ? "" : state.legacyRaw());
        arguments.add(transition.activeMutation);
        arguments.add(transition.activeJson);
        arguments.add(transition.dueMutation);
        arguments.add(String.valueOf(transition.dueScore));
        appendHashPuts(arguments, transition.failurePuts);
        appendHashDeletes(arguments, transition.failureDeletes);
        appendHashPuts(arguments, transition.deadLetterPuts);
        appendHashDeletes(arguments, transition.deadLetterDeletes);

        return stringRedisTemplate.execute(
                REVISIONED_TRANSITION_SCRIPT,
                List.of(
                        DEGRADED_WRITE_KEY,
                        DUE_INDEX_KEY,
                        REPAIR_FAILURE_KEY,
                        DEAD_LETTER_KEY,
                        REVISION_KEY
                ),
                arguments.toArray()
        );
    }

    /** 将 Hash 写集合追加为 count + field/value 对。 */
    private void appendHashPuts(List<String> arguments, Map<String, String> puts) {
        arguments.add(String.valueOf(puts.size()));
        puts.forEach((field, json) -> {
            arguments.add(field);
            arguments.add(json);
        });
    }

    /** 将 Hash 删除集合追加为 count + field 列表。 */
    private void appendHashDeletes(List<String> arguments, Set<String> deletes) {
        arguments.add(String.valueOf(deletes.size()));
        arguments.addAll(deletes);
    }

    /**
     * 在 tenant/hash 分布式锁内执行 Redis 读改写，避免并发域修复互相覆盖。
     */
    private <T> T withUpdateLock(String objectHash, Long tenantId, Supplier<T> action) {
        String lockName = UPDATE_LOCK_PREFIX + buildStorageField(objectHash, tenantId);
        RLock lock = redissonClient.getLock(lockName);
        boolean acquired = false;
        try {
            // 不指定固定 lease，启用 Redisson watchdog 自动续租，避免 Redis 抖动期间锁中途过期。
            acquired = lock.tryLock(UPDATE_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("degraded write update lock is busy");
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while acquiring degraded write update lock", e);
        } finally {
            if (acquired) {
                try {
                    lock.unlock();
                } catch (IllegalMonitorStateException e) {
                    log.warn("Degraded write update lock ownership was lost before release: {}", lockName);
                } catch (RuntimeException e) {
                    log.error("Failed to release degraded write update lock: {}", lockName, e);
                }
            }
        }
    }

    /**
     * 读取域级修复失败状态；损坏状态不得被当作首次失败覆盖。
     */
    private RepairFailureState readRepairFailure(String repairField) {
        Object value = stringRedisTemplate.opsForHash().get(REPAIR_FAILURE_KEY, repairField);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String json)) {
            throw new IllegalStateException("repair failure state is not a string: " + repairField);
        }
        return readRequiredJsonValue(json, RepairFailureState.class, "repair failure state");
    }

    /**
     * 反序列化状态真值，格式异常时失败关闭并保留 Redis 原文。
     */
    private <T> T readRequiredJsonValue(String json, Class<T> valueType, String valueName) {
        try {
            return objectMapper.readValue(json, valueType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to deserialize " + valueName, e);
        }
    }

    /**
     * 反序列化 Redis JSON 值，格式异常时返回 null 并保留原始数据。
     */
    private <T> T readJsonValue(Object value, Class<T> valueType, String valueName) {
        if (!(value instanceof String json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, valueType);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize {}: {}", valueName, json, e);
            return null;
        }
    }

    /**
     * 在待提交转移中移除一个 pending 域，并同步生成 active/due 的最终状态。
     */
    private void stagePendingDomainRemoval(
            RevisionedState state,
            DegradedWriteRecord degradedWrite,
            String domain,
            RevisionedTransition<?> transition
    ) {
        if (degradedWrite.getMissingDomains() == null) {
            throw new IllegalStateException(
                    "cannot remove pending domain from unknown state: " + state.storageField()
            );
        }
        List<String> remainingDomains = new ArrayList<>(safeList(degradedWrite.getMissingDomains()));
        remainingDomains.remove(domain);
        degradedWrite.setMissingDomains(remainingDomains);
        if (remainingDomains.isEmpty()) {
            transition.deleteActive();
            transition.removeDue();
            return;
        }
        transition.setActive(serializeJson(
                degradedWrite,
                "degraded write record",
                state.storageField()
        ));
        transition.schedule(System.currentTimeMillis());
    }

    /**
     * active 存在时重排 due，不存在时清理孤立 due；具体操作仍由 CAS Lua 提交。
     */
    private void scheduleIfActive(
            RevisionedTransition<?> transition,
            RevisionedState state,
            long dueAt
    ) {
        if (state.activeRaw() == null) {
            transition.removeDue();
        } else {
            transition.schedule(dueAt);
        }
    }

    /**
     * 使用持久化 HSCAN cursor 为旧版 Hash 记录逐批补齐 due index。
     */
    private void backfillDueIndex(int batchSize, long now) {
        stringRedisTemplate.execute(
                BACKFILL_DUE_INDEX_SCRIPT,
                List.of(DEGRADED_WRITE_KEY, DUE_INDEX_KEY, BACKFILL_CURSOR_KEY),
                String.valueOf(batchSize),
                String.valueOf(now)
        );
    }

    /**
     * 仅在 Hash truth 仍存在时原子重排 claim，否则清理孤立索引。
     */
    private void rescheduleFieldIfPending(String storageField, long dueAt) {
        if (storageField == null || storageField.isBlank()) {
            return;
        }
        stringRedisTemplate.execute(
                RESCHEDULE_SCRIPT,
                List.of(DEGRADED_WRITE_KEY, DUE_INDEX_KEY),
                storageField,
                String.valueOf(dueAt)
        );
    }

    /**
     * 根据域级连续失败次数计算指数退避时间，并由配置上限限制。
     */
    private long retryAt(long now, int attempts) {
        StorageProperties.DegradedWriteConfig config = degradedWriteConfig();
        long baseMillis = TimeUnit.SECONDS.toMillis(config.getEffectiveRetryBackoffSeconds());
        long maxMillis = TimeUnit.SECONDS.toMillis(config.getEffectiveMaxRetryBackoffSeconds());
        long delay = baseMillis;
        for (int current = 1; current < attempts && delay < maxMillis; current++) {
            delay = Math.min(maxMillis, delay > maxMillis / 2 ? maxMillis : delay * 2);
        }
        return safeAddMillis(now, delay);
    }

    /**
     * 返回带安全默认值和 clamp 的恢复调度配置。
     */
    private StorageProperties.DegradedWriteConfig degradedWriteConfig() {
        if (storageProperties != null && storageProperties.getDegradedWrite() != null) {
            return storageProperties.getDegradedWrite();
        }
        return new StorageProperties.DegradedWriteConfig();
    }

    /**
     * 饱和相加毫秒时间，避免极端配置或时钟值溢出为立即到期。
     */
    private long safeAddMillis(long base, long delta) {
        return delta > Long.MAX_VALUE - base ? Long.MAX_VALUE : base + delta;
    }

    /**
     * 在 Lua 执行前完成 JSON 序列化；失败时不触发任何 Redis 状态转移。
     */
    private String serializeJson(Object value, String valueName, String field) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize {}: field={}", valueName, field, e);
            throw new IllegalStateException("failed to serialize " + valueName, e);
        }
    }

    /**
     * 将失败原因限制在可控长度，避免外部异常消息无限放大 Redis 记录。
     */
    private String normalizeFailureReason(String reason) {
        String normalized = reason == null || reason.isBlank() ? "unspecified" : reason.trim();
        return normalized.length() <= MAX_FAILURE_REASON_LENGTH
                ? normalized
                : normalized.substring(0, MAX_FAILURE_REASON_LENGTH);
    }

    /**
     * 将可能为空的列表转换为只读空列表，兼容历史记录缺失字段。
     */
    private <T> List<T> safeList(List<T> values) {
        return values != null ? values : Collections.emptyList();
    }

    /**
     * 判断计划快照是否含有无法证明物理唯一性的目标。
     *
     * <p>FaultDomainManager 会把缺失、非法或重复 physicalStorageId 的多副本计划
     * 映射为空目标；此时任何成功回调都不得清理 durable missing-domain evidence。</p>
     *
     * @param plannedTargets 当前对象的一次不可变计划快照
     * @return true 表示至少一个计划域无法安全解析物理目标
     */
    private boolean hasUnresolvedPlannedTargets(Map<String, String> plannedTargets) {
        return plannedTargets.values().stream()
                .anyMatch(targetNode -> targetNode == null || targetNode.isBlank());
    }

    /**
     * 按一次拓扑快照计算缺失域，避免一次状态更新内混用两个 ring 版本。
     *
     * <p>同域旧节点或临时 fallback 节点不代表 placement 已满足；只有本快照的 planned target
     * 已真实出现在 writtenNodes 中，该域才可以从 durable repair 状态移除。</p>
     */
    private List<String> calculateMissingDomains(
            Map<String, String> plannedTargets,
            List<String> writtenNodes
    ) {
        Set<String> writtenNodeSet = new HashSet<>(safeList(writtenNodes));

        return plannedTargets.entrySet().stream()
                .filter(entry -> entry.getValue() == null
                        || entry.getValue().isBlank()
                        || !writtenNodeSet.contains(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
    }
}
