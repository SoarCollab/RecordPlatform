package cn.flying.dao.vo.file;

import java.io.Serial;
import java.io.Serializable;

/**
 * Statistics returned by an integrity check run.
 */
public record IntegrityCheckStatsVO(
        long totalChecked,
        long mismatchesFound,
        long errorsEncountered
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
