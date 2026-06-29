package cn.flying.service.support;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.JsonConverter;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes and decodes ordered storage-object references stored on chain.
 */
public final class StoredObjectReferenceCodec {

    private static final TypeReference<List<Map<String, Object>>> ORDERED_CONTENT_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, String>> LEGACY_MAPPED_CONTENT_TYPE = new TypeReference<>() {
    };

    private StoredObjectReferenceCodec() {
    }

    /**
     * Serializes completed direct-upload parts as an ordered array without deduplicating equal hashes.
     */
    public static String toChainContent(List<DirectMultipartCompletedPartVO> parts) {
        if (parts == null || parts.isEmpty()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "Stored parts cannot be empty");
        }
        List<Map<String, Object>> entries = parts.stream()
                .sorted(Comparator.comparingInt(DirectMultipartCompletedPartVO::partIndex))
                .map(StoredObjectReferenceCodec::toChainContentEntry)
                .toList();
        return JsonConverter.toJsonWithPretty(entries);
    }

    /**
     * Serializes already-normalized object references as ordered chain content.
     */
    public static String toReferenceChainContent(List<StoredObjectReference> references) {
        if (references == null || references.isEmpty()) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "Stored references cannot be empty");
        }
        List<Map<String, Object>> entries = references.stream()
                .sorted(Comparator.comparingInt(StoredObjectReference::index))
                .map(StoredObjectReferenceCodec::toChainContentEntry)
                .toList();
        return JsonConverter.toJsonWithPretty(entries);
    }

    /**
     * Parses chain content into ordered object references for storage download APIs.
     */
    public static List<StoredObjectReference> parseChainContent(String fileContent) {
        if (!StringUtils.hasText(fileContent)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
        }

        char firstJsonChar = firstJsonChar(fileContent);
        if (firstJsonChar == '[') {
            return requireReferences(parseOrderedContent(fileContent));
        }
        if (firstJsonChar == '{') {
            return requireReferences(parseMappedContent(fileContent));
        }
        throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
    }

    /**
     * Builds one JSON object for a completed direct-upload part.
     */
    private static Map<String, Object> toChainContentEntry(DirectMultipartCompletedPartVO part) {
        if (part == null
                || !StringUtils.hasText(part.cipherHash())
                || !StringUtils.hasText(part.storagePath())) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "Stored part metadata cannot be empty");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("index", part.partIndex());
        entry.put("cipherHash", part.cipherHash());
        entry.put("storagePath", part.storagePath());
        entry.put("size", part.size());
        entry.put("plainHash", part.plainHash());
        entry.put("eTag", part.eTag());
        entry.put("checksumAlgorithm", part.checksumAlgorithm());
        return entry;
    }

    /**
     * Builds one JSON object for a normalized stored-object reference.
     */
    private static Map<String, Object> toChainContentEntry(StoredObjectReference reference) {
        if (reference == null
                || !StringUtils.hasText(reference.cipherHash())
                || !StringUtils.hasText(reference.storagePath())) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "Stored reference metadata cannot be empty");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("index", reference.index());
        entry.put("cipherHash", reference.cipherHash());
        entry.put("storagePath", reference.storagePath());
        return entry;
    }

    /**
     * Parses the current ordered-array chain content contract.
     */
    private static List<StoredObjectReference> parseOrderedContent(String fileContent) {
        List<Map<String, Object>> entries = JsonConverter.parse(fileContent, ORDERED_CONTENT_TYPE);
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<StoredObjectReference> references = new ArrayList<>(entries.size());
        for (Map<String, Object> entry : entries) {
            if (entry == null) {
                throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
            }
            references.add(toStoredObjectReference(entry));
        }
        return references.stream()
                .sorted(Comparator.comparingInt(StoredObjectReference::index))
                .toList();
    }

    /**
     * Parses the historical map content still produced by non-direct uploads.
     */
    private static List<StoredObjectReference> parseMappedContent(String fileContent) {
        Map<String, String> mapped = JsonConverter.parse(fileContent, LEGACY_MAPPED_CONTENT_TYPE);
        if (mapped == null || mapped.isEmpty()) {
            return List.of();
        }
        List<StoredObjectReference> references = new ArrayList<>(mapped.size());
        int index = 0;
        for (Map.Entry<String, String> entry : mapped.entrySet()) {
            if (!StringUtils.hasText(entry.getKey()) || !StringUtils.hasText(entry.getValue())) {
                throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
            }
            references.add(new StoredObjectReference(index++, entry.getKey(), entry.getValue()));
        }
        return List.copyOf(references);
    }

    /**
     * Rejects parsed content that does not contain any storage references.
     */
    private static List<StoredObjectReference> requireReferences(List<StoredObjectReference> references) {
        if (references == null || references.isEmpty()) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
        }
        return references;
    }

    /**
     * Converts one ordered-array JSON entry into a typed reference.
     */
    private static StoredObjectReference toStoredObjectReference(Map<String, Object> entry) {
        Integer index = readIndex(entry.get("index"));
        String cipherHash = readText(entry.get("cipherHash"));
        String storagePath = readText(entry.get("storagePath"));
        if (index == null || !StringUtils.hasText(cipherHash) || !StringUtils.hasText(storagePath)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
        }
        return new StoredObjectReference(index, cipherHash, storagePath);
    }

    /**
     * Reads a numeric JSON index while rejecting missing or malformed values.
     */
    private static Integer readIndex(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && StringUtils.hasText(text)) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Reads a JSON scalar as trimmed text.
     */
    private static String readText(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    /**
     * Returns the first non-whitespace character so parsing can choose the correct JSON shape.
     */
    private static char firstJsonChar(String fileContent) {
        for (int i = 0; i < fileContent.length(); i++) {
            char value = fileContent.charAt(i);
            if (!Character.isWhitespace(value)) {
                return value;
            }
        }
        return '\0';
    }
}
