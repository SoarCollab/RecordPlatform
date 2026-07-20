package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 使用带代际 fencing 的 Redis 到期索引跟踪 direct-upload staging 生命周期。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DirectUploadStagingTracker {

    private static final String EXPIRY_SET_KEY = "storage:direct-upload:staging-expiry";
    static final String CLAIM_HASH_KEY = "storage:direct-upload:staging-expiry:claims";
    private static final String CLAIM_GATE_KEY = "storage:direct-upload:staging-expiry:claim-gate";
    private static final String DEAD_LETTER_SET_KEY = "storage:direct-upload:staging-expiry:dead-letter";
    private static final String MEMBER_VERSION = "v1";
    private static final long CLEANUP_RETRY_DELAY_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final int MAX_ENCODED_MEMBER_LENGTH = 2048;
    private static final Pattern SESSION_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern NODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder TOKEN_DECODER = Base64.getUrlDecoder();

    private static final RedisScript<Long> REFRESH_SCRIPT = new DefaultRedisScript<>("""
            if ARGV[3] == '1' and redis.call('GET', KEYS[3]) then
                local epoch = redis.call('INCR', KEYS[5])
                local ttl = redis.call('PTTL', KEYS[3])
                if ttl > 0 then
                    redis.call('SET', KEYS[4], tostring(epoch), 'PX', ttl)
                else
                    redis.call('SET', KEYS[4], tostring(epoch))
                end
                return -1
            end
            local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
            local requested = tonumber(ARGV[2])
            if current and tonumber(current) > requested then
                requested = tonumber(current)
            end
            redis.call('ZADD', KEYS[1], requested, ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local gate = redis.call('SET', KEYS[3], ARGV[4], 'NX', 'PX', ARGV[5])
            if not gate then
                return {}
            end
            local members = redis.call(
                'ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[3]
            )
            if #members == 0 then
                if redis.call('GET', KEYS[3]) == ARGV[4] then
                    redis.call('DEL', KEYS[3])
                end
                return {}
            end
            local claimed = {}
            for index, member in ipairs(members) do
                local originalScore = redis.call('ZSCORE', KEYS[1], member)
                local claimToken = ARGV[4] .. ':' .. index
                redis.call('ZADD', KEYS[1], ARGV[2], member)
                redis.call('HSET', KEYS[2], member, claimToken)
                claimed[#claimed + 1] = member
                claimed[#claimed + 1] = claimToken
                claimed[#claimed + 1] = tostring(originalScore)
            end
            return claimed
            """, List.class);

    private static final RedisScript<Long> VERIFY_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], ARGV[1]) == ARGV[2] then
                return 1
            end
            return 0
            """, Long.class);

    private static final RedisScript<Long> COMPLETE_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
                return 0
            end
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[1], ARGV[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> RESCHEDULE_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
                return 0
            end
            local retryAt = tonumber(ARGV[3])
            local current = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if current and tonumber(current) > retryAt then
                retryAt = tonumber(current)
            end
            redis.call('ZADD', KEYS[1], retryAt, ARGV[1])
            redis.call('HDEL', KEYS[2], ARGV[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> RESCHEDULE_CLAIMS_SCRIPT = new DefaultRedisScript<>("""
            local retryAt = tonumber(ARGV[1])
            local rescheduled = 0
            for index = 2, #ARGV, 2 do
                local member = ARGV[index]
                local claimToken = ARGV[index + 1]
                if redis.call('HGET', KEYS[2], member) == claimToken then
                    local target = retryAt
                    local current = redis.call('ZSCORE', KEYS[1], member)
                    if current and tonumber(current) > target then
                        target = tonumber(current)
                    end
                    redis.call('ZADD', KEYS[1], target, member)
                    redis.call('HDEL', KEYS[2], member)
                    rescheduled = rescheduled + 1
                end
            end
            return rescheduled
            """, Long.class);

    private static final RedisScript<Long> DEAD_LETTER_CLAIM_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[2], ARGV[1]) ~= ARGV[2] then
                return 0
            end
            redis.call('HDEL', KEYS[2], ARGV[1])
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('ZADD', KEYS[3], ARGV[3], ARGV[1])
            return 1
            """, Long.class);

    private static final RedisScript<Long> RELEASE_GATE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final StorageProperties storageProperties;
    private final DirectUploadLockManager lockManager;
    private final DirectUploadOperationIntentStore operationIntentStore;

    /**
     * 登记或刷新 staging 对象生命周期，并与 complete、abort、cleanup 使用同一分片锁串行。
     *
     * @param descriptor staging 对象身份
     */
    public void record(DirectUploadStagingDescriptor descriptor) {
        refreshWithPartLock(descriptor, true);
    }

    /**
     * 对已删除 staging 保留 tombstone 到完整保留期结束，以清理预签名 URL 的延迟重放。
     *
     * <p>该方法允许在 promotion 已持有同一 Redisson 锁时重入。</p>
     *
     * @param descriptor staging 对象身份
     */
    public void retainAfterDelete(DirectUploadStagingDescriptor descriptor) {
        refreshWithPartLock(descriptor, false);
    }

    /**
     * 兼容旧调用点；完成或中止后必须保留 tombstone，禁止立即丢弃生命周期追踪。
     *
     * @param descriptor staging 对象身份
     * @deprecated 使用 {@link #retainAfterDelete(DirectUploadStagingDescriptor)} 明确表达语义
     */
    @Deprecated(forRemoval = false)
    public void remove(DirectUploadStagingDescriptor descriptor) {
        retainAfterDelete(descriptor);
    }

    /**
     * 在分片锁内原子刷新 score，并使任何旧清理 claim 失效。
     */
    private void refreshWithPartLock(
            DirectUploadStagingDescriptor descriptor,
            boolean fenceActiveOperation
    ) {
        String member = encode(descriptor);
        try (DirectUploadLockManager.LockHandle ignored = lockManager.acquire(descriptor)) {
            DirectUploadOperationIntentStore.LifecycleFenceKeys fenceKeys =
                    operationIntentStore.lifecycleFenceKeys(descriptor);
            long retentionHours = storageProperties.getDirectUpload().getEffectiveStagingRetentionHours();
            long expiresAt = safeAddMillis(
                    System.currentTimeMillis(),
                    Duration.ofHours(retentionHours).toMillis()
            );
            Long refreshed = stringRedisTemplate.execute(
                    REFRESH_SCRIPT,
                    List.of(
                            EXPIRY_SET_KEY,
                            CLAIM_HASH_KEY,
                            fenceKeys.intentKey(),
                            fenceKeys.executionFenceKey(),
                            fenceKeys.globalFenceKey()
                    ),
                    member,
                    String.valueOf(expiresAt),
                    fenceActiveOperation ? "1" : "0"
            );
            if (refreshed != null && refreshed == -1L) {
                throw new IllegalStateException(
                        "direct-upload staging generation is already finalizing or terminal");
            }
            if (refreshed == null || refreshed != 1L) {
                throw new IllegalStateException("failed to register direct-upload staging lifecycle");
            }
        }
    }

    /**
     * 原子领取一个集群级有界批次，并为每个成员分配独立 fencing token。
     *
     * @param limit 集群当前批次最多领取数量
     * @return 当前实例领取的批次；其他实例在 gate 有效时得到空批次
     */
    @SuppressWarnings("unchecked")
    public ClaimBatch claimExpired(int limit) {
        if (limit <= 0) {
            return ClaimBatch.empty();
        }
        int boundedLimit = Math.min(limit, storageProperties.getDirectUpload().getEffectiveCleanupBatchSize());
        long now = System.currentTimeMillis();
        long claimLeaseMillis = TimeUnit.SECONDS.toMillis(
                storageProperties.getDirectUpload().getEffectiveCleanupClaimLeaseSeconds()
        );
        long leaseUntil = safeAddMillis(now, claimLeaseMillis);
        String batchToken = UUID.randomUUID().toString();

        List<Object> rawClaims = (List<Object>) stringRedisTemplate.execute(
                CLAIM_SCRIPT,
                List.of(EXPIRY_SET_KEY, CLAIM_HASH_KEY, CLAIM_GATE_KEY),
                String.valueOf(now),
                String.valueOf(leaseUntil),
                String.valueOf(boundedLimit),
                batchToken,
                String.valueOf(claimLeaseMillis)
        );
        if (rawClaims == null) {
            throw new IllegalStateException("failed to claim direct-upload staging cleanup batch");
        }
        if (rawClaims.isEmpty()) {
            return ClaimBatch.empty();
        }
        if (rawClaims.size() % 3 != 0) {
            throw new IllegalStateException("malformed direct-upload staging cleanup claim result");
        }

        List<TrackedStaging> claimed = new ArrayList<>(Math.min(boundedLimit, rawClaims.size() / 3));
        for (int index = 0; index + 2 < rawClaims.size() && claimed.size() < boundedLimit; index += 3) {
            String member = rawString(rawClaims.get(index));
            String claimToken = rawString(rawClaims.get(index + 1));
            long originalScore = parseScore(rawClaims.get(index + 2));
            Optional<DirectUploadStagingDescriptor> descriptor = decode(member);
            if (descriptor.isEmpty()) {
                deadLetterCorruptedClaim(member, claimToken, now);
                log.warn("直传 staging 生命周期记录损坏，已隔离到 dead-letter，不执行对象删除");
                continue;
            }
            claimed.add(new TrackedStaging(member, descriptor.get(), claimToken, originalScore));
        }
        return new ClaimBatch(batchToken, List.copyOf(claimed), claimLeaseMillis);
    }

    /**
     * 在执行 provider DELETE 前确认 claim token 仍属于当前批次。
     *
     * @param tracked 已领取记录
     * @return claim 是否仍为当前代
     */
    public boolean isClaimCurrent(TrackedStaging tracked) {
        validateTrackedClaim(tracked);
        Long current = stringRedisTemplate.execute(
                VERIFY_CLAIM_SCRIPT,
                List.of(CLAIM_HASH_KEY),
                tracked.member(),
                tracked.claimToken()
        );
        if (current == null) {
            throw new IllegalStateException("failed to verify direct-upload staging cleanup claim");
        }
        return current == 1L;
    }

    /**
     * DELETE 成功后仅在 fencing token 仍匹配时原子移除 claim 与 lifecycle。
     *
     * @param tracked 已领取记录
     * @return 当前代已完成时为 true；代际已变化时为 false
     */
    public boolean completeClaim(TrackedStaging tracked) {
        validateTrackedClaim(tracked);
        Long completed = stringRedisTemplate.execute(
                COMPLETE_CLAIM_SCRIPT,
                List.of(EXPIRY_SET_KEY, CLAIM_HASH_KEY),
                tracked.member(),
                tracked.claimToken()
        );
        if (completed == null) {
            throw new IllegalStateException("failed to complete direct-upload staging cleanup claim");
        }
        return completed == 1L;
    }

    /**
     * 失败时仅重排当前 claim，并取现有 score 与退避时间的较大值，禁止缩短新代保留期。
     *
     * @param tracked 已领取记录
     * @return 当前代已重排时为 true；代际已变化时为 false
     */
    public boolean rescheduleClaim(TrackedStaging tracked) {
        validateTrackedClaim(tracked);
        long retryAt = safeAddMillis(System.currentTimeMillis(), CLEANUP_RETRY_DELAY_MILLIS);
        Long rescheduled = stringRedisTemplate.execute(
                RESCHEDULE_CLAIM_SCRIPT,
                List.of(EXPIRY_SET_KEY, CLAIM_HASH_KEY),
                tracked.member(),
                tracked.claimToken(),
                String.valueOf(retryAt)
        );
        if (rescheduled == null) {
            throw new IllegalStateException("failed to reschedule direct-upload staging cleanup claim");
        }
        return rescheduled == 1L;
    }

    /**
     * 在一次 Lua 调用中按各自 fencing token 精确重排未处理 claim，缩短批次退出窗口。
     *
     * @param trackedClaims 当前批次尚未处理的 claim
     * @return 仍由当前批次持有并成功重排的 claim 数量
     */
    public int rescheduleClaims(List<TrackedStaging> trackedClaims) {
        if (trackedClaims == null || trackedClaims.isEmpty()) {
            return 0;
        }
        long retryAt = safeAddMillis(System.currentTimeMillis(), CLEANUP_RETRY_DELAY_MILLIS);
        List<String> arguments = new ArrayList<>(1 + trackedClaims.size() * 2);
        arguments.add(String.valueOf(retryAt));
        for (TrackedStaging tracked : trackedClaims) {
            validateTrackedClaim(tracked);
            arguments.add(tracked.member());
            arguments.add(tracked.claimToken());
        }
        Long rescheduled = stringRedisTemplate.execute(
                RESCHEDULE_CLAIMS_SCRIPT,
                List.of(EXPIRY_SET_KEY, CLAIM_HASH_KEY),
                arguments.toArray()
        );
        if (rescheduled == null) {
            throw new IllegalStateException("failed to reschedule direct-upload staging cleanup claims");
        }
        if (rescheduled < 0 || rescheduled > trackedClaims.size()) {
            throw new IllegalStateException("invalid direct-upload staging cleanup reschedule count");
        }
        return rescheduled.intValue();
    }

    /**
     * 完成一个批次后按 token 释放集群 claim gate；失败时依靠 TTL 自动恢复。
     *
     * @param batch 当前领取批次
     */
    public void releaseClaimBatch(ClaimBatch batch) {
        if (batch == null || batch.batchToken() == null || batch.batchToken().isBlank()) {
            return;
        }
        Long released = stringRedisTemplate.execute(
                RELEASE_GATE_SCRIPT,
                List.of(CLAIM_GATE_KEY),
                batch.batchToken()
        );
        if (released == null) {
            throw new IllegalStateException("failed to release direct-upload staging cleanup batch");
        }
    }

    /**
     * 仅在 claim token 匹配时把损坏成员持久隔离，永不根据损坏内容访问 S3。
     */
    private void deadLetterCorruptedClaim(String member, String claimToken, long now) {
        if (member == null || member.isBlank() || claimToken == null || claimToken.isBlank()) {
            throw new IllegalStateException("malformed direct-upload staging cleanup claim identity");
        }
        Long deadLettered = stringRedisTemplate.execute(
                DEAD_LETTER_CLAIM_SCRIPT,
                List.of(EXPIRY_SET_KEY, CLAIM_HASH_KEY, DEAD_LETTER_SET_KEY),
                member,
                claimToken,
                String.valueOf(now)
        );
        if (deadLettered == null) {
            throw new IllegalStateException("failed to dead-letter corrupt direct-upload staging claim");
        }
    }

    /**
     * 使用 URL-safe Base64 对可变文本编码，生成确定性且不含凭据的 tracker 成员。
     */
    private String encode(DirectUploadStagingDescriptor descriptor) {
        validateDescriptor(descriptor);
        return String.join(".",
                MEMBER_VERSION,
                String.valueOf(descriptor.tenantId()),
                String.valueOf(descriptor.partIndex()),
                encodeToken(descriptor.nodeName()),
                encodeToken(descriptor.sessionId()),
                encodeToken(descriptor.objectName()));
    }

    /**
     * 解码并再次验证 tracker 成员，防止损坏数据扩大为任意对象删除。
     */
    private Optional<DirectUploadStagingDescriptor> decode(String member) {
        if (member == null || member.isBlank() || member.length() > MAX_ENCODED_MEMBER_LENGTH) {
            return Optional.empty();
        }
        String[] fields = member.split("\\.", 6);
        if (fields.length != 6 || !MEMBER_VERSION.equals(fields[0])) {
            return Optional.empty();
        }
        try {
            DirectUploadStagingDescriptor descriptor = new DirectUploadStagingDescriptor(
                    Long.parseLong(fields[1]),
                    decodeToken(fields[4]),
                    Integer.parseInt(fields[2]),
                    decodeToken(fields[3]),
                    decodeToken(fields[5])
            );
            validateDescriptor(descriptor);
            return Optional.of(descriptor);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * 校验 tenant/session/part、S3 bucket 名与规范 staging key 完全一致。
     */
    private void validateDescriptor(DirectUploadStagingDescriptor descriptor) {
        if (descriptor == null
                || descriptor.tenantId() == null
                || descriptor.tenantId() < 0
                || descriptor.partIndex() < 0
                || descriptor.nodeName() == null
                || !NODE_PATTERN.matcher(descriptor.nodeName()).matches()
                || descriptor.sessionId() == null
                || !SESSION_PATTERN.matcher(descriptor.sessionId()).matches()) {
            throw new IllegalArgumentException("invalid direct-upload staging descriptor");
        }
        String expectedObjectName = "tenant/" + descriptor.tenantId()
                + "/staging/direct-upload/" + descriptor.sessionId()
                + "/part-" + descriptor.partIndex();
        if (!expectedObjectName.equals(descriptor.objectName())) {
            throw new IllegalArgumentException("direct-upload staging key is not canonical");
        }
    }

    /**
     * 校验 cleanup API 只能接收 claimExpired 生成的完整 fencing 身份。
     */
    private void validateTrackedClaim(TrackedStaging tracked) {
        if (tracked == null
                || tracked.member() == null
                || tracked.member().isBlank()
                || tracked.claimToken() == null
                || tracked.claimToken().isBlank()) {
            throw new IllegalArgumentException("invalid direct-upload staging cleanup claim");
        }
        validateDescriptor(tracked.descriptor());
    }

    /**
     * 编码一个 tracker 文本字段。
     */
    private String encodeToken(String value) {
        return TOKEN_ENCODER.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解码一个 tracker 文本字段。
     */
    private String decodeToken(String value) {
        return new String(TOKEN_DECODER.decode(value), StandardCharsets.UTF_8);
    }

    /**
     * 兼容 Lettuce 返回 String 或 byte[] 的 Lua multi-bulk 元素。
     */
    private String rawString(Object raw) {
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw == null ? "" : String.valueOf(raw);
    }

    /**
     * 把 Redis score 安全解析为 epoch millis。
     */
    private long parseScore(Object raw) {
        String value = rawString(raw);
        try {
            double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed) || parsed < 0 || parsed > Long.MAX_VALUE) {
                throw new NumberFormatException("out of range");
            }
            return (long) parsed;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("malformed direct-upload staging cleanup claim score", e);
        }
    }

    /**
     * 饱和相加 epoch millis，防止异常配置导致 long 溢出。
     */
    private long safeAddMillis(long base, long delta) {
        if (delta <= 0) {
            return base;
        }
        return base > Long.MAX_VALUE - delta ? Long.MAX_VALUE : base + delta;
    }

    /**
     * 集群级领取批次，batch token 用于安全释放 claim gate。
     */
    public record ClaimBatch(String batchToken, List<TrackedStaging> entries, long claimLeaseMillis) {

        /**
         * 兼容仅构造 gate token 与条目的测试或调用点；生产领取路径始终携带真实租约。
         */
        public ClaimBatch(String batchToken, List<TrackedStaging> entries) {
            this(batchToken, entries, 0L);
        }

        /**
         * 返回无需释放 gate 的空批次。
         */
        public static ClaimBatch empty() {
            return new ClaimBatch("", Collections.emptyList(), 0L);
        }
    }

    /**
     * 保留 Redis 原始成员、解析身份、claim token 与领取前 score。
     */
    public record TrackedStaging(
            String member,
            DirectUploadStagingDescriptor descriptor,
            String claimToken,
            long originalScore
    ) {
    }
}
