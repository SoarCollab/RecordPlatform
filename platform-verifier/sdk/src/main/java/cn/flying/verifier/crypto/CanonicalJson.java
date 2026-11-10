package cn.flying.verifier.crypto;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.Map;

/**
 * Strict bounded JSON parser and deterministic serializer for signed proof evidence.
 */
public final class CanonicalJson {

    public static final int MAX_DOCUMENT_BYTES = 1024 * 1024;
    public static final int MAX_NESTING_DEPTH = 64;
    public static final int MAX_STRING_LENGTH = 256 * 1024;
    public static final int MAX_NUMBER_LENGTH = 128;

    private final JsonMapper mapper;

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
                .changeDefaultPropertyInclusion(inclusion ->
                        inclusion.withValueInclusion(JsonInclude.Include.NON_NULL))
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
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
            // Jackson 3 不再按 MapperFeature.SORT_PROPERTIES_ALPHABETICALLY 排序对象属性
            //（该特性对 record/POJO 已不生效），因此先转成树、按 key 递归排序后再序列化，
            // 保证 canonical 字节与历史签发结果一致。
            return mapper.writeValueAsBytes(sortByPropertyName(mapper.valueToTree(value)));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Unable to serialize canonical proof JSON", e);
        }
    }

    /**
     * Recursively rebuilds a JSON tree so every object lists its properties in code-point order.
     *
     * @param node source node
     * @return node whose object properties are sorted by name
     */
    private static JsonNode sortByPropertyName(JsonNode node) {
        if (node == null || node.isNull()) {
            return node;
        }
        if (node.isArray()) {
            ArrayNode sorted = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> sorted.add(sortByPropertyName(child)));
            return sorted;
        }
        if (node.isObject()) {
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            node.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> sorted.set(entry.getKey(), sortByPropertyName(entry.getValue())));
            return sorted;
        }
        return node;
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
        } catch (RuntimeException e) {
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
    public JsonMapper mapperCopy() {
        return mapper.rebuild().build();
    }
}
