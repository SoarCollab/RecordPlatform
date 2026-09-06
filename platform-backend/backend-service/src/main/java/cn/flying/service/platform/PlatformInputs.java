package cn.flying.service.platform;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.dao.vo.platform.PlatformSafeLongSerializer;

import java.util.Objects;
import java.util.Set;

/** Shared validation for independently callable platform query and command boundaries. */
final class PlatformInputs {

    /** Prevents instances of stateless boundary checks. */
    private PlatformInputs() {
    }

    /** Calculates a bounded page offset without allowing overflow or unbounded page sizes. */
    static long offset(long pageNum, long pageSize) {
        if (pageNum < 1 || pageNum > PlatformSafeLongSerializer.MAX_SAFE_INTEGER || pageSize < 1 || pageSize > 100) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        try {
            return Math.multiplyExact(pageNum - 1, pageSize);
        } catch (ArithmeticException exception) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
    }

    /** Normalizes bounded search text before it reaches bound SQL parameters. */
    static String keyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        if (keyword.length() > 100) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        return keyword.isBlank() ? null : keyword.trim();
    }

    /** Rejects filters that could expose platform accounts through a tenant-user query. */
    static void memberFilters(String role, Integer status) {
        if (role != null && !Set.of("user", "admin", "monitor").contains(role)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
        status(status);
    }

    /** Accepts only the two persisted lifecycle values. */
    static void status(Integer status) {
        if (status != null && status != 0 && status != 1) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID);
        }
    }

    /** Validates an explicit tenant target while preserving tenant zero compatibility. */
    static void tenantId(Long tenantId) {
        if (tenantId == null || tenantId < 0) {
            throw new GeneralException(ResultEnum.PLATFORM_TARGET_NOT_FOUND);
        }
    }

    /** Checks a current resource version and prevents an overflowing version increment. */
    static void version(Long current, Long expected) {
        if (!PlatformSafeLongSerializer.isSafe(current) || !PlatformSafeLongSerializer.isSafe(expected)
                || current == PlatformSafeLongSerializer.MAX_SAFE_INTEGER || !Objects.equals(current, expected)) {
            throw new GeneralException(ResultEnum.PLATFORM_VERSION_CONFLICT);
        }
    }

    /** Treats missing or corrupt measurements as unavailable rather than inventing zero. */
    static Long measured(Long value) {
        if (!PlatformSafeLongSerializer.isSafe(value)) {
            throw new GeneralException(ResultEnum.SERVICE_UNAVAILABLE);
        }
        return value;
    }
}
