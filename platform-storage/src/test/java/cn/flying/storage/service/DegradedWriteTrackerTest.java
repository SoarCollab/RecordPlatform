package cn.flying.storage.service;

import cn.flying.storage.config.StorageProperties;
import cn.flying.storage.core.FaultDomainManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 degraded write 记录的租户隔离、旧格式兼容和成功修复更新。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DegradedWriteTracker Tenant Isolation Tests")
class DegradedWriteTrackerTest {

    private static final String REDIS_KEY = "storage:degraded_writes";
    private static final String DUE_INDEX_KEY = "storage:degraded_writes:due";
    private static final String BACKFILL_CURSOR_KEY = "storage:degraded_writes:due:backfill_cursor";
    private static final String FAILURE_KEY = "storage:degraded_writes:repair_failures";
    private static final String DEAD_LETTER_KEY = "storage:degraded_writes:dead_letter";
    private static final String REVISION_KEY = "storage:degraded_writes:revisions";
    private static final String HASH = "sha256:abc";

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private FaultDomainManager faultDomainManager;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock updateLock;

    private ObjectMapper objectMapper;
    private DegradedWriteTracker tracker;
    private StorageProperties storageProperties;
    private Deque<List<Object>> claimResults;
    private List<String> executedScripts;
    private List<List<String>> executedScriptKeys;
    private List<Object[]> executedScriptArguments;
    private Deque<Runnable> beforeCasActions;
    private final Object casMonitor = new Object();

    /**
     * 注入真实 JSON 映射器与显式 HashOperations mock。
     */
    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        tracker = new DegradedWriteTracker();
        ReflectionTestUtils.setField(tracker, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(tracker, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(tracker, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(tracker, "redissonClient", redissonClient);
        storageProperties = new StorageProperties();
        ReflectionTestUtils.setField(tracker, "storageProperties", storageProperties);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        lenient().when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        lenient().when(hashOperations.get(eq(REVISION_KEY), any())).thenReturn(null);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(updateLock);
        lenient().when(updateLock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-a", "domain-b", "domain-c"));
        lenient().when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-a"))
                .thenReturn("node-a");
        lenient().when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-b");
        lenient().when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-c"))
                .thenReturn("node-c");
        lenient().when(faultDomainManager.getPlannedTargetsSnapshot(anyString()))
                .thenAnswer(invocation -> {
                    String objectHash = invocation.getArgument(0);
                    List<String> domains = faultDomainManager.getPlannedTargetDomains(objectHash);
                    if (domains == null) {
                        return Map.of();
                    }
                    Map<String, String> plannedTargets = new LinkedHashMap<>();
                    for (String domain : domains) {
                        plannedTargets.put(
                                domain,
                                faultDomainManager.getPlannedTargetNodeInDomain(objectHash, domain)
                        );
                    }
                    return Collections.unmodifiableMap(plannedTargets);
                });
        claimResults = new ArrayDeque<>();
        executedScripts = new ArrayList<>();
        executedScriptKeys = new ArrayList<>();
        executedScriptArguments = new ArrayList<>();
        beforeCasActions = new ArrayDeque<>();
        stubRedisScripts();
    }

    @Test
    @DisplayName("same content hash in two tenants should use independent Redis fields")
    void shouldIsolateSameHashAcrossTenants() {
        when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-a", "domain-b"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-a"))
                .thenReturn("node-a");
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-b");

        tracker.recordDegradedWrite(HASH, List.of("node-a"), 1L);
        tracker.recordDegradedWrite(HASH, List.of("node-a"), 2L);

        ArgumentCaptor<Object> fieldCaptor = ArgumentCaptor.forClass(Object.class);
        verify(hashOperations, org.mockito.Mockito.times(2))
                .put(eq(REDIS_KEY), fieldCaptor.capture(), anyString());
        assertThat(fieldCaptor.getAllValues()).containsExactly("1:" + HASH, "2:" + HASH);
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("2:" + HASH), anyDouble());
    }

