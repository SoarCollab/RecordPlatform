package cn.flying.storage.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证 direct-upload 终态意图的不可变代际、Redis fencing 与失败关闭合同。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectUploadOperationIntentStore Unit Tests")
class DirectUploadOperationIntentStoreTest {

    private static final String GLOBAL_FENCE_KEY =
            "storage:direct-upload:operation-intent:v1:global-fence";
    private static final String SESSION_ID = "intent-session";
    private static final String NODE_NAME = "node-a";
    private static final String STAGING_OBJECT =
            "tenant/7/staging/direct-upload/intent-session/part-2";
    private static final String PLAIN_HASH = "sha256:" + "a".repeat(64);
    private static final String CIPHER_HASH = "sha256:" + "b".repeat(64);

    @Mock
    private StringRedisTemplate redisTemplate;

    private DirectUploadOperationIntentStore store;
    private DirectUploadStagingDescriptor stagingDescriptor;
    private List<ScriptCall> scriptCalls;
    private Deque<Function<ScriptCall, Object>> scriptResponses;

    /**
     * 为每个用例创建隔离的 Store，并记录所有 Redis Lua 的 KEYS 与 ARGV。
     */
    @BeforeEach
    void setUp() {
        store = new DirectUploadOperationIntentStore(redisTemplate);
        stagingDescriptor = new DirectUploadStagingDescriptor(
                7L,
                SESSION_ID,
                2,
                NODE_NAME,
                STAGING_OBJECT
        );
        scriptCalls = new ArrayList<>();
        scriptResponses = new ArrayDeque<>();
        stubRedisScripts();
    }

    /**
     * 证明完整 complete 内容代际会规范化 ETag、hash 和算法，同时排除可变拓扑。
     */
    @Test
    @DisplayName("complete generation should be canonical and independent from topology")
    void shouldSerializeCanonicalCompleteGeneration() throws Exception {
        DirectUploadPartDescriptor first = completeDescriptor(
                "  \"staging-etag\"  ",
                "  SHA256:" + "A".repeat(64) + "  ",
                "  SHA256:" + "B".repeat(64) + "  ",
                " sha-256 ",
                4_096L,
                "tenant/7/" + CIPHER_HASH,
                List.of("node-a", "node-b"),
                2
        );
        DirectUploadPartDescriptor equivalentRetry = completeDescriptor(
                "staging-etag",
                PLAIN_HASH,
                CIPHER_HASH,
                "SHA-256",
                4_096L,
                "tenant/7/" + CIPHER_HASH,
                List.of("node-c"),
                1
        );
        scriptResponses.add(call -> okResult(41L, call.arguments()[0]));
        scriptResponses.add(call -> okResult(42L, call.arguments()[0]));

        DirectUploadOperationIntentStore.OperationIntent firstIntent = store.beginComplete(first);
        DirectUploadOperationIntentStore.OperationIntent retryIntent =
                store.beginComplete(equivalentRetry);

        String descriptorFingerprint = store.descriptorFingerprint(stagingDescriptor);
        String expectedGeneration = sha256Hex(String.join("\0",
                "complete-v1",
                descriptorFingerprint,
                "tenant/7/" + CIPHER_HASH,
                "4096",
                "staging-etag",
                PLAIN_HASH,
                CIPHER_HASH,
                "SHA-256"
        ));
        String expectedValue = "v1|COMPLETE|" + descriptorFingerprint + "|" + expectedGeneration;

        assertThat(firstIntent.serializedValue()).isEqualTo(expectedValue);
        assertThat(firstIntent.mode())
                .isEqualTo(DirectUploadOperationIntentStore.OperationMode.COMPLETE);
        assertThat(firstIntent.descriptorFingerprint()).isEqualTo(descriptorFingerprint);
        assertThat(firstIntent.generationFingerprint()).isEqualTo(expectedGeneration);
        assertThat(firstIntent.executionEpoch()).isEqualTo(41L);
        assertThat(retryIntent.serializedValue()).isEqualTo(expectedValue);
        assertThat(retryIntent.generationFingerprint()).isEqualTo(expectedGeneration);
        assertThat(retryIntent.executionEpoch()).isEqualTo(42L);

        String expectedKey = store.intentKey(stagingDescriptor);
        assertThat(expectedKey)
                .doesNotContain(SESSION_ID)
                .matches("storage:direct-upload:operation-intent:v1:[0-9a-f]{64}");
        assertThat(scriptCalls).hasSize(2).allSatisfy(call -> {
            assertThat(call.keys()).containsExactly(
                    expectedKey,
                    expectedKey + ":fence",
                    GLOBAL_FENCE_KEY
            );
            assertThat(call.arguments()).containsExactly(expectedValue);
            assertThat(call.script()).contains("INCR", "CONFLICT", "PERSIST");
        });
    }

