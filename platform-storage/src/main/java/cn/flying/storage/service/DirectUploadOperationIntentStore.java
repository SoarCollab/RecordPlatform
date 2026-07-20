package cn.flying.storage.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 以不可变 Redis 记录绑定 direct-upload 分片的终态操作和内容代际。
 *
 * <p>Redisson 锁只提供正常运行时互斥；进程暂停或 watchdog 续约失效后，旧持有者仍可能恢复。
 * 活跃期意图不设置 TTL，终态 tombstone 落盘后才按完整重放防护周期退休；全局 epoch 永久单调，
 * 防止分片 key 到期重建后旧持有者重新获得相同 fencing token。</p>
 */
@Component
@RequiredArgsConstructor
public class DirectUploadOperationIntentStore {

    private static final String KEY_PREFIX = "storage:direct-upload:operation-intent:v1:";
    private static final String GLOBAL_FENCE_KEY =
            "storage:direct-upload:operation-intent:v1:global-fence";
    private static final String VALUE_VERSION = "v1";
    private static final String CHECKSUM_ALGORITHM_SHA256 = "SHA-256";
    private static final int MAX_ETAG_LENGTH = 255;
    private static final Pattern SESSION_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern NODE_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern EPOCH_PATTERN = Pattern.compile("^[1-9][0-9]{0,18}$");
    private static final String RESULT_OK_PREFIX = "OK\n";
    private static final String RESULT_CONFLICT_PREFIX = "CONFLICT\n";
    private static final String RESULT_SUPERSEDED = "SUPERSEDED";

