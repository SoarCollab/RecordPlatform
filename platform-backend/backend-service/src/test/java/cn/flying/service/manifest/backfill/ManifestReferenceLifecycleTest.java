package cn.flying.service.manifest.backfill;

import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.entity.ManifestReferenceCensus;
import cn.flying.dao.entity.ManifestReferenceLedger;
import cn.flying.dao.entity.ManifestReferenceSweepMark;
import cn.flying.dao.mapper.ManifestReferenceCensusMapper;
import cn.flying.dao.mapper.ManifestReferenceLedgerMapper;
import cn.flying.dao.mapper.ManifestReferenceSweepMarkMapper;
import cn.flying.dao.mapper.TenantMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.remote.FileRemoteClient;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies conservative reference census and mark/grace/delete lifecycle decisions.
 */
@ExtendWith(MockitoExtension.class)
class ManifestReferenceLifecycleTest {

    private static final Long TENANT_ID = 11L;
    private static final String CIPHER_HASH = "sha256:" + "3".repeat(64);
    private static final String STORAGE_PATH = "storage/tenant/11/chunk/" + CIPHER_HASH;
    private static final String IDENTITY_DIGEST =
            "1f823e4d25e8c61ad165a616af8fe5482b7227317b7c73bdd1cee8495f392a08";

    @Mock
    private ManifestReferenceCensusMapper censusMapper;

    @Mock
    private ManifestReferenceLedgerMapper ledgerMapper;

    @Mock
    private ManifestReferenceSweepMarkMapper markMapper;

    @Mock
    private ManifestReferenceSweepClaimService claimService;

    @Mock
    private FileRemoteClient fileRemoteClient;

    @Mock
    private TenantMapper tenantMapper;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ManifestReferenceCensusService censusService;
    private ManifestReferenceSweepService sweepService;

    /**
     * Establishes tenant isolation and the two lifecycle services.
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        censusService = new ManifestReferenceCensusService(
                censusMapper, ledgerMapper, fileRemoteClient, snowflakeIdGenerator, transactionTemplate);
        sweepService = new ManifestReferenceSweepService(
                censusService, ledgerMapper, markMapper, claimService, fileRemoteClient,
                tenantMapper, snowflakeIdGenerator);
        ReflectionTestUtils.setField(sweepService, "markEnabled", true);
        ReflectionTestUtils.setField(sweepService, "deleteEnabled", true);
        ReflectionTestUtils.setField(sweepService, "protectionDays", 30);
        ReflectionTestUtils.setField(sweepService, "batchSize", 20);
        ReflectionTestUtils.setField(sweepService, "leaseSeconds", 120L);
    }

    /**
     * Clears the tenant boundary after each lifecycle example.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Materializes every reference source and adds an unknown hold for degraded repairs.
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldMaterializeAllSourcesAndDegradedUnknownHold() {
        when(snowflakeIdGenerator.nextId()).thenReturn(100L);
        when(fileRemoteClient.getDegradedWriteCount()).thenReturn(Result.success(2L));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<ManifestReferenceCensus> callback = invocation.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
        ManifestReferenceLedger known = new ManifestReferenceLedger()
                .setObjectIdentityDigest("known")
                .setSourceType("MANIFEST")
                .setSourceKeyDigest("source-known")
                .setHoldReason("LIVE")
                .setKnownReference(1);
        ManifestReferenceLedger unknown = new ManifestReferenceLedger()
                .setObjectIdentityDigest("unknown")
                .setSourceType("SAGA")
                .setSourceKeyDigest("source-unknown")
                .setHoldReason("UNKNOWN_REFERENCE")
                .setKnownReference(0);
        when(ledgerMapper.selectCensusRows(100L, TENANT_ID)).thenReturn(List.of(known, unknown));
        when(censusMapper.selectById(100L)).thenReturn(completedCensus(100L));

        ManifestReferenceCensus result = censusService.createCensus(TENANT_ID);

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        verify(ledgerMapper).insertManifestReferences(100L, TENANT_ID);
        verify(ledgerMapper).insertFileVersionReferences(100L, TENANT_ID);
        verify(ledgerMapper).insertShareReferences(100L, TENANT_ID);
        verify(ledgerMapper).insertAttestationReferences(100L, TENANT_ID);
        verify(ledgerMapper).insertAttestationCandidateReferences(100L, TENANT_ID);
        verify(ledgerMapper).insertLegacyAttestationUnknownHolds(100L, TENANT_ID);
        verify(ledgerMapper).insertProofReferences(100L, TENANT_ID);
        verify(ledgerMapper).insertLegacyUnknownHolds(100L, TENANT_ID);
        verify(ledgerMapper).insertSagaUnknownHolds(100L, TENANT_ID);
        verify(ledgerMapper).insertFinalizationUnknownHolds(100L, TENANT_ID);
        ArgumentCaptor<ManifestReferenceLedger> degradedHold =
                ArgumentCaptor.forClass(ManifestReferenceLedger.class);
        verify(ledgerMapper).insert(degradedHold.capture());
        assertThat(degradedHold.getValue().getKnownReference()).isZero();
        assertThat(degradedHold.getValue().getHoldReason()).isEqualTo("DEGRADED_REPAIR_PENDING");
    }

    /**
     * Ensures a source-tenant share remains visible in the object owner's protective census.
     */
    @Test
    void shouldCensusCrossTenantSharesByProtectedManifestTenant() throws NoSuchMethodException {
        Insert statement = ManifestReferenceLedgerMapper.class
                .getMethod("insertShareReferences", Long.class, Long.class)
                .getAnnotation(Insert.class);
        String sql = String.join("\n", statement.value());

        assertThat(sql).contains(
                "SELECT #{censusId}, #{tenantId}, manifest.tenant_id",
                "AND manifest.tenant_id = #{tenantId}");
        assertThat(sql).doesNotContain("WHERE source.tenant_id = #{tenantId}");
    }

