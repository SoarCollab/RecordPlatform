package cn.flying.service.manifest.backfill;

import cn.flying.dao.dto.File;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.dao.vo.file.ManifestErrorDetail;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves one stable machine-readable manifest state across public product contracts.
 */
@Service
@RequiredArgsConstructor
public class ManifestGovernanceStatusService {

    private final ManifestBackfillItemMapper itemMapper;

    /**
     * Returns the latest exact-version status for a missing active manifest.
     */
    public ManifestErrorDetail missingManifest(File file) {
        if (file == null || file.getTenantId() == null || file.getId() == null) {
            return unclassifiedMissing();
        }
        ManifestBackfillItem item = itemMapper.selectLatestForFile(
                file.getTenantId(), file.getId(), file.getVersion() == null ? 0 : file.getVersion());
        return fromItem(item);
    }

    /**
     * Returns the latest status for an integrity alert that does not carry file version.
     */
    public ManifestErrorDetail missingManifest(Long tenantId, Long fileId) {
        if (tenantId == null || fileId == null) {
            return unclassifiedMissing();
        }
        return fromItem(itemMapper.selectLatestForFileAnyVersion(tenantId, fileId));
    }

    /**
     * Returns the successful active-manifest contract fields.
     */
    public ManifestErrorDetail activeManifest() {
        return new ManifestErrorDetail("ACTIVE", "ALREADY_MANIFEST", null, false);
    }

    /**
     * Converts a durable item while denying compatibility unless scan evidence explicitly allowed it.
     */
    private ManifestErrorDetail fromItem(ManifestBackfillItem item) {
        if (item == null) {
            return unclassifiedMissing();
        }
        return new ManifestErrorDetail(
                item.getStatus(),
                item.getClassification(),
                item.getReasonCode(),
                Integer.valueOf(1).equals(item.getLegacyDownloadAllowed()));
    }

    /**
     * Fails closed for files that have not entered a completed governance scan.
     */
    private ManifestErrorDetail unclassifiedMissing() {
        return new ManifestErrorDetail(
                "REUPLOAD_REQUIRED", "UNCLASSIFIED", "MISSING_MANIFEST_UNCLASSIFIED", false);
    }
}
