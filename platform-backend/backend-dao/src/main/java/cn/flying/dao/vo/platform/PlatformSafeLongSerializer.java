package cn.flying.dao.vo.platform;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/** Emits platform business numbers without changing the legacy string serialization of Long identifiers. */
public final class PlatformSafeLongSerializer extends JsonSerializer<Long> {

    public static final long MAX_SAFE_INTEGER = 9007199254740991L;

    /** Tests the exact nonnegative integer range representable by a JavaScript number. */
    public static boolean isSafe(Long value) {
        return value != null && value >= 0 && value <= MAX_SAFE_INTEGER;
    }

    /** Rejects invalid or lossy values instead of sending a silently rounded platform number. */
    @Override
    public void serialize(Long value, JsonGenerator generator, SerializerProvider serializers) throws IOException {
        if (!isSafe(value)) {
            throw new IOException("Platform numeric value is outside the supported integer range");
        }
        generator.writeNumber(value.longValue());
    }
}