    /**
     * Reuses the durable mark when a duplicate sweep request races the first request.
     */
    @Test
    void shouldReturnExistingMarkForDuplicateRequest() {
        ManifestReferenceCensus census = completedCensus(200L);
        ManifestReferenceSweepMark existing = mark(300L);
        when(fileRemoteClient.getDegradedWriteCount()).thenReturn(Result.success(0L));
        executeCensusAsEmpty(census);
        when(ledgerMapper.countExactReferences(anyLong(), eq(TENANT_ID), any())).thenReturn(0L);
        when(ledgerMapper.countUnknownHolds(anyLong(), eq(TENANT_ID))).thenReturn(0L);
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH)).thenReturn(Result.success(head("etag-a")));
        when(markMapper.insertIgnoreMark(any())).thenReturn(0);
        when(markMapper.selectByObjectIdentity(eq(TENANT_ID), any())).thenReturn(existing);

        ManifestReferenceSweepMark result = sweepService.markObject(TENANT_ID, STORAGE_PATH, CIPHER_HASH);

        assertThat(result).isSameAs(existing);
        verify(markMapper).selectByObjectIdentity(eq(TENANT_ID), any());
    }

    /**
     * Cancels deletion when a reference appears in the fresh delete-time census.
     */
    @Test
    void shouldRetainMarkWhenReferenceReappears() {
        ManifestReferenceSweepMark mark = mark(300L);
        when(claimService.claimDue(20, 120L)).thenReturn(List.of(mark));
        executeCensusAsEmpty(completedCensus(201L));
        when(fileRemoteClient.getDegradedWriteCount()).thenReturn(Result.success(0L));
        when(ledgerMapper.countExactReferences(anyLong(), eq(TENANT_ID), eq(IDENTITY_DIGEST)))
                .thenReturn(1L);
        when(markMapper.completeClaim(
                300L, TENANT_ID, "claim-a", "RETAINED", "REFERENCE_REAPPEARED", null, null))
                .thenReturn(1);

        sweepService.sweepTenantBatch();

        verify(fileRemoteClient, never()).deleteStorageFile(any());
        verify(markMapper).completeClaim(
                300L, TENANT_ID, "claim-a", "RETAINED", "REFERENCE_REAPPEARED", null, null);
    }

    /**
     * Retains an object when delete-time HEAD identity differs from mark-time evidence.
     */
    @Test
    void shouldRetainMarkWhenDeleteTimeHeadChanges() {
        ManifestReferenceSweepMark mark = mark(300L);
        givenUnreferencedClaim(mark, 202L);
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH))
                .thenReturn(Result.success(head("etag-changed")));
        when(markMapper.completeClaim(
                300L, TENANT_ID, "claim-a", "RETAINED", "DELETE_HEAD_MISMATCH", null, null))
                .thenReturn(1);

        sweepService.sweepTenantBatch();

        verify(fileRemoteClient, never()).deleteStorageFile(any());
    }

    /**
     * Schedules a bounded retry when the tenant-scoped storage delete RPC fails.
     */
    @Test
    void shouldRetryDeleteRpcFailure() {
        ManifestReferenceSweepMark mark = mark(300L);
        givenUnreferencedClaim(mark, 203L);
        when(fileRemoteClient.headObject(STORAGE_PATH, CIPHER_HASH))
                .thenReturn(Result.success(head("etag-a")));
        when(fileRemoteClient.deleteStorageFile(any()))
                .thenReturn(new Result<>(503, "unavailable", null));
        when(markMapper.completeClaim(
                eq(300L), eq(TENANT_ID), eq("claim-a"), eq("FAILED"),
                eq("DELETE_RPC_TRANSIENT"), eq("StorageDeleteUnavailable"), any(Date.class)))
                .thenReturn(1);

        sweepService.sweepTenantBatch();

        verify(markMapper).completeClaim(
                eq(300L), eq(TENANT_ID), eq("claim-a"), eq("FAILED"),
                eq("DELETE_RPC_TRANSIENT"), eq("StorageDeleteUnavailable"), any(Date.class));
    }

    /**
     * Stubs a completed empty census without duplicating its transaction mechanics.
     */
    @SuppressWarnings("unchecked")
    private void executeCensusAsEmpty(ManifestReferenceCensus completed) {
        when(snowflakeIdGenerator.nextId()).thenReturn(completed.getId());
        when(censusMapper.insert(any(ManifestReferenceCensus.class))).thenReturn(1);
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<ManifestReferenceCensus> callback = invocation.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
        when(ledgerMapper.selectCensusRows(completed.getId(), TENANT_ID)).thenReturn(List.of());
        when(censusMapper.selectById(completed.getId())).thenReturn(completed);
    }

    /**
     * Stubs one claimed object with no exact or unknown references.
     */
    private void givenUnreferencedClaim(ManifestReferenceSweepMark mark, Long censusId) {
        when(claimService.claimDue(20, 120L)).thenReturn(List.of(mark));
        executeCensusAsEmpty(completedCensus(censusId));
        when(fileRemoteClient.getDegradedWriteCount()).thenReturn(Result.success(0L));
        when(ledgerMapper.countExactReferences(censusId, TENANT_ID, IDENTITY_DIGEST)).thenReturn(0L);
        when(ledgerMapper.countUnknownHolds(censusId, TENANT_ID)).thenReturn(0L);
    }

    /**
     * Builds a completed census fixture.
     */
    private ManifestReferenceCensus completedCensus(Long id) {
        return new ManifestReferenceCensus()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setStatus("COMPLETED")
                .setKnownReferenceCount(0L)
                .setUnknownHoldCount(0L)
                .setCensusDigest("sha256:census")
                .setDeleted(0);
    }

    /**
     * Builds a claimed mark whose immutable HEAD evidence is known.
     */
    private ManifestReferenceSweepMark mark(Long id) {
        return new ManifestReferenceSweepMark()
                .setId(id)
                .setTenantId(TENANT_ID)
                .setPathTenantId(TENANT_ID)
                .setStoragePath(STORAGE_PATH)
                .setCipherHash(CIPHER_HASH)
                .setContentLength(8L)
                .setEtag("etag-a")
                .setObjectIdentityDigest(IDENTITY_DIGEST)
                .setClaimToken("claim-a")
                .setAttemptCount(1)
                .setDeleted(0);
    }

    /**
     * Builds strict tenant/path/hash/size metadata for one storage object.
     */
    private StorageObjectHeadVO head(String etag) {
        return new StorageObjectHeadVO(true, STORAGE_PATH, CIPHER_HASH, TENANT_ID, TENANT_ID,
                "node-a", 8L, etag, CIPHER_HASH);
    }
}
