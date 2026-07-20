package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 验证 promotion receipt 的租户隔离、严格校验、TTL 和原子幂等合并语义。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectUploadPromotionReceiptStore Unit Tests")
class DirectUploadPromotionReceiptStoreTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> redisValues = new ConcurrentHashMap<>();
    private final List<ScriptCall> scriptCalls = new CopyOnWriteArrayList<>();
    private final AtomicBoolean expireReceiptBeforeNextCas = new AtomicBoolean(false);

    private StorageProperties storageProperties;
    private DirectUploadPromotionReceiptStore store;
    private DirectUploadOperationIntentStore.OperationIntent intent;

    /**
     * 使用内存 map 模拟 Redis GET 与 Lua CAS，使并发测试仍验证真实调用合同。
     */
    @BeforeEach
    void setUp() {
        storageProperties = new StorageProperties();
        store = new DirectUploadPromotionReceiptStore(redisTemplate, objectMapper, storageProperties);
        intent = new DirectUploadOperationIntentStore.OperationIntent(
                "storage:direct-upload:operation-intent:v1:" + "a".repeat(64),
                "storage:direct-upload:operation-intent:v1:" + "a".repeat(64) + ":fence",
                "v1|COMPLETE|" + "b".repeat(64) + "|" + "c".repeat(64),
                DirectUploadOperationIntentStore.OperationMode.COMPLETE,
                "b".repeat(64),
                "c".repeat(64),
                1L
        );
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(invocation ->
                redisValues.get(invocation.getArgument(0)));
        stubAtomicReceiptScript();
    }

    @Test
    @DisplayName("key should be tenant isolated and one JSON value should live for at least 48 hours")
    void shouldUseTenantIsolatedSingleKeyJsonAndSafeTtl() throws Exception {
        storageProperties.getDirectUpload().setStagingRetentionHours(1);
        DirectUploadPartDescriptor tenantSeven = descriptor(
                7L, "receipt-session", 0, List.of("node-a", "node-b"), 2);
        DirectUploadPartDescriptor tenantEight = descriptor(
                8L, "receipt-session", 0, List.of("node-a", "node-b"), 2);

        store.recordSuccess(tenantSeven, List.of("node-a", "node-b"), intent);
        store.recordSuccess(tenantEight, List.of("node-a", "node-b"), intent);

        String tenantSevenKey = store.receiptKey(tenantSeven);
        String tenantEightKey = store.receiptKey(tenantEight);
        assertThat(tenantSevenKey)
                .isEqualTo(store.receiptKey(tenantSeven))
                .isNotEqualTo(tenantEightKey)
                .doesNotContain("receipt-session")
                .matches("[A-Za-z0-9:_-]+");
        assertThat(redisValues).containsOnlyKeys(tenantSevenKey, tenantEightKey);
        assertThat(scriptCalls).allSatisfy(call -> assertThat(call.keys()).hasSize(3));
        assertThat(scriptCalls).allSatisfy(call -> assertThat(Long.parseLong((String) call.arguments()[2]))
                .isGreaterThanOrEqualTo(Duration.ofHours(48).toMillis()));

        JsonNode stored = objectMapper.readTree(redisValues.get(tenantSevenKey));
        assertThat(stored.path("tenantId").asLong()).isEqualTo(7L);
        assertThat(stored.path("sessionId").asText()).isEqualTo("receipt-session");
        assertThat(stored.path("partIndex").asInt()).isZero();
        assertThat(stored.path("finalObjectName").asText()).isEqualTo(tenantSeven.finalObjectName());
        assertThat(stored.path("size").asLong()).isEqualTo(tenantSeven.size());
        assertThat(stored.path("plainHash").asText()).isEqualTo(tenantSeven.plainHash());
        assertThat(stored.path("cipherHash").asText()).isEqualTo(tenantSeven.cipherHash());
        assertThat(stored.path("checksumAlgorithm").asText()).isEqualTo("SHA-256");
        assertThat(stored.path("initialTargetNodes").size()).isEqualTo(2);
        assertThat(stored.path("successfulNodes").size()).isEqualTo(2);
        assertThat(stored.path("requiredQuorum").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("corrupt JSON and mismatched final identity should fail closed")
    void shouldRejectCorruptAndIdentityMismatchedReceipt() throws Exception {
        DirectUploadPartDescriptor descriptor = descriptor(
                7L, "receipt-session", 0, List.of("node-a"), 1);
        String key = store.receiptKey(descriptor);
        redisValues.put(key, "{not-json");

        assertThatThrownBy(() -> store.findValidated(descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("receipt is corrupt");

        DirectUploadPartDescriptor otherTenant = descriptor(
                8L, "receipt-session", 0, List.of("node-a"), 1);
        DirectUploadPromotionReceiptStore.PromotionReceipt mismatched = receipt(
                otherTenant,
                List.of("node-a"),
                List.of("node-a"),
                1
        );
        redisValues.put(key, objectMapper.writeValueAsString(mismatched));

        assertThatThrownBy(() -> store.findValidated(descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("identity mismatch");
    }

    @Test
    @DisplayName("concurrent and repeated records should merge idempotently without lowering original quorum")
    void shouldMergeConcurrentRecordsWithoutChangingOriginalSnapshotOrQuorum() throws Exception {
        DirectUploadPartDescriptor firstTopology = descriptor(
                7L, "receipt-session", 0, List.of("node-a", "node-b"), 2);
        DirectUploadPartDescriptor competingTopology = descriptor(
                7L, "receipt-session", 0, List.of("node-b", "node-c"), 2);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < 32; index++) {
                boolean first = index % 2 == 0;
                futures.add(executor.submit(() -> {
                    start.await();
                    if (first) {
                        store.recordSuccess(firstTopology, List.of("node-a", "node-b"), intent);
                    } else {
                        store.recordSuccess(competingTopology, List.of("node-b", "node-c"), intent);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        DirectUploadPromotionReceiptStore.PromotionReceipt concurrentReceipt =
                store.findValidated(firstTopology).orElseThrow();
        assertThat(concurrentReceipt.initialTargetNodes())
                .isIn(List.of("node-a", "node-b"), List.of("node-b", "node-c"));
        assertThat(concurrentReceipt.successfulNodes())
                .containsExactlyInAnyOrder("node-a", "node-b", "node-c")
                .doesNotHaveDuplicates();
        assertThat(concurrentReceipt.requiredQuorum()).isEqualTo(2);

        DirectUploadPartDescriptor lowerCurrentQuorum = descriptor(
                7L, "receipt-session", 0, List.of("node-c", "node-d"), 1);
        store.recordRetrySuccess(
                lowerCurrentQuorum,
                concurrentReceipt,
                List.of("node-a", "node-b", "node-d"),
                intent
        );
        store.recordRetrySuccess(
                lowerCurrentQuorum,
                concurrentReceipt,
                List.of("node-a", "node-b", "node-d"),
                intent
        );

        DirectUploadPromotionReceiptStore.PromotionReceipt refreshed =
                store.findValidated(lowerCurrentQuorum).orElseThrow();
        assertThat(refreshed.initialTargetNodes()).isEqualTo(concurrentReceipt.initialTargetNodes());
        assertThat(refreshed.requiredQuorum()).isEqualTo(2);
        assertThat(refreshed.successfulNodes())
                .containsExactlyInAnyOrder("node-a", "node-b", "node-c", "node-d")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("retry should fail closed when authoritative receipt expires before CAS")
    void shouldNotRecreateWeakerReceiptAfterAuthoritativeSnapshotExpires() throws Exception {
        DirectUploadPartDescriptor original = descriptor(
                7L,
                "receipt-expiry",
                0,
                List.of("node-a", "node-b", "node-c"),
                3
        );
        DirectUploadPromotionReceiptStore.PromotionReceipt authoritative = store.recordSuccess(
                original,
                List.of("node-a", "node-b", "node-c"),
                intent
        );
        DirectUploadPartDescriptor weakerCurrent = descriptor(
                7L,
                "receipt-expiry",
                0,
                List.of("node-d"),
                1
        );
        expireReceiptBeforeNextCas.set(true);

        assertThatThrownBy(() -> store.recordRetrySuccess(
                weakerCurrent,
                authoritative,
                List.of("node-a", "node-b", "node-c"),
                intent
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired during retry");

        assertThat(redisValues).doesNotContainKey(store.receiptKey(original));
    }

    /**
     * 模拟 Redis 脚本对唯一 receipt key 的原子 NX/CAS 更新。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubAtomicReceiptScript() {
        lenient().doAnswer(invocation -> {
            Object[] rawArguments = invocation.getRawArguments();
            List<String> keys = (List<String>) rawArguments[1];
            Object[] arguments = (Object[]) rawArguments[2];
            scriptCalls.add(new ScriptCall(List.copyOf(keys), arguments.clone()));
            String key = keys.getFirst();
            String expected = String.valueOf(arguments[0]);
            String replacement = String.valueOf(arguments[1]);
            String missingSentinel = String.valueOf(arguments[3]);
            synchronized (redisValues) {
                if (expireReceiptBeforeNextCas.compareAndSet(true, false)) {
                    redisValues.remove(key);
                }
                String current = redisValues.get(key);
                if (current == null && expected.equals(missingSentinel)) {
                    redisValues.put(key, replacement);
                    return 1L;
                }
                if (current != null && Objects.equals(current, expected)) {
                    redisValues.put(key, replacement);
                    return 1L;
                }
                return 0L;
            }
        }).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );
    }

    /**
     * 构建 final identity 相同、目标拓扑可变的测试 descriptor。
     */
    private DirectUploadPartDescriptor descriptor(
            Long tenantId,
            String sessionId,
            int partIndex,
            List<String> targets,
            int requiredQuorum
    ) throws Exception {
        byte[] content = "promotion-receipt-content".getBytes(StandardCharsets.UTF_8);
        String hash = "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));
        return new DirectUploadPartDescriptor(
                tenantId,
                sessionId,
                partIndex,
                "staging-node",
                "tenant/" + tenantId + "/staging/direct-upload/" + sessionId + "/part-" + partIndex,
                "tenant/" + tenantId + "/" + hash,
                content.length,
                "\"staging-etag\"",
                hash,
                hash,
                "SHA-256",
                targets,
                requiredQuorum
        );
    }

    /**
     * 构建严格匹配给定 descriptor identity 的 receipt JSON 值。
     */
    private DirectUploadPromotionReceiptStore.PromotionReceipt receipt(
            DirectUploadPartDescriptor descriptor,
            List<String> initialTargets,
            List<String> successfulNodes,
            int requiredQuorum
    ) {
        return new DirectUploadPromotionReceiptStore.PromotionReceipt(
                1,
                descriptor.tenantId(),
                descriptor.sessionId(),
                descriptor.partIndex(),
                descriptor.finalObjectName(),
                descriptor.size(),
                descriptor.plainHash(),
                descriptor.cipherHash(),
                descriptor.checksumAlgorithm(),
                initialTargets,
                successfulNodes,
                requiredQuorum
        );
    }

    /**
     * 记录一次 Lua 调用的唯一 key 和 ARGV，供 TTL/单 key 断言。
     */
    private record ScriptCall(
            List<String> keys,
            Object[] arguments
    ) {
    }
}