    /**
     * 证明相同分片已有不同终态时，exact complete 即使推进 fence 也必须失败关闭。
     */
    @Test
    @DisplayName("conflicting exact intent should fail closed")
    void shouldRejectConflictingExactIntent() {
        String descriptorFingerprint = store.descriptorFingerprint(stagingDescriptor);
        String existingAbort = "v1|ABORT|" + descriptorFingerprint + "|" + descriptorFingerprint;
        scriptResponses.add(call -> "CONFLICT\n51\n" + existingAbort);

        assertThatThrownBy(() -> store.beginComplete(validCompleteDescriptor()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("conflicting direct-upload operation intent");

        assertThat(scriptCalls).singleElement().satisfies(call -> {
            assertThat(call.arguments()[0]).asString().startsWith("v1|COMPLETE|");
            assertThat(call.keys()).contains(GLOBAL_FENCE_KEY);
            assertThat(call.script()).contains("current ~= ARGV[1]", "INCR");
        });
    }

    /**
     * 证明 abort 会继承既有 complete 内容代际，只推进执行 epoch 而不改写语义值。
     */
    @Test
    @DisplayName("abort should follow an existing complete intent")
    void shouldFollowExistingCompleteWhenAbortBegins() {
        String descriptorFingerprint = store.descriptorFingerprint(stagingDescriptor);
        String completeGeneration = "c".repeat(64);
        String existingComplete =
                "v1|COMPLETE|" + descriptorFingerprint + "|" + completeGeneration;
        scriptResponses.add(call -> okResult(61L, existingComplete));

        DirectUploadOperationIntentStore.OperationIntent inherited =
                store.beginAbort(stagingDescriptor);

        assertThat(inherited.mode())
                .isEqualTo(DirectUploadOperationIntentStore.OperationMode.COMPLETE);
        assertThat(inherited.serializedValue()).isEqualTo(existingComplete);
        assertThat(inherited.generationFingerprint()).isEqualTo(completeGeneration);
        assertThat(inherited.executionEpoch()).isEqualTo(61L);
        assertThat(scriptCalls).singleElement().satisfies(call -> {
            assertThat(call.arguments()).containsExactly(
                    "v1|ABORT|" + descriptorFingerprint + "|" + descriptorFingerprint
            );
            assertThat(call.script()).contains("PERSIST", "INCR").doesNotContain("CONFLICT");
        });
    }

    /**
     * 证明 cleanup 只有在 Redis claim token 仍匹配时才能取得终态执行权。
     */
    @Test
    @DisplayName("cleanup should return empty when its claim is superseded")
    void shouldRejectSupersededCleanupClaim() {
        DirectUploadStagingTracker.TrackedStaging tracked =
                new DirectUploadStagingTracker.TrackedStaging(
                        "encoded-member",
                        stagingDescriptor,
                        "claim-generation-7",
                        1_000L
                );
        scriptResponses.add(call -> "SUPERSEDED");

        Optional<DirectUploadOperationIntentStore.OperationIntent> result =
                store.followOrCreateCleanup(tracked);

        assertThat(result).isEmpty();
        String descriptorFingerprint = store.descriptorFingerprint(stagingDescriptor);
        String intentKey = store.intentKey(stagingDescriptor);
        assertThat(scriptCalls).singleElement().satisfies(call -> {
            assertThat(call.keys()).containsExactly(
                    intentKey,
                    intentKey + ":fence",
                    GLOBAL_FENCE_KEY,
                    DirectUploadStagingTracker.CLAIM_HASH_KEY
            );
            assertThat(call.arguments()).containsExactly(
                    "v1|CLEANUP|" + descriptorFingerprint + "|" + descriptorFingerprint,
                    tracked.member(),
                    tracked.claimToken()
            );
            assertThat(call.script()).contains("HGET", "ARGV[2]", "ARGV[3]");
        });
    }

    /**
     * 证明 verify 与 retire 均逐字节绑定意图值和 execution epoch，且只退休分片键。
     */
    @Test
    @DisplayName("verify and retire should use the exact intent fence")
    void shouldVerifyAndRetireOnlyWithExactFence() {
        DirectUploadOperationIntentStore.OperationIntent intent = validIntent(73L);
        scriptResponses.add(call -> 1L);
        scriptResponses.add(call -> 1L);

        store.verify(intent);
        store.retire(intent, Duration.ofHours(48));

        assertThat(scriptCalls).hasSize(2);
        ScriptCall verifyCall = scriptCalls.get(0);
        assertThat(verifyCall.keys()).containsExactly(intent.key(), intent.fenceKey());
        assertThat(verifyCall.arguments()).containsExactly(intent.serializedValue(), "73");
        assertThat(verifyCall.script()).contains("GET", "return 1").doesNotContain("PEXPIRE");

        ScriptCall retireCall = scriptCalls.get(1);
        assertThat(retireCall.keys())
                .containsExactly(intent.key(), intent.fenceKey())
                .doesNotContain(GLOBAL_FENCE_KEY);
        assertThat(retireCall.arguments()).containsExactly(
                intent.serializedValue(),
                "73",
                String.valueOf(Duration.ofHours(48).toMillis())
        );
        assertThat(retireCall.script()).contains(
                "PEXPIRE', KEYS[1]",
                "PEXPIRE', KEYS[2]"
        );
    }

    /**
     * 证明旧持有者或 Redis 未确认时，verify/retire 都不会静默成功。
     */
    @Test
    @DisplayName("stale verify and retire fences should fail closed")
    void shouldFailClosedForStaleVerifyAndRetireFence() {
        DirectUploadOperationIntentStore.OperationIntent intent = validIntent(79L);
        scriptResponses.add(call -> 0L);
        scriptResponses.add(call -> 0L);

        assertThatThrownBy(() -> store.verify(intent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution fence changed");
        assertThatThrownBy(() -> store.retire(intent, Duration.ofHours(48)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be retired");

        assertThat(scriptCalls).hasSize(2);
    }

    /**
     * 证明空结果、畸形前缀、非法 epoch、损坏 value 和 staging 漂移均被拒绝。
     */
    @Test
    @DisplayName("empty or corrupt begin script results should fail closed")
    void shouldRejectEmptyAndCorruptBeginResults() {
        String foreignFingerprint = "e".repeat(64);
        scriptResponses.add(call -> null);
        scriptResponses.add(call -> "");
        scriptResponses.add(call -> okResult(0L, call.arguments()[0]));
        scriptResponses.add(call -> "OK\n1\nv1|ABORT|bad|bad");
        scriptResponses.add(call ->
                "OK\n2\nv1|ABORT|" + foreignFingerprint + "|" + foreignFingerprint);

        assertThatThrownBy(() -> store.beginAbort(stagingDescriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failed to persist");
        assertThatThrownBy(() -> store.beginAbort(stagingDescriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("script result is corrupt");
        assertThatThrownBy(() -> store.beginAbort(stagingDescriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fence is corrupt");
        assertThatThrownBy(() -> store.beginAbort(stagingDescriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intent is corrupt");
        assertThatThrownBy(() -> store.beginAbort(stagingDescriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging identity mismatch");

        assertThat(scriptCalls).hasSize(5);
    }

    /**
     * 证明无效 descriptor、claim、意图对象和 retention 会在访问 Redis 前被拒绝。
     */
    @Test
    @DisplayName("invalid operation inputs should be rejected before Redis")
    void shouldRejectInvalidInputsBeforeRedis() {
        DirectUploadStagingDescriptor nonCanonical = new DirectUploadStagingDescriptor(
                7L,
                SESSION_ID,
                2,
                NODE_NAME,
                "tenant/8/staging/direct-upload/intent-session/part-2"
        );
        DirectUploadStagingTracker.TrackedStaging missingClaim =
                new DirectUploadStagingTracker.TrackedStaging(
                        "encoded-member",
                        stagingDescriptor,
                        " ",
                        1_000L
                );
        DirectUploadOperationIntentStore.OperationIntent validIntent = validIntent(83L);

        assertThatThrownBy(() -> store.beginComplete(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.beginAbort(nonCanonical))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical");
        assertThatThrownBy(() -> store.followOrCreateCleanup(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup claim");
        assertThatThrownBy(() -> store.followOrCreateCleanup(missingClaim))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cleanup claim");
        assertThatThrownBy(() -> store.verify(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.retire(validIntent, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> store.retire(validIntent, Duration.ofSeconds(Long.MAX_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");

        verifyNoInteractions(redisTemplate);
    }

    /**
     * 证明 complete 的 size、ETag、hash、算法和 final key 均属于 Redis 前置校验。
     */
    @Test
    @DisplayName("invalid complete identity should be rejected before Redis")
    void shouldRejectInvalidCompleteIdentityBeforeRedis() {
        assertThatThrownBy(() -> store.beginComplete(completeDescriptor(
                "staging-etag", PLAIN_HASH, CIPHER_HASH, "SHA-256",
                0L, "tenant/7/" + CIPHER_HASH, List.of("node-a"), 1
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.beginComplete(completeDescriptor(
                "bad\netag", PLAIN_HASH, CIPHER_HASH, "SHA-256",
                4_096L, "tenant/7/" + CIPHER_HASH, List.of("node-a"), 1
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.beginComplete(completeDescriptor(
                "staging-etag", "sha256:bad", CIPHER_HASH, "SHA-256",
                4_096L, "tenant/7/" + CIPHER_HASH, List.of("node-a"), 1
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.beginComplete(completeDescriptor(
                "staging-etag", PLAIN_HASH, CIPHER_HASH, "MD5",
                4_096L, "tenant/7/" + CIPHER_HASH, List.of("node-a"), 1
        ))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.beginComplete(completeDescriptor(
                "staging-etag", PLAIN_HASH, CIPHER_HASH, "SHA-256",
                4_096L, "tenant/8/" + CIPHER_HASH, List.of("node-a"), 1
        ))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("final object identity");

        verifyNoInteractions(redisTemplate);
    }

    /**
     * 模拟 Redis Lua 执行结果，并保存脚本文本、KEYS 与 ARGV 供合同断言。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubRedisScripts() {
        lenient().doAnswer(invocation -> {
            Object[] rawArguments = invocation.getRawArguments();
            RedisScript script = (RedisScript) rawArguments[0];
            List<String> keys = (List<String>) rawArguments[1];
            Object[] arguments = (Object[]) rawArguments[2];
            ScriptCall call = new ScriptCall(
                    script.getScriptAsString(),
                    List.copyOf(keys),
                    arguments.clone()
            );
            scriptCalls.add(call);
            if (scriptResponses.isEmpty()) {
                throw new AssertionError("unexpected Redis script: " + call.script());
            }
            return scriptResponses.removeFirst().apply(call);
        }).when(redisTemplate).execute(
                any(RedisScript.class),
                anyList(),
                any(Object[].class)
        );
    }

    /**
     * 构造合法且规范的 complete 分片描述。
     */
    private DirectUploadPartDescriptor validCompleteDescriptor() {
        return completeDescriptor(
                "\"staging-etag\"",
                PLAIN_HASH,
                CIPHER_HASH,
                "SHA-256",
                4_096L,
                "tenant/7/" + CIPHER_HASH,
                List.of("node-a", "node-b"),
                2
        );
    }

    /**
     * 构造可精确控制身份字段的 complete 分片描述。
     */
    private DirectUploadPartDescriptor completeDescriptor(
            String eTag,
            String plainHash,
            String cipherHash,
            String checksumAlgorithm,
            long size,
            String finalObject,
            List<String> targets,
            int quorum
    ) {
        return new DirectUploadPartDescriptor(
                7L,
                SESSION_ID,
                2,
                NODE_NAME,
                STAGING_OBJECT,
                finalObject,
                size,
                eTag,
                plainHash,
                cipherHash,
                checksumAlgorithm,
                targets,
                quorum
        );
    }

    /**
     * 构造通过对象完整性校验的 fencing 意图。
     */
    private DirectUploadOperationIntentStore.OperationIntent validIntent(long epoch) {
        String descriptorFingerprint = store.descriptorFingerprint(stagingDescriptor);
        String key = store.intentKey(stagingDescriptor);
        return new DirectUploadOperationIntentStore.OperationIntent(
                key,
                key + ":fence",
                "v1|ABORT|" + descriptorFingerprint + "|" + descriptorFingerprint,
                DirectUploadOperationIntentStore.OperationMode.ABORT,
                descriptorFingerprint,
                descriptorFingerprint,
                epoch
        );
    }

    /**
     * 构造 begin/follow Lua 的成功返回文本。
     */
    private String okResult(long epoch, Object serializedValue) {
        return "OK\n" + epoch + "\n" + serializedValue;
    }

    /**
     * 计算测试侧期望的稳定 SHA-256 指纹。
     */
    private String sha256Hex(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * 保存一次 Redis Lua 调用的可观察合同。
     */
    private record ScriptCall(String script, List<String> keys, Object[] arguments) {
    }
}
