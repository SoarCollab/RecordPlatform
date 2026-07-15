package cn.flying.verifier.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Arrays;

/**
 * Strict bounded JSON parser and deterministic serializer for signed proof evidence.
 */
public final class CanonicalJson {

    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_STRING_LENGTH = 256 * 1024;
    public static final int MAX_NUMBER_LENGTH = 128;

    private final ObjectMapper mapper;

    /**
     * Creates a canonical mapper with duplicate, depth, length, unknown-field, and trailing-token checks.
     */
    public CanonicalJson() {
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxDocumentLength(MAX_DOCUMENT_BYTES)
                        .maxNestingDepth(MAX_NESTING_DEPTH)
                        .maxStringLength(MAX_STRING_LENGTH)
                        .maxNumberLength(MAX_NUMBER_LENGTH)
                        .build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
        this.mapper = JsonMapper.builder(factory)
                .defaultPropertyInclusion(JsonInclude.Value.construct(
                        JsonInclude.Include.NON_NULL,
                        JsonInclude.Include.NON_NULL))
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
    }

    /**
     * Serializes a trusted model into stable UTF-8 canonical bytes.
     *
     * @param value trusted model
     * @return canonical JSON bytes
     */
    public byte[] canonicalBytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Unable to serialize canonical proof JSON", e);
        }
    }

    /**
     * Strictly parses one bounded JSON document into the requested record type.
     *
     * @param bytes JSON bytes
     * @param type target type
     * @param <T> target type
     * @return parsed value
     */
    public <T> T read(byte[] bytes, Class<T> type) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Proof JSON document size is invalid");
        }
        try {
            return mapper.readValue(bytes, type);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("Unable to parse strict proof JSON", e);
        }
    }

    /**
     * Verifies that a parsed model serializes to the exact supplied canonical bytes.
     *
     * @param original original JSON bytes
     * @param value parsed model
     * @return true only when the byte representation is canonical
     */
    public boolean isCanonical(byte[] original, Object value) {
        return original != null && Arrays.equals(original, canonicalBytes(value));
    }

    /**
     * Exposes a defensive copy for report serialization without relaxing the strict proof parser.
     *
     * @return independent mapper copy
     */
    public ObjectMapper mapperCopy() {
        return mapper.copy();
    }
}
