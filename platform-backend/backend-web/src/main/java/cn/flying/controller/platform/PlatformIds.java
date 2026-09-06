package cn.flying.controller.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;

/** Typed external identifier decoding at the platform HTTP boundary. */
final class PlatformIds {

    static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    /** Prevents instances of stateless HTTP identifier decoding. */
    private PlatformIds() {
    }

    /** Decodes an entity-typed tenant target, including the legacy system tenant zero. */
    static Long tenant(String value) {
        return decode(value, 'E', true);
    }

    /** Decodes a user identifier without accepting a numerically equivalent entity identifier. */
    static Long user(String value) {
        return decode(value, 'U', false);
    }

    /** Decodes a positive entity identifier for invitations and platform operations. */
    static Long entity(String value) {
        return decode(value, 'E', false);
    }

    /** Applies type and numeric domain checks before any service receives an internal identifier. */
    private static Long decode(String value, char type, boolean allowZero) {
        if (value == null || value.isEmpty() || value.charAt(0) != type) {
            throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        }
        Long id = IdUtils.fromExternalId(value);
        if (id == null || id < 0 || !allowZero && id == 0L) {
            throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        }
        return id;
    }
}
