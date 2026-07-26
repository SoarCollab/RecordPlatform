package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Supported manifest governance execution modes.
 */
public enum ManifestBackfillMode {
    SCAN,
    DRY_RUN,
    APPLY;

    /**
     * Parses an API mode without accepting unknown lifecycle operations.
     *
     * @param value API value
     * @return supported mode
     */
    public static ManifestBackfillMode parse(String value) {
        if (!StringUtils.hasText(value)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "manifest backfill mode is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalidMode) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    "manifest backfill mode must be SCAN, DRY_RUN, or APPLY");
        }
    }
}
