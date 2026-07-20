package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 使用带 TTL 的单个 Redis key 持久化 direct-upload 分片提升成功证据。
 */
@Component
@RequiredArgsConstructor
public class DirectUploadPromotionReceiptStore {

    private static final String KEY_PREFIX = "storage:direct-upload:promotion-receipt:v1:";
    private static final String MISSING_VALUE = "__DIRECT_UPLOAD_RECEIPT_MISSING__";
    private static final int RECEIPT_SCHEMA_VERSION = 1;
    private static final int MAX_RECORD_ATTEMPTS = 64;
    private static final int MAX_RECEIPT_JSON_LENGTH = 64 * 1024;
    private static final int MAX_RECEIPT_NODES = 256;
    private static final long MIN_RECEIPT_TTL_HOURS = 48;
    private static final String CHECKSUM_ALGORITHM_SHA256 = "SHA-256";
    private static final Pattern SESSION_PATTERN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");
    private static final Pattern NODE_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Base64.Encoder KEY_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final RedisScript<Long> UPSERT_RECEIPT_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[2]) ~= ARGV[5]
                    or redis.call('GET', KEYS[3]) ~= ARGV[6] then
                return -1
            end
            local current = redis.call('GET', KEYS[1])
            if not current then
                if ARGV[1] ~= ARGV[4] then
                    return 0
                end
                local created = redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3], 'NX')
                return created and 1 or 0
            end
            if ARGV[1] == ARGV[4] or current ~= ARGV[1] then
                return 0
            end
            local updated = redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3], 'XX')
            return updated and 1 or 0
            """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final StorageProperties storageProperties;

    /**
     * 读取并校验与当前 tenant/session/part/final identity 完全一致的提升证据。
     *
     * @param part 当前调用方重建的可信分片描述
     * @return 不存在时为空，存在但损坏或 identity 不一致时失败关闭
     */
    public Optional<PromotionReceipt> findValidated(DirectUploadPartDescriptor part) {
        validatePartIdentity(part);
        String receiptJson = stringRedisTemplate.opsForValue().get(receiptKey(part));
        if (receiptJson == null) {
            return Optional.empty();
        }
        PromotionReceipt receipt = parseReceipt(receiptJson);
        validateReceipt(receipt);
        validateReceiptIdentity(receipt, part);
        return Optional.of(receipt);
    }

    /**
     * 原子创建或合并成功节点并刷新 TTL，既有 receipt 的首次目标快照和原 quorum 保持不变。
     *
     * @param part 已完成 final HEAD/hash 校验的分片描述
     * @param successfulNodes 本轮已验证成功的 final 节点
     * @param intent 当前 complete 执行代际，receipt CAS 会与其原子复核
     * @return 本轮成功持久化后的 receipt
     */
    public PromotionReceipt recordSuccess(
            DirectUploadPartDescriptor part,
            List<String> successfulNodes,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        validatePartIdentity(part);
        validateIntent(intent);
        List<String> normalizedSuccesses = normalizeNodes(successfulNodes, "successful nodes");

        String key = receiptKey(part);
        String ttlMillis = String.valueOf(receiptTtlMillis());
        for (int attempt = 0; attempt < MAX_RECORD_ATTEMPTS; attempt++) {
            String existingJson = stringRedisTemplate.opsForValue().get(key);
            PromotionReceipt nextReceipt;
            String expectedJson;
            if (existingJson == null) {
                List<String> initialTargets = normalizeNodes(part.targetNodes(), "initial target nodes");
                if (normalizedSuccesses.size() < part.requiredQuorum()) {
                    throw new IllegalArgumentException("direct-upload receipt successes do not reach quorum");
                }
                nextReceipt = new PromotionReceipt(
                        RECEIPT_SCHEMA_VERSION,
                        part.tenantId(),
                        part.sessionId(),
                        part.partIndex(),
                        part.finalObjectName(),
                        part.size(),
                        normalizeHash(part.plainHash()),
                        normalizeHash(part.cipherHash()),
                        CHECKSUM_ALGORITHM_SHA256,
                        initialTargets,
                        normalizedSuccesses,
                        part.requiredQuorum()
                );
                expectedJson = MISSING_VALUE;
            } else {
                PromotionReceipt existing = parseReceipt(existingJson);
                validateReceipt(existing);
                validateReceiptIdentity(existing, part);
                if (normalizedSuccesses.size() < existing.requiredQuorum()) {
                    throw new IllegalArgumentException("direct-upload receipt successes do not reach original quorum");
                }
                LinkedHashSet<String> mergedSuccesses = new LinkedHashSet<>(existing.successfulNodes());
                mergedSuccesses.addAll(normalizedSuccesses);
                nextReceipt = existing.withSuccessfulNodes(List.copyOf(mergedSuccesses));
                expectedJson = existingJson;
            }

            validateReceipt(nextReceipt);
            String replacementJson = serializeReceipt(nextReceipt);
            long updated = executeReceiptCas(
                    key,
                    expectedJson,
                    replacementJson,
                    ttlMillis,
                    intent
            );
            if (updated == 1L) {
                return nextReceipt;
            }
        }
        throw new IllegalStateException("direct-upload promotion receipt update contention exceeded limit");
    }

    /**
     * 使用首次读取的权威 receipt 快照合并 retry 结果；快照过期或 immutable quorum 漂移时失败关闭。
     *
     * @param part 当前可信分片描述
     * @param authoritativeReceipt final retry 开始前读取并用于 quorum 校验的权威快照
     * @param successfulNodes 本轮已通过完整内容校验的节点
     * @param intent 当前 complete 执行代际
     * @return 保持原始 target snapshot 和 quorum 的更新 receipt
     */
    public PromotionReceipt recordRetrySuccess(
            DirectUploadPartDescriptor part,
            PromotionReceipt authoritativeReceipt,
            List<String> successfulNodes,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        validatePartIdentity(part);
        validateReceipt(authoritativeReceipt);
        validateReceiptIdentity(authoritativeReceipt, part);
        validateIntent(intent);
        List<String> normalizedSuccesses = normalizeNodes(successfulNodes, "successful nodes");
        if (normalizedSuccesses.size() < authoritativeReceipt.requiredQuorum()) {
            throw new IllegalArgumentException(
                    "direct-upload retry successes do not reach authoritative quorum");
        }

        String key = receiptKey(part);
        String ttlMillis = String.valueOf(receiptTtlMillis());
        for (int attempt = 0; attempt < MAX_RECORD_ATTEMPTS; attempt++) {
            String existingJson = stringRedisTemplate.opsForValue().get(key);
            if (existingJson == null) {
                throw new IllegalStateException(
                        "authoritative direct-upload promotion receipt expired during retry");
            }
            PromotionReceipt existing = parseReceipt(existingJson);
            validateReceipt(existing);
            validateReceiptIdentity(existing, part);
            validateImmutableSnapshot(existing, authoritativeReceipt);

            LinkedHashSet<String> mergedSuccesses = new LinkedHashSet<>(existing.successfulNodes());
            mergedSuccesses.addAll(normalizedSuccesses);
            PromotionReceipt replacement = existing.withSuccessfulNodes(List.copyOf(mergedSuccesses));
            validateReceipt(replacement);
            long updated = executeReceiptCas(
                    key,
                    existingJson,
                    serializeReceipt(replacement),
                    ttlMillis,
                    intent
            );
            if (updated == 1L) {
                return replacement;
            }
        }
        throw new IllegalStateException("direct-upload promotion receipt update contention exceeded limit");
    }

    /**
     * 在同一 Lua 原子边界内复核执行 fence 并执行 receipt NX/CAS。
     */
    private long executeReceiptCas(
            String key,
            String expectedJson,
            String replacementJson,
            String ttlMillis,
            DirectUploadOperationIntentStore.OperationIntent intent
    ) {
        Long updated = stringRedisTemplate.execute(
                UPSERT_RECEIPT_SCRIPT,
                List.of(key, intent.key(), intent.fenceKey()),
                expectedJson,
                replacementJson,
                ttlMillis,
                MISSING_VALUE,
                intent.serializedValue(),
                String.valueOf(intent.executionEpoch())
        );
        if (updated == null) {
            throw new IllegalStateException("failed to persist direct-upload promotion receipt");
        }
        if (updated == -1L) {
            throw new IllegalStateException("direct-upload receipt writer lost its execution fence");
        }
        return updated;
    }

    /**
     * 确认 retry 期间 immutable receipt 身份、首次目标快照和原 quorum 未变化。
     */
    private void validateImmutableSnapshot(
            PromotionReceipt current,
            PromotionReceipt authoritativeReceipt
    ) {
        if (current.schemaVersion() != authoritativeReceipt.schemaVersion()
                || !Objects.equals(current.tenantId(), authoritativeReceipt.tenantId())
                || !Objects.equals(current.sessionId(), authoritativeReceipt.sessionId())
                || current.partIndex() != authoritativeReceipt.partIndex()
                || !Objects.equals(current.finalObjectName(), authoritativeReceipt.finalObjectName())
                || current.size() != authoritativeReceipt.size()
                || !Objects.equals(current.plainHash(), authoritativeReceipt.plainHash())
                || !Objects.equals(current.cipherHash(), authoritativeReceipt.cipherHash())
                || !Objects.equals(current.checksumAlgorithm(), authoritativeReceipt.checksumAlgorithm())
                || !Objects.equals(current.initialTargetNodes(), authoritativeReceipt.initialTargetNodes())
                || current.requiredQuorum() != authoritativeReceipt.requiredQuorum()) {
            throw new IllegalStateException(
                    "authoritative direct-upload promotion receipt changed during retry");
        }
    }

    /**
     * 校验调用方传入的 fence 快照具备 receipt Lua 所需的完整字段。
     */
    private void validateIntent(DirectUploadOperationIntentStore.OperationIntent intent) {
        if (intent == null
                || intent.key() == null
                || intent.key().isBlank()
                || intent.fenceKey() == null
                || intent.fenceKey().isBlank()
                || intent.serializedValue() == null
                || intent.serializedValue().isBlank()
                || intent.executionEpoch() <= 0
                || intent.mode() != DirectUploadOperationIntentStore.OperationMode.COMPLETE) {
            throw new IllegalArgumentException("invalid direct-upload receipt execution intent");
        }
    }

    /**
     * 将 tenant/session/part 的规范文本编码为不暴露原始 session 的稳定 URL-safe Redis key。
     */
    String receiptKey(DirectUploadPartDescriptor part) {
        Objects.requireNonNull(part, "part");
        String identity = part.tenantId() + "\n" + part.sessionId() + "\n" + part.partIndex();
        return KEY_PREFIX + KEY_ENCODER.encodeToString(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * receipt 至少保留 48 小时，并在 staging 配置更长时复用其保留周期。
     */
    private long receiptTtlMillis() {
        StorageProperties.DirectUploadConfig directUpload = storageProperties.getDirectUpload();
        long configuredHours = directUpload == null
                ? MIN_RECEIPT_TTL_HOURS
                : directUpload.getEffectiveStagingRetentionHours();
        return Duration.ofHours(Math.max(MIN_RECEIPT_TTL_HOURS, configuredHours)).toMillis();
    }

    /**
     * 严格解析单个 JSON 对象，拒绝重复字段、未知字段、尾随内容和超长损坏值。
     */
    private PromotionReceipt parseReceipt(String receiptJson) {
        if (receiptJson == null
                || receiptJson.isBlank()
                || receiptJson.length() > MAX_RECEIPT_JSON_LENGTH) {
            throw new IllegalStateException("direct-upload promotion receipt is corrupt");
        }
        try (JsonParser parser = objectMapper.createParser(receiptJson)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            PromotionReceipt receipt = objectMapper.readerFor(PromotionReceipt.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(parser);
            if (parser.nextToken() != null) {
                throw new IllegalStateException("direct-upload promotion receipt has trailing content");
            }
            return receipt;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("direct-upload promotion receipt is corrupt", e);
        }
    }

    /**
     * 将经过完整校验的 receipt 序列化为单个 JSON Redis value。
     */
    private String serializeReceipt(PromotionReceipt receipt) {
        try {
            return objectMapper.writeValueAsString(receipt);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize direct-upload promotion receipt", e);
        }
    }

    /**
     * 校验当前 descriptor 的 final identity，避免 receipt key 被非规范路径复用。
     */
    private void validatePartIdentity(DirectUploadPartDescriptor part) {
        if (part == null
                || part.tenantId() == null
                || part.tenantId() < 0
                || part.partIndex() < 0
                || part.size() <= 0
                || part.sessionId() == null
                || !SESSION_PATTERN.matcher(part.sessionId()).matches()
                || part.requiredQuorum() <= 0
                || part.targetNodes() == null
                || part.targetNodes().isEmpty()
                || !SHA256_PATTERN.matcher(normalizeHash(part.plainHash())).matches()
                || !SHA256_PATTERN.matcher(normalizeHash(part.cipherHash())).matches()
                || !CHECKSUM_ALGORITHM_SHA256.equalsIgnoreCase(normalizeChecksum(part.checksumAlgorithm()))) {
            throw new IllegalArgumentException("invalid direct-upload promotion receipt identity");
        }
        String expectedFinalObjectName = "tenant/" + part.tenantId() + "/" + normalizeHash(part.cipherHash());
        if (!expectedFinalObjectName.equals(part.finalObjectName())) {
            throw new IllegalArgumentException("direct-upload final object identity is not canonical");
        }
    }

    /**
     * 校验 Redis value 自身结构和持久化不变量，不允许低 quorum 或损坏节点列表成为可信证据。
     */
    private void validateReceipt(PromotionReceipt receipt) {
        if (receipt == null
                || receipt.schemaVersion() != RECEIPT_SCHEMA_VERSION
                || receipt.tenantId() == null
                || receipt.tenantId() < 0
                || receipt.partIndex() < 0
                || receipt.size() <= 0
                || receipt.sessionId() == null
                || !SESSION_PATTERN.matcher(receipt.sessionId()).matches()
                || receipt.requiredQuorum() <= 0
                || receipt.plainHash() == null
                || !SHA256_PATTERN.matcher(receipt.plainHash()).matches()
                || receipt.cipherHash() == null
                || !SHA256_PATTERN.matcher(receipt.cipherHash()).matches()
                || !CHECKSUM_ALGORITHM_SHA256.equals(receipt.checksumAlgorithm())) {
            throw new IllegalStateException("direct-upload promotion receipt is invalid");
        }

        String expectedFinalObjectName = "tenant/" + receipt.tenantId() + "/" + receipt.cipherHash();
        if (!expectedFinalObjectName.equals(receipt.finalObjectName())) {
            throw new IllegalStateException("direct-upload promotion receipt final identity is invalid");
        }
        validateStoredNodeList(receipt.initialTargetNodes(), "initial target nodes");
        validateStoredNodeList(receipt.successfulNodes(), "successful nodes");
        if (receipt.requiredQuorum() > receipt.initialTargetNodes().size()
                || receipt.successfulNodes().size() < receipt.requiredQuorum()) {
            throw new IllegalStateException("direct-upload promotion receipt quorum is invalid");
        }
    }

    /**
     * 将已解析 receipt 与当前可信 descriptor 逐字段比对，禁止跨租户、跨分片或跨对象复用。
     */
    private void validateReceiptIdentity(
            PromotionReceipt receipt,
            DirectUploadPartDescriptor part
    ) {
        if (!Objects.equals(receipt.tenantId(), part.tenantId())
                || !Objects.equals(receipt.sessionId(), part.sessionId())
                || receipt.partIndex() != part.partIndex()
                || !Objects.equals(receipt.finalObjectName(), part.finalObjectName())
                || receipt.size() != part.size()
                || !Objects.equals(receipt.plainHash(), normalizeHash(part.plainHash()))
                || !Objects.equals(receipt.cipherHash(), normalizeHash(part.cipherHash()))
                || !Objects.equals(receipt.checksumAlgorithm(), CHECKSUM_ALGORITHM_SHA256)) {
            throw new IllegalStateException("direct-upload promotion receipt identity mismatch");
        }
    }

    /**
     * 对写入节点列表执行有序去重和低风险字符校验。
     */
    private List<String> normalizeNodes(List<String> nodes, String fieldName) {
        if (nodes == null || nodes.isEmpty() || nodes.size() > MAX_RECEIPT_NODES) {
            throw new IllegalArgumentException("invalid direct-upload receipt " + fieldName);
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String node : nodes) {
            if (node == null || !NODE_PATTERN.matcher(node).matches()) {
                throw new IllegalArgumentException("invalid direct-upload receipt " + fieldName);
            }
            normalized.add(node);
        }
        return List.copyOf(normalized);
    }

    /**
     * 校验 Redis 中的节点快照已规范化、无重复且有界。
     */
    private void validateStoredNodeList(List<String> nodes, String fieldName) {
        if (nodes == null
                || nodes.isEmpty()
                || nodes.size() > MAX_RECEIPT_NODES
                || nodes.stream().anyMatch(node -> node == null || !NODE_PATTERN.matcher(node).matches())
                || new LinkedHashSet<>(nodes).size() != nodes.size()) {
            throw new IllegalStateException("direct-upload promotion receipt " + fieldName + " is invalid");
        }
    }

    /**
     * 规范化 direct-upload SHA-256 identity。
     */
    private String normalizeHash(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化 checksum algorithm 文本。
     */
    private String normalizeChecksum(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * receipt JSON 合同，首次目标快照和原 requiredQuorum 在后续合并中保持不变。
     */
    public record PromotionReceipt(
            int schemaVersion,
            Long tenantId,
            String sessionId,
            int partIndex,
            String finalObjectName,
            long size,
            String plainHash,
            String cipherHash,
            String checksumAlgorithm,
            List<String> initialTargetNodes,
            List<String> successfulNodes,
            int requiredQuorum
    ) {

        /**
         * 固化反序列化列表，避免调用方在校验后修改 receipt 内容。
         */
        public PromotionReceipt {
            initialTargetNodes = initialTargetNodes == null ? null : List.copyOf(initialTargetNodes);
            successfulNodes = successfulNodes == null ? null : List.copyOf(successfulNodes);
        }

        /**
         * 只合并成功节点，不改变首次 target snapshot、原 quorum 或 final identity。
         */
        PromotionReceipt withSuccessfulNodes(List<String> mergedSuccessfulNodes) {
            return new PromotionReceipt(
                    schemaVersion,
                    tenantId,
                    sessionId,
                    partIndex,
                    finalObjectName,
                    size,
                    plainHash,
                    cipherHash,
                    checksumAlgorithm,
                    initialTargetNodes,
                    mergedSuccessfulNodes,
                    requiredQuorum
            );
        }
    }
}