    @Test
    @DisplayName("same-domain ring target change should remain a durable missing placement")
    void shouldTrackSameDomainRingTargetChange() throws Exception {
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-a"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-a"))
                .thenReturn("node-c");

        tracker.recordDegradedWrite(HASH, List.of("node-a"), 1L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), jsonCaptor.capture());
        DegradedWriteTracker.DegradedWriteRecord record = objectMapper.readValue(
                jsonCaptor.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(record.getWrittenNodes()).containsExactly("node-a");
        assertThat(record.getMissingDomains()).containsExactly("domain-a");
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
    }

    @Test
    @DisplayName("same-domain fallback write should not satisfy the exact planned target")
    void shouldTrackFallbackNodeAsMissingExactPlacement() throws Exception {
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-a"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-a"))
                .thenReturn("node-a");

        tracker.recordDegradedWrite(HASH, List.of("node-fallback"), 1L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), jsonCaptor.capture());
        DegradedWriteTracker.DegradedWriteRecord record = objectMapper.readValue(
                jsonCaptor.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(record.getWrittenNodes()).containsExactly("node-fallback");
        assertThat(record.getMissingDomains()).containsExactly("domain-a");
    }

    /**
     * 验证离线域未进入本轮成功节点列表时，权威记录仍生成该域的同步任务证据。
     */
    @Test
    @DisplayName("offline domain absent from successes should remain in durable task evidence")
    void shouldPersistOfflineDomainMissingFromSuccessfulNodes() throws Exception {
        Map<String, String> plannedTargets = new LinkedHashMap<>();
        plannedTargets.put("domain-a", "node-a");
        plannedTargets.put("domain-b", "node-b-offline");
        org.mockito.Mockito.doReturn(Collections.unmodifiableMap(plannedTargets))
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);

        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), jsonCaptor.capture());
        DegradedWriteTracker.DegradedWriteRecord record = objectMapper.readValue(
                jsonCaptor.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(record.getWrittenNodes()).containsExactly("node-a");
        assertThat(record.getMissingDomains()).containsExactly("domain-b");
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
    }

    /**
     * 验证物理重复拓扑被 manager 映射为空目标后，即使两个逻辑节点都成功也不能消除缺口。
     */
    @Test
    @DisplayName("duplicate physical targets should remain durable missing domains")
    void shouldNotCountDuplicatePhysicalTargetsAsIndependentReplicas() throws Exception {
        Map<String, String> unresolvedTargets = new LinkedHashMap<>();
        unresolvedTargets.put("domain-a", null);
        unresolvedTargets.put("domain-b", null);
        org.mockito.Mockito.doReturn(Collections.unmodifiableMap(unresolvedTargets))
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);

        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a", "node-b"), 1L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), jsonCaptor.capture());
        DegradedWriteTracker.DegradedWriteRecord record = objectMapper.readValue(
                jsonCaptor.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(record.getWrittenNodes()).containsExactly("node-a", "node-b");
        assertThat(record.getMissingDomains()).containsExactly("domain-a", "domain-b");
        verify(hashOperations, never()).delete(REDIS_KEY, "1:" + HASH);
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
    }

    /**
     * 验证拓扑刷新为物理重复状态时，迟到的修复成功不会改写或清理已有 durable truth。
     */
    @Test
    @DisplayName("unresolved physical topology should preserve an existing degraded record")
    void shouldPreserveDurableEvidenceWhenPhysicalTopologyIsUnresolved() throws Exception {
        String activeField = "1:" + HASH;
        lenient().when(hashOperations.hasKey(REDIS_KEY, activeField)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, activeField))
                .thenReturn(objectMapper.writeValueAsString(degradedRecord(List.of("domain-b"))));
        Map<String, String> unresolvedTargets = new LinkedHashMap<>();
        unresolvedTargets.put("domain-a", null);
        unresolvedTargets.put("domain-b", null);
        org.mockito.Mockito.doReturn(Collections.unmodifiableMap(unresolvedTargets))
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);

        tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b");

        verify(hashOperations, times(2)).get(REDIS_KEY, activeField);
        verify(hashOperations, never()).put(eq(REDIS_KEY), eq(activeField), anyString());
        verify(hashOperations, never()).delete(REDIS_KEY, activeField);
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq(activeField), anyDouble());
        assertThat(executedScripts).anyMatch(script -> script.contains("DEGRADED_TRANSITION_CAS"));
    }

    @Test
    @DisplayName("written exact planned target should not create a degraded record")
    void shouldSkipRecordWhenExactPlacementIsAlreadyWritten() {
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-a"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-a"))
                .thenReturn("node-a");

        tracker.recordDegradedWrite(HASH, List.of("node-a"), 1L);

        verify(hashOperations, never()).put(eq(REDIS_KEY), any(), anyString());
        verify(zSetOperations, never()).add(eq(DUE_INDEX_KEY), anyString(), anyDouble());
    }

    @Test
    @DisplayName("exact placement should clear an existing stale degraded record under lock")
    void shouldClearExistingStaleRecordWhenPlacementHasNoGap() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord stale = degradedRecord(List.of("domain-a"));
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-a"));
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(stale));

        tracker.recordDegradedWrite(HASH, List.of("node-a"), 1L);

        verify(updateLock).tryLock(5, TimeUnit.SECONDS);
        verify(hashOperations).delete(REDIS_KEY, "1:" + HASH);
        verify(zSetOperations).remove(DUE_INDEX_KEY, "1:" + HASH);
        verify(hashOperations, never()).put(eq(REDIS_KEY), any(), anyString());
    }

    @Test
    @DisplayName("authoritative observation should not let historical planned targets mask lost replicas")
    void shouldReplaceHistoricalCurrentTargetsWithAuthoritativeObservation() throws Exception {
        String activeField = "1:" + HASH;
        DegradedWriteTracker.DegradedWriteRecord historical = new DegradedWriteTracker.DegradedWriteRecord(
                HASH,
                List.of("node-a", "node-c", "node-old"),
                List.of("domain-d"),
                1L,
                123L
        );
        Map<String, String> redisState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField, objectMapper.writeValueAsString(historical));
        stubStatefulHash(redisState);
        when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-a", "domain-b", "domain-c", "domain-d"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-d"))
                .thenReturn("node-d");

        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a", "node-b"), 1L);

        DegradedWriteTracker.DegradedWriteRecord persisted = objectMapper.readValue(
                redisState.get(REDIS_KEY + "|" + activeField),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(persisted.getWrittenNodes()).containsExactly("node-old", "node-a", "node-b");
        assertThat(persisted.getMissingDomains()).containsExactly("domain-c", "domain-d");
        assertThat(persisted.getCreatedAt()).isEqualTo(123L);
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq(activeField), anyDouble());
    }

    /**
     * 验证重复降级写只保留本轮权威副本，并且每次 Redis 读改写只读取一个拓扑快照。
     */
    @Test
    @DisplayName("A/B then A/C authoritative writes should keep B missing with one snapshot per update")
    void shouldKeepReplicaMissingWhenLatestAuthoritativeWriteDoesNotVerifyIt() throws Exception {
        String activeField = "1:" + HASH;
        Map<String, String> redisState = new ConcurrentHashMap<>();
        stubStatefulHash(redisState);
        Map<String, String> plannedTargets = new LinkedHashMap<>();
        plannedTargets.put("domain-a", "node-a");
        plannedTargets.put("domain-b", "node-b");
        plannedTargets.put("domain-c", "node-c");
        org.mockito.Mockito.doReturn(Collections.unmodifiableMap(plannedTargets))
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);

        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a", "node-b"), 1L);
        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a", "node-c"), 1L);

        DegradedWriteTracker.DegradedWriteRecord persisted = objectMapper.readValue(
                redisState.get(REDIS_KEY + "|" + activeField),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(persisted.getWrittenNodes()).containsExactly("node-a", "node-c");
        assertThat(persisted.getMissingDomains()).containsExactly("domain-b");
        verify(faultDomainManager, times(2)).getPlannedTargetsSnapshot(HASH);
    }

    /**
     * 验证拓扑组件违反非空快照契约时失败关闭，不得把空拓扑当成全部副本已满足。
     */
    @Test
    @DisplayName("null planned-target snapshot should fail closed before mutating Redis")
    void shouldFailClosedWhenPlannedTargetSnapshotIsNull() {
        org.mockito.Mockito.doReturn(null)
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);

        assertThatThrownBy(() -> tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("planned target snapshot must not be null");

        verify(hashOperations, never()).put(eq(REDIS_KEY), any(), anyString());
        verify(hashOperations, never()).delete(eq(REDIS_KEY), any());
        verify(updateLock).unlock();
    }

    @Test
    @DisplayName("old target dead letter should not suppress a new target in the same domain")
    void shouldReactivateDomainWhenPlannedTargetChangesAfterDeadLetter() throws Exception {
        String oldTargetField = repairField(1L, "domain-b", "node-b");
        String newTargetField = repairField(1L, "domain-b", "node-c");
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-b"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-c");
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(false);
        lenient().when(hashOperations.hasKey(DEAD_LETTER_KEY, oldTargetField)).thenReturn(true);
        lenient().when(hashOperations.hasKey(DEAD_LETTER_KEY, newTargetField)).thenReturn(false);

        tracker.recordDegradedWrite(HASH, List.of("node-a"), 1L);

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), jsonCaptor.capture());
        DegradedWriteTracker.DegradedWriteRecord active = objectMapper.readValue(
                jsonCaptor.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(active.getMissingDomains()).containsExactly("domain-b");
        verify(hashOperations).hasKey(DEAD_LETTER_KEY, newTargetField);
        verify(hashOperations, never()).hasKey(DEAD_LETTER_KEY, oldTargetField);
    }

    @Test
    @DisplayName("recovery scheduler config should clamp unsafe values")
    void shouldClampRecoverySchedulerConfiguration() {
        StorageProperties.DegradedWriteConfig config = storageProperties.getDegradedWrite();
        config.setSyncBatchSize(Integer.MAX_VALUE);
        config.setClaimLeaseSeconds(1);
        config.setRepairTimeoutSeconds(Integer.MAX_VALUE);
        config.setRetryBackoffSeconds(Integer.MAX_VALUE);
        config.setMaxRetryBackoffSeconds(Integer.MAX_VALUE);

        assertThat(config.getEffectiveSyncBatchSize()).isEqualTo(1_000);
        assertThat(config.getEffectiveRepairTimeoutSeconds()).isEqualTo(1_800);
        assertThat(config.getEffectiveClaimLeaseSeconds()).isEqualTo(1_860);
        assertThat(config.getEffectiveRetryBackoffSeconds()).isEqualTo(86_400);
        assertThat(config.getEffectiveMaxRetryBackoffSeconds()).isEqualTo(86_400);
    }

    @Test
    @DisplayName("atomic due claim should honor batch limit and hide leases from another instance")
    void shouldClaimBoundedBatchOnlyOnceAcrossInstances() throws Exception {
        StorageProperties.DegradedWriteConfig config = storageProperties.getDegradedWrite();
        config.setSyncBatchSize(2);
        config.setClaimLeaseSeconds(1);
        config.setRepairTimeoutSeconds(1);
        claimResults.add(List.of(
                "1:" + HASH + ":1", objectMapper.writeValueAsString(degradedRecord(HASH + ":1")),
                "1:" + HASH + ":2", objectMapper.writeValueAsString(degradedRecord(HASH + ":2")),
                "1:" + HASH + ":3", objectMapper.writeValueAsString(degradedRecord(HASH + ":3"))
        ));
        claimResults.add(List.of());

        DegradedWriteTracker secondInstance = anotherTracker();
        List<DegradedWriteTracker.ClaimedDegradedWrite> firstClaims = tracker.claimPendingSyncs();
        List<DegradedWriteTracker.ClaimedDegradedWrite> secondClaims = secondInstance.claimPendingSyncs();

        assertThat(firstClaims).hasSize(2);
        assertThat(secondClaims).isEmpty();
        int claimScriptIndex = indexOfScript("ZRANGEBYSCORE");
        assertThat(executedScriptArguments.get(claimScriptIndex)[1]).isEqualTo("2");
        long now = Long.parseLong((String) executedScriptArguments.get(claimScriptIndex)[0]);
        long leaseUntil = Long.parseLong((String) executedScriptArguments.get(claimScriptIndex)[2]);
        assertThat(leaseUntil - now).isEqualTo(TimeUnit.SECONDS.toMillis(61));
        verify(hashOperations, never()).entries(REDIS_KEY);
    }

    @Test
    @DisplayName("legacy hash records should enter due index through persistent HSCAN cursor backfill")
    void shouldBackfillLegacyHashWithPersistentCursor() throws Exception {
        claimResults.add(List.of(
                "1:" + HASH,
                objectMapper.writeValueAsString(degradedRecord(List.of("domain-b")))
        ));

        List<DegradedWriteTracker.ClaimedDegradedWrite> claims = tracker.claimPendingSyncs();

        assertThat(claims).singleElement().satisfies(claim ->
                assertThat(claim.storageField()).isEqualTo("1:" + HASH));
        int backfillScriptIndex = indexOfScript("HSCAN");
        assertThat(executedScriptKeys.get(backfillScriptIndex))
                .containsExactly(REDIS_KEY, DUE_INDEX_KEY, BACKFILL_CURSOR_KEY);
        assertThat(executedScripts.get(backfillScriptIndex))
                .contains("redis.call('SET', KEYS[3], scan[1])");
        verify(hashOperations, never()).entries(REDIS_KEY);
    }

    @Test
    @DisplayName("empty due index should remain bounded without HGETALL")
    void shouldReturnEmptyClaimsWithoutReadingAllHashEntries() {
        assertThat(tracker.claimPendingSyncs()).isEmpty();

        verify(hashOperations, never()).entries(REDIS_KEY);
        assertThat(executedScripts).anyMatch(script -> script.contains("HSCAN"));
        assertThat(executedScripts).anyMatch(script -> script.contains("ZRANGEBYSCORE"));
    }

    @Test
    @DisplayName("marking one tenant synced should not delete another tenant field")
    void shouldDeleteOnlyExactTenantField() {
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.delete(REDIS_KEY, "1:" + HASH)).thenReturn(1L);

        tracker.markSynced(HASH, 1L);

        verify(hashOperations).delete(REDIS_KEY, "1:" + HASH);
        verify(hashOperations, never()).delete(REDIS_KEY, "2:" + HASH);
        verify(hashOperations, never()).delete(REDIS_KEY, HASH);
    }

    @Test
    @DisplayName("legacy field with another tenant should never be deleted")
    void shouldNotDeleteMismatchedLegacyTenant() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord legacy = new DegradedWriteTracker.DegradedWriteRecord(
                HASH,
                List.of("node-a"),
                List.of("domain-b"),
                2L,
                1L
        );
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(false);
        when(hashOperations.get(REDIS_KEY, HASH)).thenReturn(objectMapper.writeValueAsString(legacy));

        tracker.markSynced(HASH, 1L);

        verify(hashOperations).delete(REDIS_KEY, "1:" + HASH);
        verify(hashOperations, never()).delete(REDIS_KEY, HASH);
    }

    @Test
    @DisplayName("matching legacy field should complete through tenant-aware update")
    void shouldUpdateMatchingLegacyTenantRecord() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord legacy = degradedRecord(List.of("domain-b"));
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-b"));
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(false);
        when(hashOperations.get(REDIS_KEY, HASH)).thenReturn(objectMapper.writeValueAsString(legacy));

        tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b");

        verify(hashOperations).delete(REDIS_KEY, HASH);
        verify(hashOperations, never()).delete(REDIS_KEY, "1:" + HASH);
        verify(hashOperations).delete(FAILURE_KEY, repairField(1L, "domain-b"));
    }

    @Test
    @DisplayName("successful node repair should remove the matching missing domain")
    void shouldUpdateMissingDomainAfterVerifiedRepair() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = new DegradedWriteTracker.DegradedWriteRecord(
                HASH,
                List.of("node-a"),
                List.of("domain-b"),
                1L,
                1L
        );
        when(faultDomainManager.getNodeDomain("node-b")).thenReturn("domain-b");
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-b"));
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));

        tracker.markNodeRepaired(HASH, 1L, "node-b");

        verify(hashOperations).delete(REDIS_KEY, "1:" + HASH);
        verify(hashOperations, never()).put(eq(REDIS_KEY), eq("1:" + HASH), anyString());
        verify(zSetOperations).remove(DUE_INDEX_KEY, "1:" + HASH);
    }

    @Test
    @DisplayName("repair failure below threshold should remain tenant/domain scoped pending")
    void shouldKeepFailurePendingBelowThreshold() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b"));
        String repairField = repairField(1L, "domain-b");
        lenient().when(hashOperations.hasKey(DEAD_LETTER_KEY, repairField)).thenReturn(false);
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "target offline", 3);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.PENDING);
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(FAILURE_KEY), eq(repairField), jsonCaptor.capture());
        DegradedWriteTracker.RepairFailureState failure = objectMapper.readValue(
                jsonCaptor.getValue(),
                DegradedWriteTracker.RepairFailureState.class
        );
        assertThat(failure.getTenantId()).isEqualTo(1L);
        assertThat(failure.getDomain()).isEqualTo("domain-b");
        assertThat(failure.getTargetNode()).isEqualTo("node-b");
        assertThat(failure.getAttempts()).isEqualTo(1);
        verify(hashOperations, never()).put(eq(DEAD_LETTER_KEY), any(), anyString());
        verify(hashOperations, never()).delete(REDIS_KEY, "1:" + HASH);
        ArgumentCaptor<Double> dueAtCaptor = ArgumentCaptor.forClass(Double.class);
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), dueAtCaptor.capture());
        assertThat(dueAtCaptor.getValue()).isGreaterThan((double) failure.getLastFailureAt());
    }

    @Test
    @DisplayName("threshold failure should dead-letter only the matching tenant/domain")
    void shouldMoveOnlyFailedDomainToDeadLetterAtThreshold() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b", "domain-c"));
        String repairField = repairField(1L, "domain-b");
        DegradedWriteTracker.RepairFailureState previous = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-b", 2, 10L, 20L, "previous"
        );
        lenient().when(hashOperations.hasKey(DEAD_LETTER_KEY, repairField)).thenReturn(false);
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));
        when(hashOperations.get(FAILURE_KEY, repairField))
                .thenReturn(objectMapper.writeValueAsString(previous));

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "still offline", 3);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.DEAD_LETTERED);
        ArgumentCaptor<String> deadLetterJson = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(DEAD_LETTER_KEY), eq(repairField), deadLetterJson.capture());
        DegradedWriteTracker.DegradedWriteDeadLetter deadLetter = objectMapper.readValue(
                deadLetterJson.getValue(),
                DegradedWriteTracker.DegradedWriteDeadLetter.class
        );
        assertThat(deadLetter.getFailedDomain()).isEqualTo("domain-b");
        assertThat(deadLetter.getTargetNode()).isEqualTo("node-b");
        assertThat(deadLetter.getAttempts()).isEqualTo(3);
        assertThat(deadLetter.getDegradedWrite().getTenantId()).isEqualTo(1L);

        ArgumentCaptor<String> activeJson = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), activeJson.capture());
        DegradedWriteTracker.DegradedWriteRecord remaining = objectMapper.readValue(
                activeJson.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(remaining.getMissingDomains()).containsExactly("domain-c");
        verify(hashOperations).delete(FAILURE_KEY, repairField);
        verify(hashOperations, never()).delete(REDIS_KEY, "1:" + HASH);
        verify(hashOperations, never()).put(eq(DEAD_LETTER_KEY), eq(repairField(2L, "domain-b")), anyString());
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
    }

    @Test
    @DisplayName("dead-lettering the final missing domain should remove the due index")
    void shouldRemoveDueIndexWhenFinalDomainMovesToDeadLetter() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b"));
        String repairField = repairField(1L, "domain-b");
        DegradedWriteTracker.RepairFailureState previous = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-b", 2, 10L, 20L, "previous"
        );
        lenient().when(hashOperations.hasKey(DEAD_LETTER_KEY, repairField)).thenReturn(false);
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));
        when(hashOperations.get(FAILURE_KEY, repairField))
                .thenReturn(objectMapper.writeValueAsString(previous));

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "still offline", 3);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.DEAD_LETTERED);
        verify(hashOperations).delete(REDIS_KEY, "1:" + HASH);
        verify(zSetOperations).remove(DUE_INDEX_KEY, "1:" + HASH);
        verify(zSetOperations, never()).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
    }

    @Test
    @DisplayName("already dead-lettered failures should be idempotent")
    void shouldNotDuplicateExistingDeadLetter() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b"));
        String repairField = repairField(1L, "domain-b");
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));
        lenient().when(hashOperations.hasKey(DEAD_LETTER_KEY, repairField)).thenReturn(true);

        DegradedWriteTracker.RepairFailureDisposition first = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "duplicate", 3);
        DegradedWriteTracker.RepairFailureDisposition second = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "duplicate", 3);

        assertThat(first).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.ALREADY_DEAD_LETTERED);
        assertThat(second).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.ALREADY_DEAD_LETTERED);
        verify(hashOperations, never()).put(eq(DEAD_LETTER_KEY), eq(repairField), anyString());
        verify(hashOperations, times(2)).delete(REDIS_KEY, "1:" + HASH);
        verify(hashOperations, times(2)).delete(FAILURE_KEY, repairField);
    }

    @Test
    @DisplayName("successful repair should clear only the matching domain failure lifecycle")
    void shouldClearOnlySuccessfulDomainFailureState() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b", "domain-c"));
        String repairedField = repairField(1L, "domain-b");
        String otherField = repairField(1L, "domain-c");
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));
        when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-a", "domain-b", "domain-c"));

        tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b");

        verify(hashOperations).delete(FAILURE_KEY, repairedField);
        verify(hashOperations).delete(DEAD_LETTER_KEY, repairedField);
        verify(hashOperations, never()).delete(FAILURE_KEY, otherField);
        verify(hashOperations, never()).delete(DEAD_LETTER_KEY, otherField);
        ArgumentCaptor<String> activeJson = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), activeJson.capture());
        DegradedWriteTracker.DegradedWriteRecord remaining = objectMapper.readValue(
                activeJson.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(remaining.getMissingDomains()).containsExactly("domain-c");
        assertThat(remaining.getWrittenNodes()).containsExactly("node-a", "node-b");
    }

    @Test
    @DisplayName("failure attempts from an old target should not carry to a new target")
    void shouldResetFailureAttemptsWhenTargetChangesWithinDomain() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b"));
        String oldTargetField = repairField(1L, "domain-b", "node-b");
        String newTargetField = repairField(1L, "domain-b", "node-c");
        DegradedWriteTracker.RepairFailureState oldFailure = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-b", 2, 10L, 20L, "old target"
        );
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-c");
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));
        lenient().when(hashOperations.get(FAILURE_KEY, oldTargetField))
                .thenReturn(objectMapper.writeValueAsString(oldFailure));

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "new target offline", 3);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.PENDING);
        ArgumentCaptor<String> failureJson = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(FAILURE_KEY), eq(newTargetField), failureJson.capture());
        DegradedWriteTracker.RepairFailureState newFailure = objectMapper.readValue(
                failureJson.getValue(),
                DegradedWriteTracker.RepairFailureState.class
        );
        assertThat(newFailure.getTargetNode()).isEqualTo("node-c");
        assertThat(newFailure.getAttempts()).isEqualTo(1);
        verify(hashOperations, never()).get(FAILURE_KEY, oldTargetField);
    }

    @Test
    @DisplayName("failed attempt should be ignored when ring target changed during repair")
    void shouldIgnoreFailureWhenAttemptedTargetIsNoLongerCurrent() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-c");
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH,
                1L,
                "domain-b",
                "node-b",
                "old target failed",
                3
        );

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.IGNORED);
        verify(hashOperations, never()).put(eq(FAILURE_KEY), any(), anyString());
        verify(hashOperations, never()).put(eq(DEAD_LETTER_KEY), any(), anyString());
        verify(zSetOperations).add(eq(DUE_INDEX_KEY), eq("1:" + HASH), anyDouble());
    }

    @Test
    @DisplayName("exact target write should clear its dead letter before a later gap starts at attempt one")
    void shouldResetCurrentTargetLifecycleAfterExactPlacementSucceeds() throws Exception {
        String activeField = "1:" + HASH;
        String currentTargetField = repairField(1L, "domain-b", "node-b");
        String oldTargetField = repairField(1L, "domain-b", "node-old");
        Map<String, String> redisState = new ConcurrentHashMap<>();
        redisState.put(DEAD_LETTER_KEY + "|" + currentTargetField, "current dead letter");
        redisState.put(DEAD_LETTER_KEY + "|" + oldTargetField, "old target audit");
        stubStatefulHash(redisState);
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-b"));

        tracker.recordDegradedWrite(HASH, List.of("node-b"), 1L);
        tracker.recordDegradedWrite(HASH, List.of("node-a"), 1L);
        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "node-b", "missing again", 3);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.PENDING);
        assertThat(redisState).doesNotContainKey(DEAD_LETTER_KEY + "|" + currentTargetField);
        assertThat(redisState).containsKey(DEAD_LETTER_KEY + "|" + oldTargetField);
        assertThat(redisState).containsKey(REDIS_KEY + "|" + activeField);
        String failureJson = redisState.get(FAILURE_KEY + "|" + currentTargetField);
        DegradedWriteTracker.RepairFailureState failure = objectMapper.readValue(
                failureJson,
                DegradedWriteTracker.RepairFailureState.class
        );
        assertThat(failure.getTargetNode()).isEqualTo("node-b");
        assertThat(failure.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("late success from an old target should not clear the current target gap")
    void shouldKeepCurrentTargetMissingWhenOldTargetReportsSuccess() throws Exception {
        DegradedWriteTracker.DegradedWriteRecord record = degradedRecord(List.of("domain-b"));
        String currentTargetField = repairField(1L, "domain-b", "node-c");
        when(faultDomainManager.getPlannedTargetDomains(HASH)).thenReturn(List.of("domain-b"));
        when(faultDomainManager.getPlannedTargetNodeInDomain(HASH, "domain-b"))
                .thenReturn("node-c");
        lenient().when(hashOperations.hasKey(REDIS_KEY, "1:" + HASH)).thenReturn(true);
        when(hashOperations.get(REDIS_KEY, "1:" + HASH))
                .thenReturn(objectMapper.writeValueAsString(record));

        tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b");

        ArgumentCaptor<String> activeJson = ArgumentCaptor.forClass(String.class);
        verify(hashOperations).put(eq(REDIS_KEY), eq("1:" + HASH), activeJson.capture());
        DegradedWriteTracker.DegradedWriteRecord remaining = objectMapper.readValue(
                activeJson.getValue(),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(remaining.getMissingDomains()).containsExactly("domain-b");
        assertThat(remaining.getWrittenNodes()).containsExactly("node-a", "node-b");
        verify(hashOperations, never()).delete(FAILURE_KEY, currentTargetField);
        verify(hashOperations, never()).delete(DEAD_LETTER_KEY, currentTargetField);
        verify(hashOperations, never()).delete(REDIS_KEY, "1:" + HASH);
    }

    /**
     * 验证 revision 冲突时 active、due、failure 与 DLQ 不会出现任何部分提交。
     */
    @Test
    @DisplayName("revision mismatch should leave the entire repair transition unchanged")
    void shouldRejectRevisionMismatchWithoutPartialTransition() throws Exception {
        String activeField = "1:" + HASH;
        String failureField = repairField(1L, "domain-b", "node-b");
        DegradedWriteTracker.DegradedWriteRecord active = degradedRecord(List.of("domain-b", "domain-c"));
        DegradedWriteTracker.RepairFailureState previous = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-b", 2, 10L, 20L, "previous"
        );
        String activeJson = objectMapper.writeValueAsString(active);
        String failureJson = objectMapper.writeValueAsString(previous);
        Map<String, String> redisState = new ConcurrentHashMap<>();
        Map<String, Double> dueState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField, activeJson);
        redisState.put(FAILURE_KEY + "|" + failureField, failureJson);
        dueState.put(DUE_INDEX_KEY + "|" + activeField, 123D);
        stubStatefulHash(redisState);
        stubStatefulZSet(dueState);
        beforeCasActions.add(() -> hashOperations.put(REVISION_KEY, activeField, "1"));

        assertThatThrownBy(() -> tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "node-b", "still offline", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision CAS conflict");

        assertThat(redisState.get(REDIS_KEY + "|" + activeField)).isEqualTo(activeJson);
        assertThat(redisState.get(FAILURE_KEY + "|" + failureField)).isEqualTo(failureJson);
        assertThat(redisState).doesNotContainKey(DEAD_LETTER_KEY + "|" + failureField);
        assertThat(dueState.get(DUE_INDEX_KEY + "|" + activeField)).isEqualTo(123D);
        assertThat(redisState.get(REVISION_KEY + "|" + activeField)).isEqualTo("1");
    }

    /**
     * 验证旧成功回调 CAS 冲突后失败关闭，不会擦除另一个 owner 新增的缺失域。
     */
    @Test
    @DisplayName("stale domain success should not erase a newly rebuilt missing-domain record")
    void shouldKeepNewMissingDomainsWhenStaleSuccessConflicts() throws Exception {
        String activeField = "1:" + HASH;
        DegradedWriteTracker.DegradedWriteRecord initial = degradedRecord(List.of("domain-b", "domain-c"));
        DegradedWriteTracker.DegradedWriteRecord rebuilt = new DegradedWriteTracker.DegradedWriteRecord(
                HASH, List.of("node-a"), List.of("domain-b", "domain-d"), 1L, 2L
        );
        String rebuiltJson = objectMapper.writeValueAsString(rebuilt);
        Map<String, String> redisState = new ConcurrentHashMap<>();
        Map<String, Double> dueState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField, objectMapper.writeValueAsString(initial));
        dueState.put(DUE_INDEX_KEY + "|" + activeField, 100D);
        stubStatefulHash(redisState);
        stubStatefulZSet(dueState);
        beforeCasActions.add(() -> {
            hashOperations.put(REDIS_KEY, activeField, rebuiltJson);
            hashOperations.put(REVISION_KEY, activeField, "1");
            zSetOperations.add(DUE_INDEX_KEY, activeField, 777D);
        });

        assertThatThrownBy(() -> tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision CAS conflict");

        assertThat(redisState.get(REDIS_KEY + "|" + activeField)).isEqualTo(rebuiltJson);
        assertThat(dueState.get(DUE_INDEX_KEY + "|" + activeField)).isEqualTo(777D);
    }

    /**
     * 验证 active 原文校验可捕获未更新 revision 的旧版本并发写入。
     */
    @Test
    @DisplayName("raw active mismatch should fail CAS even when revision did not change")
    void shouldRejectRawMismatchWithoutRevisionChange() throws Exception {
        String activeField = "1:" + HASH;
        DegradedWriteTracker.DegradedWriteRecord initial = degradedRecord(List.of("domain-b"));
        DegradedWriteTracker.DegradedWriteRecord external = new DegradedWriteTracker.DegradedWriteRecord(
                HASH, List.of("node-a"), List.of("domain-c"), 1L, 2L
        );
        String externalJson = objectMapper.writeValueAsString(external);
        Map<String, String> redisState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField, objectMapper.writeValueAsString(initial));
        stubStatefulHash(redisState);
        beforeCasActions.add(() -> hashOperations.put(REDIS_KEY, activeField, externalJson));

        assertThatThrownBy(() -> tracker.markSynced(HASH, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision CAS conflict");

        assertThat(redisState.get(REDIS_KEY + "|" + activeField)).isEqualTo(externalJson);
    }

    /**
     * 验证删除后以相同 JSON 重建仍会被 revision 拦截，并保留新 due 与锁失效后的主异常。
     */
    @Test
    @DisplayName("same-json delete/recreate ABA should survive a stale delete after lock loss")
    void shouldFenceSameJsonAbaAndPreserveRebuiltDueAfterLockLoss() throws Exception {
        String activeField = "1:" + HASH;
        String activeJson = objectMapper.writeValueAsString(degradedRecord(List.of("domain-b")));
        Map<String, String> redisState = new ConcurrentHashMap<>();
        Map<String, Double> dueState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField, activeJson);
        dueState.put(DUE_INDEX_KEY + "|" + activeField, 100D);
        stubStatefulHash(redisState);
        stubStatefulZSet(dueState);
        beforeCasActions.add(() -> {
            hashOperations.delete(REDIS_KEY, activeField);
            zSetOperations.remove(DUE_INDEX_KEY, activeField);
            hashOperations.put(REVISION_KEY, activeField, "1");
            hashOperations.put(REDIS_KEY, activeField, activeJson);
            zSetOperations.add(DUE_INDEX_KEY, activeField, 888D);
            hashOperations.put(REVISION_KEY, activeField, "2");
        });
        org.mockito.Mockito.doThrow(new IllegalMonitorStateException("watchdog ownership lost"))
                .when(updateLock).unlock();

        assertThatThrownBy(() -> tracker.markSynced(HASH, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision CAS conflict");

        assertThat(redisState.get(REDIS_KEY + "|" + activeField)).isEqualTo(activeJson);
        assertThat(dueState.get(DUE_INDEX_KEY + "|" + activeField)).isEqualTo(888D);
        assertThat(redisState.get(REVISION_KEY + "|" + activeField)).isEqualTo("2");
    }

    /**
     * 验证旧 target 的失败转死信在 CAS 冲突后不会污染新 target 生命周期。
     */
    @Test
    @DisplayName("stale failure to DLQ transition should not touch a rebuilt target lifecycle")
    void shouldKeepNewTargetFailureWhenStaleDeadLetterTransitionConflicts() throws Exception {
        String activeField = "1:" + HASH;
        String oldFailureField = repairField(1L, "domain-b", "node-b");
        String newFailureField = repairField(1L, "domain-b", "node-c");
        DegradedWriteTracker.RepairFailureState oldFailure = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-b", 2, 10L, 20L, "old"
        );
        DegradedWriteTracker.RepairFailureState newFailure = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-c", 1, 30L, 30L, "new"
        );
        String newFailureJson = objectMapper.writeValueAsString(newFailure);
        Map<String, String> redisState = new ConcurrentHashMap<>();
        Map<String, Double> dueState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField,
                objectMapper.writeValueAsString(degradedRecord(List.of("domain-b"))));
        redisState.put(FAILURE_KEY + "|" + oldFailureField, objectMapper.writeValueAsString(oldFailure));
        dueState.put(DUE_INDEX_KEY + "|" + activeField, 100D);
        stubStatefulHash(redisState);
        stubStatefulZSet(dueState);
        beforeCasActions.add(() -> {
            hashOperations.delete(FAILURE_KEY, oldFailureField);
            hashOperations.put(FAILURE_KEY, newFailureField, newFailureJson);
            hashOperations.put(REVISION_KEY, activeField, "1");
            zSetOperations.add(DUE_INDEX_KEY, activeField, 999D);
        });

        assertThatThrownBy(() -> tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "node-b", "late failure", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("revision CAS conflict");

        assertThat(redisState).doesNotContainKey(DEAD_LETTER_KEY + "|" + oldFailureField);
        assertThat(redisState.get(FAILURE_KEY + "|" + newFailureField)).isEqualTo(newFailureJson);
        assertThat(dueState.get(DUE_INDEX_KEY + "|" + activeField)).isEqualTo(999D);
    }

    /**
     * 验证 record 的 CAS 重试会重新获取拓扑快照，不会用旧 placement 覆盖新缺口。
     */
    @Test
    @DisplayName("record retry should recompute against the post-conflict topology snapshot")
    void shouldRefreshTopologySnapshotBeforeRecordCasRetry() throws Exception {
        String activeField = "1:" + HASH;
        Map<String, String> oldTargets = new LinkedHashMap<>();
        oldTargets.put("domain-a", "node-a");
        oldTargets.put("domain-b", "node-b");
        Map<String, String> newTargets = new LinkedHashMap<>();
        newTargets.put("domain-a", "node-a");
        newTargets.put("domain-c", "node-c");
        org.mockito.Mockito.doReturn(
                        Collections.unmodifiableMap(oldTargets),
                        Collections.unmodifiableMap(newTargets)
                )
                .when(faultDomainManager)
                .getPlannedTargetsSnapshot(HASH);
        DegradedWriteTracker.DegradedWriteRecord rebuilt = new DegradedWriteTracker.DegradedWriteRecord(
                HASH, List.of("node-a"), List.of("domain-c"), 1L, 2L
        );
        Map<String, String> redisState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField,
                objectMapper.writeValueAsString(degradedRecord(List.of("domain-b"))));
        stubStatefulHash(redisState);
        beforeCasActions.add(() -> {
            hashOperations.put(REDIS_KEY, activeField, serialize(rebuilt));
            hashOperations.put(REVISION_KEY, activeField, "1");
        });

        tracker.recordAuthoritativeDegradedWrite(HASH, List.of("node-a"), 1L);

        DegradedWriteTracker.DegradedWriteRecord persisted = objectMapper.readValue(
                redisState.get(REDIS_KEY + "|" + activeField),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(persisted.getMissingDomains()).containsExactly("domain-c");
        assertThat(redisState.get(REVISION_KEY + "|" + activeField)).isEqualTo("2");
        verify(faultDomainManager, times(2)).getPlannedTargetsSnapshot(HASH);
    }

    /**
     * 验证成功死信转移一次性更新 active、due、failure、DLQ 与 revision。
     */
    @Test
    @DisplayName("successful dead-letter transition should commit every related Redis structure")
    void shouldCommitActiveDueFailureAndDeadLetterTogether() throws Exception {
        String activeField = "1:" + HASH;
        String failureField = repairField(1L, "domain-b", "node-b");
        DegradedWriteTracker.RepairFailureState previous = new DegradedWriteTracker.RepairFailureState(
                HASH, 1L, "domain-b", "node-b", 2, 10L, 20L, "previous"
        );
        Map<String, String> redisState = new ConcurrentHashMap<>();
        Map<String, Double> dueState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField,
                objectMapper.writeValueAsString(degradedRecord(List.of("domain-b", "domain-c"))));
        redisState.put(FAILURE_KEY + "|" + failureField, objectMapper.writeValueAsString(previous));
        dueState.put(DUE_INDEX_KEY + "|" + activeField, 100D);
        stubStatefulHash(redisState);
        stubStatefulZSet(dueState);

        DegradedWriteTracker.RepairFailureDisposition disposition = tracker.recordRepairFailure(
                HASH, 1L, "domain-b", "node-b", "threshold", 3);

        assertThat(disposition).isEqualTo(DegradedWriteTracker.RepairFailureDisposition.DEAD_LETTERED);
        DegradedWriteTracker.DegradedWriteRecord remaining = objectMapper.readValue(
                redisState.get(REDIS_KEY + "|" + activeField),
                DegradedWriteTracker.DegradedWriteRecord.class
        );
        assertThat(remaining.getMissingDomains()).containsExactly("domain-c");
        assertThat(redisState).doesNotContainKey(FAILURE_KEY + "|" + failureField);
        assertThat(redisState).containsKey(DEAD_LETTER_KEY + "|" + failureField);
        assertThat(dueState.get(DUE_INDEX_KEY + "|" + activeField)).isGreaterThan(100D);
        assertThat(redisState.get(REVISION_KEY + "|" + activeField)).isEqualTo("1");
    }

    @Test
    @DisplayName("concurrent domain success updates should not overwrite each other")
    void shouldSerializeConcurrentDomainUpdates() throws Exception {
        String activeField = "1:" + HASH;
        Map<String, String> redisState = new ConcurrentHashMap<>();
        redisState.put(REDIS_KEY + "|" + activeField,
                objectMapper.writeValueAsString(degradedRecord(List.of("domain-b", "domain-c"))));
        stubStatefulHash(redisState);
        when(faultDomainManager.getPlannedTargetDomains(HASH))
                .thenReturn(List.of("domain-a", "domain-b", "domain-c"));
        ReentrantLock delegate = new ReentrantLock();
        doAnswer(invocation -> delegate.tryLock(5, TimeUnit.SECONDS))
                .when(updateLock).tryLock(5, TimeUnit.SECONDS);
        doAnswer(invocation -> {
            delegate.unlock();
            return null;
        }).when(updateLock).unlock();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> {
                await(start);
                tracker.updateMissingDomains(HASH, 1L, "domain-b", "node-b");
            });
            var second = executor.submit(() -> {
                await(start);
                tracker.updateMissingDomains(HASH, 1L, "domain-c", "node-c");
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(redisState).doesNotContainKey(REDIS_KEY + "|" + activeField);
        verify(updateLock, times(2)).tryLock(5, TimeUnit.SECONDS);
        verify(updateLock, times(2)).unlock();
        verify(updateLock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("busy update lock should fail closed without mutating Redis")
    void shouldFailClosedWhenUpdateLockIsBusy() throws Exception {
        when(updateLock.tryLock(5, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> tracker.markSynced(HASH, 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lock is busy");

        verify(hashOperations, never()).delete(anyString(), any());
        verify(updateLock, never()).unlock();
    }

    @Test
    @DisplayName("interrupted update lock wait should preserve the interrupt status")
    void shouldPreserveInterruptWhenUpdateLockWaitIsInterrupted() throws Exception {
        when(updateLock.tryLock(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());

        try {
            assertThatThrownBy(() -> tracker.markSynced(HASH, 1L))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(hashOperations, never()).delete(anyString(), any());
            verify(updateLock, never()).unlock();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("watchdog update lock should release directly without an ownership probe")
    void shouldReleaseWatchdogUpdateLockWithoutOwnershipProbe() {
        when(hashOperations.delete(REDIS_KEY, "1:" + HASH)).thenReturn(1L);

        tracker.markSynced(HASH, 1L);

        verify(updateLock).unlock();
        verify(updateLock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("lost update-lock ownership should be tolerated after entering unlock path")
    void shouldTolerateLostUpdateLockOwnership() {
        when(hashOperations.delete(REDIS_KEY, "1:" + HASH)).thenReturn(0L);
        org.mockito.Mockito.doThrow(new IllegalMonitorStateException("ownership lost"))
                .when(updateLock).unlock();

        tracker.markSynced(HASH, 1L);

        verify(updateLock).unlock();
        verify(updateLock, never()).isHeldByCurrentThread();
    }

    @Test
    @DisplayName("unlock Redis failure should not replace the primary update failure")
    void shouldPreservePrimaryFailureWhenUnlockAlsoFails() {
        when(hashOperations.delete(REDIS_KEY, "1:" + HASH))
                .thenThrow(new IllegalArgumentException("primary update failure"));
        org.mockito.Mockito.doThrow(new RedisConnectionException("release failed"))
                .when(updateLock).unlock();

        assertThatThrownBy(() -> tracker.markSynced(HASH, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("primary update failure");

        verify(updateLock).unlock();
        verify(updateLock, never()).isHeldByCurrentThread();
    }

    /**
     * 创建包含指定缺失域的租户记录。
     */
    private DegradedWriteTracker.DegradedWriteRecord degradedRecord(List<String> missingDomains) {
        return new DegradedWriteTracker.DegradedWriteRecord(
                HASH,
                List.of("node-a"),
                missingDomains,
                1L,
                1L
        );
    }

    /**
     * 创建使用指定对象哈希的待恢复记录，供批次领取测试区分成员。
     */
    private DegradedWriteTracker.DegradedWriteRecord degradedRecord(String objectHash) {
        return new DegradedWriteTracker.DegradedWriteRecord(
                objectHash,
                List.of("node-a"),
                List.of("domain-b"),
                1L,
                1L
        );
    }

    /**
     * 创建共享同一 Redis mock 的第二个 tracker，模拟多实例竞争同一 due claim。
     */
    private DegradedWriteTracker anotherTracker() {
        DegradedWriteTracker another = new DegradedWriteTracker();
        ReflectionTestUtils.setField(another, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(another, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(another, "faultDomainManager", faultDomainManager);
        ReflectionTestUtils.setField(another, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(another, "storageProperties", storageProperties);
        return another;
    }

    /**
     * 模拟 Redis 对 Lua 的原子执行，并记录脚本、KEYS 与 ARGV 供边界断言。
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
            executedScriptKeys.add(List.copyOf(keys));
            executedScriptArguments.add(arguments.clone());
            if (scriptText.contains("DEGRADED_STATE_SNAPSHOT")) {
                return emulateRevisionedSnapshot(keys, arguments);
            }
            if (scriptText.contains("DEGRADED_TRANSITION_CAS")) {
                return emulateTransitionCas(keys, arguments);
            }
            if (scriptText.contains("HSCAN")) {
                return 0L;
            }
            if (scriptText.contains("ZRANGEBYSCORE")) {
                return claimResults.isEmpty() ? List.of() : claimResults.removeFirst();
            }
            if (scriptText.contains("HEXISTS")) {
                return 1L;
            }
            return null;
        }).when(redisTemplate).execute(
                any(RedisScript.class),
                any(List.class),
                any(Object[].class)
        );
    }

    /**
     * 模拟生产 Lua 对 primary、legacy 与 revision 的同点快照读取。
     */
    private List<Object> emulateRevisionedSnapshot(List<String> keys, Object[] rawArguments) {
        String primaryField = String.valueOf(rawArguments[0]);
        String legacyField = String.valueOf(rawArguments[1]);
        String fenceField = String.valueOf(rawArguments[2]);
        Object primaryRaw = hashOperations.get(keys.get(0), primaryField);
        Object legacyRaw = primaryField.equals(legacyField)
                ? primaryRaw
                : hashOperations.get(keys.get(0), legacyField);
        Object revision = hashOperations.get(keys.get(1), fenceField);
        return List.of(
                revision == null ? "0" : String.valueOf(revision),
                primaryRaw == null ? "0" : "1",
                primaryRaw == null ? "" : String.valueOf(primaryRaw),
                legacyRaw == null ? "0" : "1",
                legacyRaw == null ? "" : String.valueOf(legacyRaw)
        );
    }

    /**
     * 在 Mockito 状态上原子模拟 revision + active 原文 CAS，并执行脚本声明的完整转移。
     */
    private Long emulateTransitionCas(List<String> keys, Object[] rawArguments) {
        synchronized (casMonitor) {
            if (!beforeCasActions.isEmpty()) {
                beforeCasActions.removeFirst().run();
            }
            List<String> arguments = java.util.Arrays.stream(rawArguments)
                    .map(String::valueOf)
                    .toList();
            String storageField = arguments.get(0);
            String fenceField = arguments.get(1);
            long expectedRevision = Long.parseLong(arguments.get(2));
            long nextRevision = Long.parseLong(arguments.get(3));
            String primaryField = arguments.get(4);
            boolean primaryExpectedExists = "1".equals(arguments.get(5));
            String primaryExpectedRaw = arguments.get(6);
            String legacyField = arguments.get(7);
            boolean legacyExpectedExists = "1".equals(arguments.get(8));
            String legacyExpectedRaw = arguments.get(9);
            Object currentPrimaryRaw = hashOperations.get(keys.get(0), primaryField);
            Object currentLegacyRaw = primaryField.equals(legacyField)
                    ? currentPrimaryRaw
                    : hashOperations.get(keys.get(0), legacyField);
            Object currentRevisionValue = hashOperations.get(keys.get(4), fenceField);
            long currentRevision = currentRevisionValue == null
                    ? 0L
                    : Long.parseLong(String.valueOf(currentRevisionValue));
            if (currentRevision != expectedRevision
                    || primaryExpectedExists != (currentPrimaryRaw != null)
                    || (primaryExpectedExists
                    && !primaryExpectedRaw.equals(String.valueOf(currentPrimaryRaw)))
                    || legacyExpectedExists != (currentLegacyRaw != null)
                    || (legacyExpectedExists
                    && !legacyExpectedRaw.equals(String.valueOf(currentLegacyRaw)))) {
                return 0L;
            }

            int index = 10;
            String activeMutation = arguments.get(index++);
            String activeJson = arguments.get(index++);
            String dueMutation = arguments.get(index++);
            double dueScore = Double.parseDouble(arguments.get(index++));
            if ("S".equals(activeMutation)) {
                hashOperations.put(keys.get(0), storageField, activeJson);
            } else if ("D".equals(activeMutation)) {
                hashOperations.delete(keys.get(0), storageField);
            }
            if ("S".equals(dueMutation)) {
                zSetOperations.add(keys.get(1), storageField, dueScore);
            } else if ("D".equals(dueMutation)) {
                zSetOperations.remove(keys.get(1), storageField);
            }

            index = applyHashPuts(keys.get(2), arguments, index);
            index = applyHashDeletes(keys.get(2), arguments, index);
            index = applyHashPuts(keys.get(3), arguments, index);
            applyHashDeletes(keys.get(3), arguments, index);
            hashOperations.put(keys.get(4), fenceField, String.valueOf(nextRevision));
            return 1L;
        }
    }

    /** 模拟 Lua 中 count + field/value 形式的 Hash 写集合。 */
    private int applyHashPuts(String redisKey, List<String> arguments, int startIndex) {
        int count = Integer.parseInt(arguments.get(startIndex));
        int index = startIndex + 1;
        for (int current = 0; current < count; current++) {
            hashOperations.put(redisKey, arguments.get(index), arguments.get(index + 1));
            index += 2;
        }
        return index;
    }

    /** 模拟 Lua 中 count + field 形式的 Hash 删除集合。 */
    private int applyHashDeletes(String redisKey, List<String> arguments, int startIndex) {
        int count = Integer.parseInt(arguments.get(startIndex));
        int index = startIndex + 1;
        for (int current = 0; current < count; current++) {
            hashOperations.delete(redisKey, arguments.get(index++));
        }
        return index;
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
     * 复现生产代码的 tenant/hash/domain 隔离 field。
     */
    private String repairField(Long tenantId, String domain) {
        return repairField(tenantId, domain, "node-" + domain.charAt(domain.length() - 1));
    }

    /**
     * 复现生产代码的 tenant/hash/domain/target 隔离 field。
     */
    private String repairField(Long tenantId, String domain, String targetNode) {
        String encodedDomain = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(domain.getBytes(StandardCharsets.UTF_8));
        String encodedTarget = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(targetNode.getBytes(StandardCharsets.UTF_8));
        return tenantId + ":" + HASH + ":" + encodedDomain + ":" + encodedTarget;
    }

    /**
     * 用线程安全 Map 模拟 Redis Hash，验证锁保护下的并发读改写。
     */
    private void stubStatefulHash(Map<String, String> redisState) {
        lenient().when(hashOperations.hasKey(anyString(), any())).thenAnswer(invocation ->
                redisState.containsKey(invocation.getArgument(0) + "|" + invocation.getArgument(1)));
        lenient().when(hashOperations.get(anyString(), any())).thenAnswer(invocation ->
                redisState.get(invocation.getArgument(0) + "|" + invocation.getArgument(1)));
        lenient().doAnswer(invocation -> {
            redisState.put(
                    invocation.getArgument(0) + "|" + invocation.getArgument(1),
                    invocation.getArgument(2)
            );
            return null;
        }).when(hashOperations).put(anyString(), any(), any());
        lenient().when(hashOperations.delete(anyString(), any())).thenAnswer(invocation ->
                redisState.remove(invocation.getArgument(0) + "|" + invocation.getArgument(1)) != null ? 1L : 0L);
    }

    /**
     * 用线程安全 Map 模拟 Redis ZSET，精确断言 CAS 冲突不会覆盖新 due score。
     */
    private void stubStatefulZSet(Map<String, Double> dueState) {
        lenient().when(zSetOperations.add(anyString(), anyString(), anyDouble())).thenAnswer(invocation -> {
            dueState.put(
                    invocation.getArgument(0) + "|" + invocation.getArgument(1),
                    invocation.getArgument(2)
            );
            return true;
        });
        lenient().when(zSetOperations.remove(anyString(), any())).thenAnswer(invocation -> {
            Object rawMembers = invocation.getArgument(1);
            Object member = rawMembers instanceof Object[] members ? members[0] : rawMembers;
            return dueState.remove(invocation.getArgument(0) + "|" + member) != null ? 1L : 0L;
        });
    }

    /**
     * 在并发 hook 中序列化测试状态，并把不可恢复异常转换为测试失败。
     */
    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new AssertionError("failed to serialize test state", e);
        }
    }

    /**
     * 在线程启动屏障上等待，并把中断转换为测试失败。
     */
    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for concurrent update start");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for concurrent update start", e);
        }
    }
}
