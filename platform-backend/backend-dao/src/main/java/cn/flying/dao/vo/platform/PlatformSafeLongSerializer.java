package cn.flying.dao.vo.platform;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.SerializationContext;

/** Emits platform business numbers without changing the legacy string serialization of Long identifiers. */
public final class PlatformSafeLongSerializer extends ValueSerializer<Long> {

    public static final long MAX_SAFE_INTEGER = 9007199254740991L;

    /** Tests the exact nonnegative integer range representable by a JavaScript number. */
    public static boolean isSafe(Long value) {
        return value != null && value >= 0 && value <= MAX_SAFE_INTEGER;
    }

    /** Rejects invalid or lossy values instead of sending a silently rounded platform number. */
    @Override
    public void serialize(Long value, JsonGenerator generator, SerializationContext serializers) {
        if (!isSafe(value)) {
            throw new IllegalArgumentException("Platform numeric value is outside the supported integer range");
        }
        generator.writeNumber(value.longValue());
    }
}
