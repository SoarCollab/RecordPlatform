package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 验证 staging 生命周期刷新、集群领取、代际 fencing 与损坏记录隔离。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectUploadStagingTracker Unit Tests")
class DirectUploadStagingTrackerTest {

    private static final String EXPIRY_SET_KEY = "storage:direct-upload:staging-expiry";
    private static final String CLAIM_HASH_KEY = "storage:direct-upload:staging-expiry:claims";
    private static final String CLAIM_GATE_KEY = "storage:direct-upload:staging-expiry:claim-gate";
    private static final String DEAD_LETTER_SET_KEY = "storage:direct-upload:staging-expiry:dead-letter";
    private static final String INTENT_KEY = "storage:direct-upload:operation-intent:v1:test";
    private static final String INTENT_FENCE_KEY = INTENT_KEY + ":fence";
    private static final String GLOBAL_FENCE_KEY =
            "storage:direct-upload:operation-intent:v1:global-fence";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private DirectUploadLockManager lockManager;

    @Mock
    private DirectUploadOperationIntentStore operationIntentStore;

    @Mock
    private DirectUploadLockManager.LockHandle lockHandle;

    private StorageProperties properties;
    private DirectUploadStagingTracker tracker;
    private DirectUploadStagingDescriptor descriptor;
    private Deque<List<Object>> claimResults;
    private List<String> executedScripts;
    private List<List<String>> executedKeys;
    private List<Object[]> executedArguments;
    private Long refreshResult;
    private Long verifyResult;
    private Long completeResult;
    private Long rescheduleResult;
    private Long batchRescheduleResult;
    private Long deadLetterResult;
    private Long releaseResult;
    private boolean nullClaimResult;

