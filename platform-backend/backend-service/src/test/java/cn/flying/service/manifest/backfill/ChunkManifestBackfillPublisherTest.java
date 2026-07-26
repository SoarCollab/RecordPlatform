package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.service.manifest.BackfilledManifestPublication;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ChunkManifestBackfillPublisherTest {

    private static final Long TENANT_ID = 11L;
    private static final Long FILE_ID = 22L;
    private static final Long RUN_ID = 66L;
    private static final String CLAIM_TOKEN = "claim-token";

    @Mock
    private ManifestBackfillItemMapper itemMapper;

    @Mock
    private FileMapper fileMapper;

    @Mock
    private ManifestEvidenceResolver evidenceResolver;

    @Mock
    private ChunkManifestService chunkManifestService;

    @InjectMocks
    private ChunkManifestBackfillPublisher publisher;

    private ManifestBackfillItem item;
    private File file;
    private ChunkManifestDraft draft;
    private ChunkManifestView view;

    /**
     * Builds one valid tenant-scoped claim and manifest evidence fixture.
     */
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(TENANT_ID);
        item = new ManifestBackfillItem()
                .setId(33L)
                .setRunId(RUN_ID)
                .setTenantId(TENANT_ID)
                .setFileId(FILE_ID)
                .setFileVersion(2)
                .setOwnerUserId(44L)
                .setClassification(ManifestBackfillClassification.BACKFILLABLE.name())
                .setEvidencePayload("payload")
                .setEvidenceDigest("sha256:evidence")
                .setLeaseExpiresAt(Date.from(Instant.now().plusSeconds(60)));
        file = new File()
                .setId(FILE_ID)
                .setTenantId(TENANT_ID)
                .setUid(44L)
                .setVersion(2)
                .setStatus(FileUploadStatus.SUCCESS.getCode())
                .setDeleted(0);
        ChunkManifestChunk chunk = new ChunkManifestChunk(
                0, "sha256:plain", "sha256:cipher", 8L,
                "storage/tenant/11/chunk/sha256:cipher", "S3", "etag", "SHA-256");
        draft = new ChunkManifestDraft(
                "cn.flying.chunk-manifest.v1", "sha256:file", "SHA-256", 8L, 8L,
                null, "NONE", "S3", List.of(chunk));
        view = new ChunkManifestView(
                55L, FILE_ID, 2, draft.schemaId(), draft.fileHash(), "sha256:manifest",
                draft.hashAlgorithm(), draft.chunkSize(), 1, draft.totalSize(), null,
                draft.encryptionAlgorithm(), draft.storageBackend(), draft.chunks());

        when(itemMapper.selectClaimedForUpdate(TENANT_ID, RUN_ID, 33L, CLAIM_TOKEN)).thenReturn(item);
        lenient().when(fileMapper.selectByIdForManifestBackfillUpdate(TENANT_ID, FILE_ID)).thenReturn(file);
        lenient().when(evidenceResolver.revalidate(file, "payload", "sha256:evidence"))
                .thenReturn(new ManifestBackfillEvidenceSnapshot(
                        TENANT_ID, FILE_ID, 2, 44L, "chain-record", "sha256:file",
                        "sha256:manifest", draft));
    }

    /**
     * Clears the tenant context after every publication test.
     */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /**
     * Verifies a created manifest and item completion share one publication result.
     */
    @Test
    void shouldPublishInsertOnlyBackfillAndCompleteClaim() {
        when(chunkManifestService.createBackfilledManifestIfAbsent(44L, FILE_ID, draft))
                .thenReturn(new BackfilledManifestPublication(view, true));
        when(itemMapper.completeClaim(
                eq(TENANT_ID), eq(RUN_ID), eq(33L), eq(CLAIM_TOKEN),
                eq("BACKFILLED"), eq("BACKFILLABLE"),
                eq("BACKFILLABLE_EVIDENCE"), eq(55L), eq(0), eq(null), eq(null)))
                .thenReturn(1);

        BackfilledManifestPublication result = publisher.publish(TENANT_ID, RUN_ID, 33L, CLAIM_TOKEN);

        assertThat(result.created()).isTrue();
        verify(chunkManifestService).createBackfilledManifestIfAbsent(44L, FILE_ID, draft);
    }

    /**
     * Verifies a concurrent equal active manifest is observed rather than superseded.
     */
    @Test
    void shouldTreatEqualConcurrentManifestAsIgnoredWinner() {
        when(chunkManifestService.createBackfilledManifestIfAbsent(44L, FILE_ID, draft))
                .thenReturn(new BackfilledManifestPublication(view, false));
        when(itemMapper.completeClaim(
                eq(TENANT_ID), eq(RUN_ID), eq(33L), eq(CLAIM_TOKEN),
                eq("IGNORED"), eq("ALREADY_MANIFEST"),
                eq("ALREADY_MANIFEST"), eq(55L), eq(0), eq(null), eq(null)))
                .thenReturn(1);

        BackfilledManifestPublication result = publisher.publish(TENANT_ID, RUN_ID, 33L, CLAIM_TOKEN);

        assertThat(result.created()).isFalse();
    }

    /**
     * Verifies an expired lease cannot reach evidence or manifest publication.
     */
    @Test
    void shouldRejectExpiredClaimBeforePublication() {
        item.setLeaseExpiresAt(Date.from(Instant.now().minusSeconds(1)));

        assertThatThrownBy(() -> publisher.publish(TENANT_ID, RUN_ID, 33L, CLAIM_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(ManifestBackfillReason.CLAIM_LOST.name());
    }
}