    private static final RedisScript<String> BEGIN_EXACT_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], ARGV[1])
                current = ARGV[1]
            end
            local epoch = redis.call('INCR', KEYS[3])
            if current ~= ARGV[1] then
                local ttl = redis.call('PTTL', KEYS[1])
                if ttl > 0 then
                    redis.call('SET', KEYS[2], tostring(epoch), 'PX', ttl)
                else
                    redis.call('SET', KEYS[2], tostring(epoch))
                end
                return 'CONFLICT\\n' .. tostring(epoch) .. '\\n' .. current
            end
            redis.call('PERSIST', KEYS[1])
            redis.call('SET', KEYS[2], tostring(epoch))
            return 'OK\\n' .. tostring(epoch) .. '\\n' .. current
            """, String.class);

    private static final RedisScript<String> FOLLOW_OR_CREATE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], ARGV[1])
                current = ARGV[1]
            end
            local epoch = redis.call('INCR', KEYS[3])
            redis.call('PERSIST', KEYS[1])
            redis.call('SET', KEYS[2], tostring(epoch))
            return 'OK\\n' .. tostring(epoch) .. '\\n' .. current
            """, String.class);

    private static final RedisScript<String> FOLLOW_OR_CREATE_CLEANUP_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[4], ARGV[2]) ~= ARGV[3] then
                return 'SUPERSEDED'
            end
            local current = redis.call('GET', KEYS[1])
            if not current then
                redis.call('SET', KEYS[1], ARGV[1])
                current = ARGV[1]
            end
            local epoch = redis.call('INCR', KEYS[3])
            redis.call('PERSIST', KEYS[1])
            redis.call('SET', KEYS[2], tostring(epoch))
            return 'OK\\n' .. tostring(epoch) .. '\\n' .. current
            """, String.class);

    private static final RedisScript<Long> VERIFY_FENCE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return 0
            end
            return 1
            """, Long.class);

    private static final RedisScript<Long> RETIRE_INTENT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            if redis.call('GET', KEYS[2]) ~= ARGV[2] then
                return 0
            end
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[2], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 原子声明当前分片只能按给定内容代际执行 complete。
     *
     * @param part 后端可信计划重建的完整分片描述
     * @return 后续每个对象存储副作用都必须复核的不可变意图
     */
    public OperationIntent beginComplete(DirectUploadPartDescriptor part) {
        validatePart(part);
        String descriptorFingerprint = descriptorFingerprint(part.stagingDescriptor());
        String generationFingerprint = sha256Hex(String.join("\0",
                "complete-v1",
                descriptorFingerprint,
                part.finalObjectName(),
                String.valueOf(part.size()),
                normalizeEtag(part.eTag()),
                normalizeHash(part.plainHash()),
                normalizeHash(part.cipherHash()),
                CHECKSUM_ALGORITHM_SHA256
        ));
        return beginExact(
                part.stagingDescriptor(),
                OperationMode.COMPLETE,
                descriptorFingerprint,
                generationFingerprint
        );
    }

    /**
     * 原子声明当前分片只能按给定 staging 代际执行 abort。
     *
     * @param descriptor staging 对象身份
     * @return 后续每个对象存储副作用都必须复核的不可变意图
     */
    public OperationIntent beginAbort(DirectUploadStagingDescriptor descriptor) {
        return followOrCreate(descriptor, OperationMode.ABORT);
    }

    /**
     * 生命周期清理优先继承既有 complete/abort 意图；尚无终态时原子声明 cleanup。
     *
     * @param descriptor 已通过 tracker 解码和 claim fencing 的 staging 身份
     * @return 后续每次 DELETE 前必须复核的既有或新建意图
     */
    public Optional<OperationIntent> followOrCreateCleanup(
            DirectUploadStagingTracker.TrackedStaging tracked
    ) {
        if (tracked == null
                || tracked.member() == null
                || tracked.member().isBlank()
                || tracked.claimToken() == null
                || tracked.claimToken().isBlank()) {
            throw new IllegalArgumentException("invalid direct-upload cleanup claim intent");
        }
        DirectUploadStagingDescriptor descriptor = tracked.descriptor();
        String descriptorFingerprint = descriptorFingerprint(descriptor);
        String key = intentKey(descriptor);
        String fenceKey = fenceKey(key);
        OperationIntent cleanup = newIntent(
                key,
                fenceKey,
                OperationMode.CLEANUP,
                descriptorFingerprint,
                descriptorFingerprint,
                0L
        );
        String result = stringRedisTemplate.execute(
                FOLLOW_OR_CREATE_CLEANUP_SCRIPT,
                List.of(
                        key,
                        fenceKey,
                        GLOBAL_FENCE_KEY,
                        DirectUploadStagingTracker.CLAIM_HASH_KEY
                ),
                cleanup.serializedValue(),
                tracked.member(),
                tracked.claimToken()
        );
        if (RESULT_SUPERSEDED.equals(result)) {
            return Optional.empty();
        }
        return Optional.of(parseBeginResult(
                result,
                key,
                fenceKey,
                descriptorFingerprint,
                false
        ));
    }

    /**
     * 在对象存储副作用前精确复核 Redis 中仍是调用方持有的不可变意图。
     *
     * @param intent begin/follow 返回的原始意图
     */
    public void verify(OperationIntent intent) {
        validateIntentObject(intent);
        Long verified = stringRedisTemplate.execute(
                VERIFY_FENCE_SCRIPT,
                List.of(intent.key(), intent.fenceKey()),
                intent.serializedValue(),
                String.valueOf(intent.executionEpoch())
        );
        if (verified == null || verified != 1L) {
            throw new IllegalStateException("direct-upload operation intent or execution fence changed");
        }
    }

    /**
     * 终态 tombstone 或到期清理已持久化后，为每分片意图和 fence 设置有界保留期。
     *
     * <p>全局单调 fence 永不按分片增长；意图过期后即使会话身份被异常复用，新 token 也不会
     * 与旧持有者碰撞，而旧持有者因 key 缺失无法通过 verify。</p>
     *
     * @param intent 当前执行代际
     * @param retention 安全保留期，通常与 staging 重放防护周期相同
     */
    public void retire(OperationIntent intent, Duration retention) {
        validateIntentObject(intent);
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("direct-upload intent retention must be positive");
        }
        long retentionMillis;
        try {
            retentionMillis = retention.toMillis();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("direct-upload intent retention is too large", e);
        }
        if (retentionMillis <= 0) {
            throw new IllegalArgumentException("direct-upload intent retention must be positive");
        }
        Long retired = stringRedisTemplate.execute(
                RETIRE_INTENT_SCRIPT,
                List.of(intent.key(), intent.fenceKey()),
                intent.serializedValue(),
                String.valueOf(intent.executionEpoch()),
                String.valueOf(retentionMillis)
        );
        if (retired == null || retired != 1L) {
            throw new IllegalStateException("direct-upload operation intent could not be retired by current fence");
        }
    }

    /**
     * 以 SET NX 创建精确意图；完全相同的重试幂等成功，任何模式或代际冲突均失败关闭。
     */
    private OperationIntent beginExact(
            DirectUploadStagingDescriptor descriptor,
            OperationMode mode,
            String descriptorFingerprint,
            String generationFingerprint
    ) {
        String key = intentKey(descriptor);
        String fenceKey = fenceKey(key);
        OperationIntent requested = newIntent(
                key,
                fenceKey,
                mode,
                descriptorFingerprint,
                generationFingerprint,
                0L
        );
        String result = stringRedisTemplate.execute(
                BEGIN_EXACT_SCRIPT,
                List.of(key, fenceKey, GLOBAL_FENCE_KEY),
                requested.serializedValue()
        );
        return parseBeginResult(
                result,
                key,
                fenceKey,
                descriptorFingerprint,
                true
        );
    }

    /**
     * abort 继承已存在的 complete/cleanup 终态并推进执行代际；无意图时创建 ABORT。
     */
    private OperationIntent followOrCreate(
            DirectUploadStagingDescriptor descriptor,
            OperationMode createMode
    ) {
        String descriptorFingerprint = descriptorFingerprint(descriptor);
        String key = intentKey(descriptor);
        String fenceKey = fenceKey(key);
        OperationIntent requested = newIntent(
                key,
                fenceKey,
                createMode,
                descriptorFingerprint,
                descriptorFingerprint,
                0L
        );
        String result = stringRedisTemplate.execute(
                FOLLOW_OR_CREATE_SCRIPT,
                List.of(key, fenceKey, GLOBAL_FENCE_KEY),
                requested.serializedValue()
        );
        return parseBeginResult(
                result,
                key,
                fenceKey,
                descriptorFingerprint,
                false
        );
    }

    /**
     * 解析原子 begin/follow 脚本结果，冲突操作虽已推进 fence 仍必须失败关闭。
     */
    private OperationIntent parseBeginResult(
            String result,
            String key,
            String fenceKey,
            String descriptorFingerprint,
            boolean rejectConflict
    ) {
        if (result == null) {
            throw new IllegalStateException("failed to persist direct-upload operation intent");
        }
        boolean conflict = result.startsWith(RESULT_CONFLICT_PREFIX);
        String prefix = conflict ? RESULT_CONFLICT_PREFIX : RESULT_OK_PREFIX;
        if (!result.startsWith(prefix)) {
            throw new IllegalStateException("direct-upload operation intent script result is corrupt");
        }
        String payload = result.substring(prefix.length());
        int separator = payload.indexOf('\n');
        if (separator <= 0 || separator == payload.length() - 1) {
            throw new IllegalStateException("direct-upload operation intent script result is corrupt");
        }
        String epochValue = payload.substring(0, separator);
        if (!EPOCH_PATTERN.matcher(epochValue).matches()) {
            throw new IllegalStateException("direct-upload operation fence is corrupt");
        }
        long epoch;
        try {
            epoch = Long.parseLong(epochValue);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("direct-upload operation fence is corrupt", e);
        }
        String serializedValue = payload.substring(separator + 1);
        OperationIntent stored = parseAndValidate(
                key,
                fenceKey,
                serializedValue,
                descriptorFingerprint,
                epoch
        );
        if (conflict && rejectConflict) {
            throw new IllegalStateException("conflicting direct-upload operation intent");
        }
        if (conflict) {
            throw new IllegalStateException("direct-upload cleanup intent script returned a conflict");
        }
        return stored;
    }

    /**
     * 构建 tenant/session/part 维度的低敏稳定 key，不在 Redis key 中暴露原始会话文本。
     */
    String intentKey(DirectUploadStagingDescriptor descriptor) {
        validateDescriptor(descriptor);
        return KEY_PREFIX + sha256Hex(String.join("\0",
                String.valueOf(descriptor.tenantId()),
                descriptor.sessionId(),
                String.valueOf(descriptor.partIndex())
        ));
    }

    /**
     * 计算完整 staging 身份指纹，使同一分片编号不能换节点或对象路径后继承旧意图。
     */
    String descriptorFingerprint(DirectUploadStagingDescriptor descriptor) {
        validateDescriptor(descriptor);
        return sha256Hex(String.join("\0",
                "staging-v1",
                String.valueOf(descriptor.tenantId()),
                descriptor.sessionId(),
                String.valueOf(descriptor.partIndex()),
                descriptor.nodeName(),
                descriptor.objectName()
        ));
    }

    /**
     * 返回 tracker 原子刷新生命周期时所需的 intent、执行 fence 和全局单调序列 key。
     */
    LifecycleFenceKeys lifecycleFenceKeys(DirectUploadStagingDescriptor descriptor) {
        String key = intentKey(descriptor);
        return new LifecycleFenceKeys(key, fenceKey(key), GLOBAL_FENCE_KEY);
    }

    /**
     * 创建格式固定的意图值，避免 JSON 宽松解析和未知字段影响 fencing。
     */
    private OperationIntent newIntent(
            String key,
            String fenceKey,
            OperationMode mode,
            String descriptorFingerprint,
            String generationFingerprint,
            long executionEpoch
    ) {
        String serializedValue = String.join(
                "|",
                VALUE_VERSION,
                mode.name(),
                descriptorFingerprint,
                generationFingerprint
        );
        return new OperationIntent(
                key,
                fenceKey,
                serializedValue,
                mode,
                descriptorFingerprint,
                generationFingerprint,
                executionEpoch
        );
    }

    /**
     * 严格解析 Redis value，并确认其 staging 身份与当前 tracker/调用方描述一致。
     */
    private OperationIntent parseAndValidate(
            String key,
            String fenceKey,
            String serializedValue,
            String expectedDescriptorFingerprint,
            long executionEpoch
    ) {
        if (serializedValue == null || serializedValue.length() < 138 || serializedValue.length() > 141) {
            throw new IllegalStateException("direct-upload operation intent is corrupt");
        }
        String[] fields = serializedValue.split("\\|", -1);
        if (fields.length != 4 || !VALUE_VERSION.equals(fields[0])) {
            throw new IllegalStateException("direct-upload operation intent is corrupt");
        }
        OperationMode mode;
        try {
            mode = OperationMode.valueOf(fields[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("direct-upload operation intent is corrupt", e);
        }
        if (!FINGERPRINT_PATTERN.matcher(fields[2]).matches()
                || !FINGERPRINT_PATTERN.matcher(fields[3]).matches()) {
            throw new IllegalStateException("direct-upload operation intent is corrupt");
        }
        if (!Objects.equals(expectedDescriptorFingerprint, fields[2])) {
            throw new IllegalStateException("direct-upload operation intent staging identity mismatch");
        }
        if (executionEpoch <= 0) {
            throw new IllegalStateException("direct-upload operation fence is corrupt");
        }
        return new OperationIntent(
                key,
                fenceKey,
                serializedValue,
                mode,
                fields[2],
                fields[3],
                executionEpoch
        );
    }

    /**
     * 防止测试替身或调用方伪造不完整意图绕过精确 Redis 比对。
     */
    private void validateIntentObject(OperationIntent intent) {
        if (intent == null
                || intent.key() == null
                || !intent.key().startsWith(KEY_PREFIX)
                || intent.fenceKey() == null
                || !intent.fenceKey().equals(fenceKey(intent.key()))
                || intent.serializedValue() == null
                || intent.mode() == null
                || intent.executionEpoch() <= 0
                || !FINGERPRINT_PATTERN.matcher(nullToEmpty(intent.descriptorFingerprint())).matches()
                || !FINGERPRINT_PATTERN.matcher(nullToEmpty(intent.generationFingerprint())).matches()) {
            throw new IllegalArgumentException("invalid direct-upload operation intent");
        }
        OperationIntent parsed = parseAndValidate(
                intent.key(),
                intent.fenceKey(),
                intent.serializedValue(),
                intent.descriptorFingerprint(),
                intent.executionEpoch()
        );
        if (parsed.mode() != intent.mode()
                || !parsed.generationFingerprint().equals(intent.generationFingerprint())) {
            throw new IllegalArgumentException("invalid direct-upload operation intent");
        }
    }

    /**
     * 校验完整 complete 身份；目标拓扑和 quorum 刻意不参与代际，允许 receipt 重试时拓扑变化。
     */
    private void validatePart(DirectUploadPartDescriptor part) {
        if (part == null
                || part.size() <= 0
                || !Objects.equals(part.sourceNode(), part.stagingDescriptor().nodeName())
                || !Objects.equals(part.stagingObjectName(), part.stagingDescriptor().objectName())
                || !SHA256_PATTERN.matcher(normalizeHash(part.plainHash())).matches()
                || !SHA256_PATTERN.matcher(normalizeHash(part.cipherHash())).matches()
                || !CHECKSUM_ALGORITHM_SHA256.equalsIgnoreCase(normalizeChecksum(part.checksumAlgorithm()))
                || !isSafeEtag(normalizeEtag(part.eTag()))) {
            throw new IllegalArgumentException("invalid direct-upload complete intent identity");
        }
        validateDescriptor(part.stagingDescriptor());
        String expectedFinalObjectName = "tenant/" + part.tenantId() + "/" + normalizeHash(part.cipherHash());
        if (!expectedFinalObjectName.equals(part.finalObjectName())) {
            throw new IllegalArgumentException("direct-upload final object identity is not canonical");
        }
    }

    /**
     * 校验 tenant/session/part、bucket 与规范 staging key，拒绝把损坏 Redis 数据扩大为任意 S3 操作。
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
     * 校验 ETag 只包含可见 ASCII 且能安全进入稳定代际文本。
     */
    private boolean isSafeEtag(String value) {
        if (value.isEmpty() || value.length() > MAX_ETAG_LENGTH) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x21 || character > 0x7E) {
                return false;
            }
        }
        return true;
    }

    /**
     * 计算小写 SHA-256 十六进制指纹。
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(CHECKSUM_ALGORITHM_SHA256);
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * 去掉 provider ETag 的成对引号和外围空白，使等价重试使用同一代际。
     */
    private String normalizeEtag(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() >= 2
                && normalized.charAt(0) == '\"'
                && normalized.charAt(normalized.length() - 1) == '\"') {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * 规范化 SHA-256 文本。
     */
    private String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化 checksum 算法文本。
     */
    private String normalizeChecksum(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * 将空值转换为空串，供正则校验使用。
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 为不可变意图 key 派生单调执行代际 key。
     */
    private String fenceKey(String intentKey) {
        return intentKey + ":fence";
    }

    /**
     * 同一分片允许的唯一终态操作。
     */
    public enum OperationMode {
        COMPLETE,
        ABORT,
        CLEANUP
    }

    /**
     * begin/follow 返回的不可变意图快照；serializedValue 必须逐字节匹配 Redis 当前值。
     */
    public record OperationIntent(
            String key,
            String fenceKey,
            String serializedValue,
            OperationMode mode,
            String descriptorFingerprint,
            String generationFingerprint,
            long executionEpoch
    ) {
    }

    /**
     * tracker 与 intent store 共享的低敏 Redis key 集合。
     */
    record LifecycleFenceKeys(
            String intentKey,
            String executionFenceKey,
            String globalFenceKey
    ) {
    }
}