    /**
     * 注入真实配置、分片锁和可观测的 Redis Lua stub。
     */
    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        tracker = new DirectUploadStagingTracker(
                redisTemplate,
                properties,
                lockManager,
                operationIntentStore
        );
        descriptor = new DirectUploadStagingDescriptor(
                7L,
                "session-1",
                2,
                "node-a",
                "tenant/7/staging/direct-upload/session-1/part-2"
        );
        lenient().when(lockManager.acquire(any())).thenReturn(lockHandle);
        lenient().when(operationIntentStore.lifecycleFenceKeys(any())).thenReturn(
                new DirectUploadOperationIntentStore.LifecycleFenceKeys(
                        INTENT_KEY,
                        INTENT_FENCE_KEY,
                        GLOBAL_FENCE_KEY
                )
        );
        claimResults = new ArrayDeque<>();
        executedScripts = new ArrayList<>();
        executedKeys = new ArrayList<>();
        executedArguments = new ArrayList<>();
        refreshResult = 1L;
        verifyResult = 1L;
        completeResult = 1L;
        rescheduleResult = 1L;
        batchRescheduleResult = 2L;
        deadLetterResult = 1L;
        releaseResult = 1L;
        nullClaimResult = false;
        stubRedisScripts();
    }

    @Test
    @DisplayName("record should refresh a deterministic member under the part lock for at least 48 hours")
    void shouldRefreshLifecycleUnderPartLock() {
        long before = System.currentTimeMillis();

        tracker.record(descriptor);
        tracker.record(descriptor);

        int firstRefresh = indexOfScript("local current = redis.call('ZSCORE'");
        int secondRefresh = lastIndexOfScript("local current = redis.call('ZSCORE'");
        Object[] firstArguments = executedArguments.get(firstRefresh);
        Object[] secondArguments = executedArguments.get(secondRefresh);
        assertThat(firstArguments[0]).isEqualTo(secondArguments[0]);
        assertThat(String.valueOf(firstArguments[0])).startsWith("v1.7.2.");
        assertThat(String.valueOf(firstArguments[0])).doesNotContain(descriptor.objectName());
        assertThat(Long.parseLong(String.valueOf(secondArguments[1])))
                .isBetween(
                        before + Duration.ofHours(48).toMillis(),
                        System.currentTimeMillis() + Duration.ofHours(48).toMillis()
                );
        assertThat(executedKeys.get(firstRefresh)).containsExactly(
                EXPIRY_SET_KEY,
                CLAIM_HASH_KEY,
                INTENT_KEY,
                INTENT_FENCE_KEY,
                GLOBAL_FENCE_KEY
        );
        assertThat(executedScripts.get(firstRefresh))
                .contains("tonumber(current) > requested")
                .contains("HDEL", "KEYS[2]");
        verify(lockManager, times(2)).acquire(descriptor);
        verify(lockHandle, times(2)).close();
    }

    @Test
    @DisplayName("retention and claim lease configuration should clamp unsafe values")
    void shouldClampLifecycleConfiguration() {
        properties.getDirectUpload().setStagingRetentionHours(1);
        properties.getDirectUpload().setCleanupClaimLeaseSeconds(1);

        assertThat(properties.getDirectUpload().getEffectiveStagingRetentionHours()).isEqualTo(48);
        assertThat(properties.getDirectUpload().getEffectiveCleanupClaimLeaseSeconds()).isEqualTo(180);

        properties.getDirectUpload().setStagingRetentionHours(Long.MAX_VALUE);
        properties.getDirectUpload().setCleanupClaimLeaseSeconds(Integer.MAX_VALUE);

        assertThat(properties.getDirectUpload().getEffectiveStagingRetentionHours()).isEqualTo(8_760);
        assertThat(properties.getDirectUpload().getEffectiveCleanupClaimLeaseSeconds()).isEqualTo(3_600);
    }

    @Test
    @DisplayName("completed or aborted object should retain a full tombstone instead of removing tracking")
    void shouldRetainTombstoneAfterDelete() {
        long before = System.currentTimeMillis();

        tracker.retainAfterDelete(descriptor);

        int refresh = indexOfScript("local current = redis.call('ZSCORE'");
        assertThat(Long.parseLong(String.valueOf(executedArguments.get(refresh)[1])))
                .isGreaterThanOrEqualTo(before + Duration.ofHours(48).toMillis());
        assertThat(executedScripts.get(refresh)).doesNotContain("ZREM");
        verify(lockManager).acquire(descriptor);
    }

    @Test
    @DisplayName("refresh should fail closed when Redis does not acknowledge lifecycle persistence")
    void shouldFailClosedWhenRefreshReturnsNull() {
        refreshResult = null;

        assertThatThrownBy(() -> tracker.record(descriptor))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("register");
        verify(lockHandle).close();
    }

    @Test
    @DisplayName("claim should be cluster gated, bounded and recoverable through a finite lease")
    void shouldClaimClusterWideBoundedBatchWithLease() {
        properties.getDirectUpload().setCleanupBatchSize(2);
        properties.getDirectUpload().setCleanupClaimLeaseSeconds(1);
        String member = encodedMember(descriptor);
        claimResults.add(List.of(
                member, "batch-1:1", "1000",
                member, "batch-1:2", "1001",
                member, "batch-1:3", "1002"
        ));

        DirectUploadStagingTracker.ClaimBatch batch = tracker.claimExpired(10);

        assertThat(batch.entries()).hasSize(2);
        assertThat(batch.entries().getFirst().descriptor()).isEqualTo(descriptor);
        assertThat(batch.entries().getFirst().claimToken()).isEqualTo("batch-1:1");
        int claim = indexOfScript("local gate = redis.call('SET'");
        assertThat(executedKeys.get(claim))
                .containsExactly(EXPIRY_SET_KEY, CLAIM_HASH_KEY, CLAIM_GATE_KEY);
        assertThat(executedScripts.get(claim))
                .contains("'NX', 'PX'")
                .contains("ZRANGEBYSCORE")
                .contains("'LIMIT', 0, ARGV[3]")
                .contains("HSET", "claimToken");
        Object[] arguments = executedArguments.get(claim);
        assertThat(arguments[2]).isEqualTo("2");
        long now = Long.parseLong(String.valueOf(arguments[0]));
        long leaseUntil = Long.parseLong(String.valueOf(arguments[1]));
        assertThat(leaseUntil - now).isEqualTo(Duration.ofSeconds(180).toMillis());
        assertThat(arguments[4]).isEqualTo(String.valueOf(Duration.ofSeconds(180).toMillis()));
        assertThat(batch.claimLeaseMillis()).isEqualTo(Duration.ofSeconds(180).toMillis());
    }

    @Test
    @DisplayName("corrupt member should move to persistent dead letter without producing a cleanup descriptor")
    void shouldDeadLetterCorruptMemberWithoutS3Identity() {
        claimResults.add(List.of("broken-member", "batch-1:1", "1000"));

        DirectUploadStagingTracker.ClaimBatch batch = tracker.claimExpired(10);

        assertThat(batch.entries()).isEmpty();
        int deadLetter = indexOfScript("redis.call('ZADD', KEYS[3]");
        assertThat(executedKeys.get(deadLetter))
                .containsExactly(EXPIRY_SET_KEY, CLAIM_HASH_KEY, DEAD_LETTER_SET_KEY);
        assertThat(executedArguments.get(deadLetter)[0]).isEqualTo("broken-member");
        assertThat(executedArguments.get(deadLetter)[1]).isEqualTo("batch-1:1");
        assertThat(executedScripts.get(deadLetter))
                .contains("HGET", "ARGV[2]")
                .contains("ZREM", "HDEL");
    }

    @Test
    @DisplayName("null Redis claim result should fail closed without deleting lifecycle state")
    void shouldFailClosedWhenClaimReturnsNull() {
        nullClaimResult = true;

        assertThatThrownBy(() -> tracker.claimExpired(10))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim");
    }

    @Test
    @DisplayName("cleanup mutations should all compare the exact claim token")
    void shouldFenceVerifyCompleteAndRescheduleByClaimToken() {
        DirectUploadStagingTracker.TrackedStaging tracked = tracked("member", "token-1");

        assertThat(tracker.isClaimCurrent(tracked)).isTrue();
        assertThat(tracker.completeClaim(tracked)).isTrue();
        assertThat(tracker.rescheduleClaim(tracked)).isTrue();

        int verify = indexOfScript("HGET', KEYS[1], ARGV[1]) == ARGV[2]");
        int complete = indexOfScript("redis.call('ZREM', KEYS[1]");
        int reschedule = indexOfScript("local retryAt = tonumber(ARGV[3])");
        assertThat(executedArguments.get(verify)).containsExactly("member", "token-1");
        assertThat(executedArguments.get(complete)).containsExactly("member", "token-1");
        assertThat(executedArguments.get(reschedule)[0]).isEqualTo("member");
        assertThat(executedArguments.get(reschedule)[1]).isEqualTo("token-1");
        assertThat(executedScripts.get(reschedule))
                .contains("HGET", "ARGV[2]")
                .contains("tonumber(current) > retryAt");
    }

    @Test
    @DisplayName("batch budget exit should reschedule every remaining claim by its exact token")
    void shouldFenceBatchRescheduleByEveryClaimToken() {
        DirectUploadStagingTracker.TrackedStaging first = tracked("member-1", "token-1");
        DirectUploadStagingTracker.TrackedStaging second = tracked("member-2", "token-2");

        int rescheduled = tracker.rescheduleClaims(List.of(first, second));

        assertThat(rescheduled).isEqualTo(2);
        int batchReschedule = indexOfScript("for index = 2, #ARGV, 2 do");
        assertThat(executedKeys.get(batchReschedule)).containsExactly(EXPIRY_SET_KEY, CLAIM_HASH_KEY);
        assertThat(executedArguments.get(batchReschedule))
                .hasSize(5)
                .containsSequence("member-1", "token-1", "member-2", "token-2");
        assertThat(executedScripts.get(batchReschedule))
                .contains("HGET", "claimToken")
                .contains("HDEL", "rescheduled = rescheduled + 1");
    }

    @Test
    @DisplayName("stale claim should never complete or shorten a refreshed generation")
    void shouldIgnoreStaleClaimMutations() {
        completeResult = 0L;
        rescheduleResult = 0L;
        DirectUploadStagingTracker.TrackedStaging tracked = tracked("member", "old-token");

        assertThat(tracker.completeClaim(tracked)).isFalse();
        assertThat(tracker.rescheduleClaim(tracked)).isFalse();

        assertThat(executedScripts.get(indexOfScript("local retryAt = tonumber(ARGV[3])")))
                .contains("~= ARGV[2]")
                .contains("return 0");
    }

    @Test
    @DisplayName("batch gate release should be compare-and-delete and tolerate an empty batch")
    void shouldReleaseOnlyOwnedBatchGate() {
        tracker.releaseClaimBatch(new DirectUploadStagingTracker.ClaimBatch("batch-1", List.of()));
        tracker.releaseClaimBatch(DirectUploadStagingTracker.ClaimBatch.empty());

        int release = indexOfScript("redis.call('GET', KEYS[1]) == ARGV[1]");
        assertThat(executedKeys.get(release)).containsExactly(CLAIM_GATE_KEY);
        assertThat(executedArguments.get(release)).containsExactly("batch-1");
        assertThat(executedScripts.get(release)).contains("DEL", "ARGV[1]");
        assertThat(executedScripts.stream()
                .filter(script -> script.contains("redis.call('GET', KEYS[1]) == ARGV[1]")))
                .hasSize(1);
    }

    @Test
    @DisplayName("noncanonical object key should be rejected before lock or Redis access")
    void shouldRejectNoncanonicalObjectKey() {
        DirectUploadStagingDescriptor invalid = new DirectUploadStagingDescriptor(
                7L,
                "session-1",
                2,
                "node-a",
                "tenant/8/staging/direct-upload/session-1/part-2"
        );

        assertThatThrownBy(() -> tracker.record(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("canonical");
        verify(lockManager, org.mockito.Mockito.never()).acquire(any());
    }

    /**
     * 模拟 Redis Lua 返回值，并记录脚本、KEYS 与 ARGV 供并发合同断言。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubRedisScripts() {
        lenient().doAnswer(invocation -> {
            Object[] rawArguments = invocation.getRawArguments();
            RedisScript script = (RedisScript) rawArguments[0];
            List<String> keys = (List<String>) rawArguments[1];
            Object[] arguments = (Object[]) rawArguments[2];
            String scriptText = script.getScriptAsString();
            executedScripts.add(scriptText);
            executedKeys.add(List.copyOf(keys));
            executedArguments.add(arguments.clone());
            if (scriptText.contains("local gate = redis.call('SET'")) {
                if (nullClaimResult) {
                    return null;
                }
                return claimResults.isEmpty() ? List.of() : claimResults.removeFirst();
            }
            if (scriptText.contains("local retryAt = tonumber(ARGV[3])")) {
                return rescheduleResult;
            }
            if (scriptText.contains("for index = 2, #ARGV, 2 do")) {
                return batchRescheduleResult;
            }
            if (scriptText.contains("local current = redis.call('ZSCORE'")) {
                return refreshResult;
            }
            if (scriptText.contains("redis.call('ZADD', KEYS[3]")) {
                return deadLetterResult;
            }
            if (scriptText.contains("redis.call('ZREM', KEYS[1]")) {
                return completeResult;
            }
            if (scriptText.contains("redis.call('GET', KEYS[1]) == ARGV[1]")) {
                return releaseResult;
            }
            if (scriptText.contains("redis.call('HGET', KEYS[1]")) {
                return verifyResult;
            }
            throw new AssertionError("unexpected Redis script: " + scriptText);
        }).when(redisTemplate).execute(
                any(RedisScript.class),
                any(List.class),
                any(Object[].class)
        );
    }

    /**
     * 返回首个包含指定 Lua 片段的执行记录下标。
     */
    private int indexOfScript(String fragment) {
        for (int index = 0; index < executedScripts.size(); index++) {
            if (executedScripts.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new AssertionError("script was not executed: " + fragment);
    }

    /**
     * 返回最后一个包含指定 Lua 片段的执行记录下标。
     */
    private int lastIndexOfScript(String fragment) {
        for (int index = executedScripts.size() - 1; index >= 0; index--) {
            if (executedScripts.get(index).contains(fragment)) {
                return index;
            }
        }
        throw new AssertionError("script was not executed: " + fragment);
    }

    /**
     * 构造与生产编码规则一致的合法 Redis member。
     */
    private String encodedMember(DirectUploadStagingDescriptor value) {
        return String.join(".",
                "v1",
                String.valueOf(value.tenantId()),
                String.valueOf(value.partIndex()),
                encode(value.nodeName()),
                encode(value.sessionId()),
                encode(value.objectName()));
    }

    /**
     * 对 member 文本字段执行 URL-safe Base64 编码。
     */
    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 创建完整的 fencing claim 测试对象。
     */
    private DirectUploadStagingTracker.TrackedStaging tracked(String member, String claimToken) {
        return new DirectUploadStagingTracker.TrackedStaging(member, descriptor, claimToken, 1L);
    }
}
