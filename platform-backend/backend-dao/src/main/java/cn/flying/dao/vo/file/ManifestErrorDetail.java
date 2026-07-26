package cn.flying.dao.vo.file;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Machine-readable manifest lifecycle detail shared by download, integrity, and proof errors.
 */
@Schema(description = "Machine-readable manifest lifecycle detail")
public record ManifestErrorDetail(
        String manifestStatus,
        String manifestClassification,
        String manifestErrorCode,
        boolean legacyDownloadAllowed
) {
}
