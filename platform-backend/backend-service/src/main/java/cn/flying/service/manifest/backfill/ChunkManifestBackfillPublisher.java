package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.service.manifest.BackfilledManifestPublication;
import cn.flying.service.manifest.ChunkManifestService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Atomically revalidates evidence, insert-publishes one manifest, and completes its claimed item.
 */
@Service
@RequiredArgsConstructor
public class ChunkManifestBackfillPublisher {

    private final ManifestBackfillItemMapper itemMapper;
    private final FileMapper fileMapper;
    private final ManifestEvidenceResolver evidenceResolver;
    private final ChunkManifestService chunkManifestService;

    /**
     * Publishes a claimed item without ever invoking replacement-style manifest save semantics.
     *
     * @param tenantId tenant boundary carried by the claim
     * @param runId claimed run ID
     * @param itemId claimed item ID
     * @param claimToken ownership token
     * @return publication result
     */
    @Transactional(rollbackFor = Exception.class)
    public BackfilledManifestPublication publish(
            Long tenantId,
            Long runId,
            Long itemId,
            String claimToken
    ) {
        if (!Objects.equals(TenantContext.requireTenantId(), tenantId)) {
            throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
        }
        ManifestBackfillItem item = itemMapper.selectClaimedForUpdate(
                tenantId, runId, itemId, claimToken);
        if (item == null || item.getLeaseExpiresAt() == null
                || !item.getLeaseExpiresAt().toInstant().isAfter(Instant.now())) {
            throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
        }
        if (!Objects.equals(tenantId, item.getTenantId())
                || !Objects.equals(runId, item.getRunId())
                || !ManifestBackfillClassification.BACKFILLABLE.name().equals(item.getClassification())) {
            throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
        }

        File file = fileMapper.selectByIdForManifestBackfillUpdate(tenantId, item.getFileId());
        if (file == null
                || !Objects.equals(file.getVersion(), item.getFileVersion())
                || !Objects.equals(file.getStatus(), FileUploadStatus.SUCCESS.getCode())
                || Integer.valueOf(1).equals(file.getDeleted())) {
            throw new ManifestEvidenceResolver.ManifestEvidenceChangedException(
                    ManifestBackfillReason.EVIDENCE_DIGEST_CHANGED);
        }
        ManifestBackfillEvidenceSnapshot snapshot = evidenceResolver.revalidate(
                file, item.getEvidencePayload(), item.getEvidenceDigest());
        BackfilledManifestPublication publication = chunkManifestService.createBackfilledManifestIfAbsent(
                item.getOwnerUserId(), item.getFileId(), snapshot.manifestDraft());

        String itemStatus = publication.created()
                ? ManifestBackfillItemStatus.BACKFILLED.name()
                : ManifestBackfillItemStatus.IGNORED.name();
        String classification = publication.created()
                ? ManifestBackfillClassification.BACKFILLABLE.name()
                : ManifestBackfillClassification.ALREADY_MANIFEST.name();
        String reason = publication.created()
                ? ManifestBackfillReason.BACKFILLABLE_EVIDENCE.name()
                : ManifestBackfillReason.ALREADY_MANIFEST.name();
        int updated = itemMapper.completeClaim(
                tenantId, runId, item.getId(), claimToken, itemStatus, classification, reason,
                publication.manifest().manifestId(), 0, null, null);
        if (updated != 1) {
            throw new IllegalStateException(ManifestBackfillReason.CLAIM_LOST.name());
        }
        return publication;
    }
}
