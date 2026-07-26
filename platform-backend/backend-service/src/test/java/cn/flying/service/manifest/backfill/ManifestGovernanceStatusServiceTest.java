package cn.flying.service.manifest.backfill;

import cn.flying.dao.dto.File;
import cn.flying.dao.entity.ManifestBackfillItem;
import cn.flying.dao.mapper.ManifestBackfillItemMapper;
import cn.flying.dao.vo.file.ManifestErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ManifestGovernanceStatusServiceTest {

    @Mock
    private ManifestBackfillItemMapper itemMapper;

    @InjectMocks
    private ManifestGovernanceStatusService service;

    /**
     * Verifies compatibility remains denied when no completed governance evidence exists.
     */
    @Test
    void shouldFailClosedForUnclassifiedMissingManifest() {
        File file = new File().setId(10L).setTenantId(20L).setVersion(3);
        when(itemMapper.selectLatestForFile(20L, 10L, 3)).thenReturn(null);

        ManifestErrorDetail detail = service.missingManifest(file);

        assertThat(detail.manifestStatus()).isEqualTo("REUPLOAD_REQUIRED");
        assertThat(detail.manifestClassification()).isEqualTo("UNCLASSIFIED");
        assertThat(detail.manifestErrorCode()).isEqualTo("MISSING_MANIFEST_UNCLASSIFIED");
        assertThat(detail.legacyDownloadAllowed()).isFalse();
    }

    /**
     * Verifies the typed compatibility bit is copied only from a durable scan item.
     */
    @Test
    void shouldExposeExplicitLegacyCompatibilityEvidence() {
        File file = new File().setId(10L).setTenantId(20L).setVersion(3);
        when(itemMapper.selectLatestForFile(20L, 10L, 3)).thenReturn(new ManifestBackfillItem()
                .setStatus("REUPLOAD_REQUIRED")
                .setClassification("REUPLOAD_REQUIRED")
                .setReasonCode("LEGACY_DOWNLOAD_ALLOWED")
                .setLegacyDownloadAllowed(1));

        ManifestErrorDetail detail = service.missingManifest(file);

        assertThat(detail.manifestErrorCode()).isEqualTo("LEGACY_DOWNLOAD_ALLOWED");
        assertThat(detail.legacyDownloadAllowed()).isTrue();
    }

    /**
     * 验证空身份与不带版本的完整性告警都使用稳定的治理状态映射。
     */
    @Test
    void shouldHandleNullIdentityAndVersionlessAlertLookup() {
        ManifestErrorDetail unclassified = new ManifestErrorDetail(
                "REUPLOAD_REQUIRED", "UNCLASSIFIED", "MISSING_MANIFEST_UNCLASSIFIED", false);
        ManifestBackfillItem item = new ManifestBackfillItem()
                .setStatus("FAILED")
                .setClassification("FAILED")
                .setReasonCode("OBJECT_NOT_FOUND")
                .setLegacyDownloadAllowed(0);
        when(itemMapper.selectLatestForFileAnyVersion(20L, 10L)).thenReturn(item);

        assertThat(service.missingManifest((File) null)).isEqualTo(unclassified);
        assertThat(service.missingManifest(null, 10L)).isEqualTo(unclassified);
        assertThat(service.missingManifest(20L, 10L)).isEqualTo(
                new ManifestErrorDetail("FAILED", "FAILED", "OBJECT_NOT_FOUND", false));
    }

    /**
     * Verifies successful products share the same active-manifest vocabulary.
     */
    @Test
    void shouldReturnStableActiveManifestStatus() {
        assertThat(service.activeManifest()).isEqualTo(
                new ManifestErrorDetail("ACTIVE", "ALREADY_MANIFEST", null, false));
    }
}
