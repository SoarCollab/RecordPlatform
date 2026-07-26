package cn.flying.dao.vo.manifest;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Cursor page for bounded manifest backfill item inspection.
 */
@Schema(description = "Manifest backfill item cursor page")
public record ManifestBackfillItemPageVO(
        List<ManifestBackfillItemVO> records,
        String nextCursor
) {
}
