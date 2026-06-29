package cn.flying.service.manifest;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Normalizes chunk manifest payloads and calculates deterministic manifest hashes.
 */
@Service
public class ChunkManifestCanonicalizer {

    public static final String SCHEMA_ID = "cn.flying.chunk-manifest.v1";
    public static final String HASH_ALGORITHM = "SHA-256";
    public static final String MANIFEST_HASH_PREFIX = "sha256:";
    private static final String DEFAULT_STORAGE_BACKEND = "S3";

    private static final ObjectMapper CANONICAL_OBJECT_MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    /**
     * Returns a validated manifest draft with defaults applied and chunks sorted by index.
     *
     * @param draft untrusted draft from a backend caller
     * @return normalized draft ready for hashing or persistence
     */
    public ChunkManifestDraft normalize(ChunkManifestDraft draft) {
        if (draft == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk manifest draft is required");
        }
        if (!StringUtils.hasText(draft.fileHash())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "fileHash is required");
        }
        if (draft.chunkSize() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunkSize must be positive");
        }
        if (draft.totalSize() <= 0) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "totalSize must be positive");
        }
        if (CollectionUtils.isEmpty(draft.chunks())) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunks cannot be empty");
        }

        List<ChunkManifestChunk> chunks = normalizeChunks(draft);
        long totalChunkSize = chunks.stream()
                .mapToLong(ChunkManifestChunk::size)
                .reduce(0L, Math::addExact);
        if (totalChunkSize != draft.totalSize()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "totalSize must equal the sum of chunk sizes");
        }

        return new ChunkManifestDraft(
                trimOrDefault(draft.schemaId(), SCHEMA_ID),
                draft.fileHash().trim(),
                trimOrDefault(draft.hashAlgorithm(), HASH_ALGORITHM),
                draft.chunkSize(),
                draft.totalSize(),
                trimToNull(draft.merkleRoot()),
                trimToNull(draft.encryptionAlgorithm()),
                trimOrDefault(draft.storageBackend(), DEFAULT_STORAGE_BACKEND),
                chunks
        );
    }

    /**
     * Serializes a normalized manifest into canonical JSON without the manifest hash field.
     *
     * @param draft manifest draft to canonicalize
     * @return deterministic JSON payload
     */
    public String canonicalJson(ChunkManifestDraft draft) {
        ChunkManifestDraft normalized = normalize(draft);
        try {
            return CANONICAL_OBJECT_MAPPER.writeValueAsString(toCanonicalPayload(normalized));
        } catch (JsonProcessingException e) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "chunk manifest canonical JSON serialization failed");
        }
    }

    /**
     * Calculates the canonical manifest hash as sha256:lowercase-hex.
     *
     * @param draft manifest draft to hash
     * @return deterministic manifest hash
     */
    public String manifestHash(ChunkManifestDraft draft) {
        return MANIFEST_HASH_PREFIX + sha256Hex(canonicalJson(draft));
    }

    /**
     * Validates chunk-level fields, applies chunk defaults, and returns chunks ordered by index.
     */
    private List<ChunkManifestChunk> normalizeChunks(ChunkManifestDraft draft) {
        List<ChunkManifestChunk> chunks = new ArrayList<>(draft.chunks().size());
        for (ChunkManifestChunk chunk : draft.chunks()) {
            if (chunk == null) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk entry cannot be null");
            }
            if (chunk.index() < 0) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk index must be non-negative");
            }
            if (chunk.size() <= 0) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk size must be positive");
            }
            if (!StringUtils.hasText(chunk.plainHash())) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk plainHash is required");
            }
            if (!StringUtils.hasText(chunk.cipherHash())) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk cipherHash is required");
            }
            if (!StringUtils.hasText(chunk.storagePath())) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk storagePath is required");
            }
            chunks.add(new ChunkManifestChunk(
                    chunk.index(),
                    chunk.plainHash().trim(),
                    chunk.cipherHash().trim(),
                    chunk.size(),
                    chunk.storagePath().trim(),
                    trimOrDefault(chunk.storageBackend(), trimOrDefault(draft.storageBackend(), DEFAULT_STORAGE_BACKEND)),
                    trimToNull(chunk.etag()),
                    trimOrDefault(chunk.checksumAlgorithm(), HASH_ALGORITHM)
            ));
        }

        chunks.sort(Comparator.comparingInt(ChunkManifestChunk::index));
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).index() != i) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk indexes must be contiguous from 0");
            }
        }
        return List.copyOf(chunks);
    }

    /**
     * Builds a sorted map representation of the manifest payload used for canonical JSON.
     */
    private Map<String, Object> toCanonicalPayload(ChunkManifestDraft draft) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("schema", draft.schemaId());
        payload.put("fileHash", draft.fileHash());
        payload.put("hashAlgorithm", draft.hashAlgorithm());
        payload.put("chunkSize", draft.chunkSize());
        payload.put("totalSize", draft.totalSize());
        payload.put("merkleRoot", draft.merkleRoot());
        payload.put("encryptionAlgorithm", draft.encryptionAlgorithm());
        payload.put("storageBackend", draft.storageBackend());
        payload.put("chunks", draft.chunks().stream()
                .map(this::toCanonicalChunkPayload)
                .toList());
        return payload;
    }

    /**
     * Builds a sorted map representation of one chunk entry.
     */
    private Map<String, Object> toCanonicalChunkPayload(ChunkManifestChunk chunk) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("index", chunk.index());
        payload.put("plainHash", chunk.plainHash());
        payload.put("cipherHash", chunk.cipherHash());
        payload.put("size", chunk.size());
        payload.put("storagePath", chunk.storagePath());
        payload.put("storageBackend", chunk.storageBackend());
        payload.put("etag", chunk.etag());
        payload.put("checksumAlgorithm", chunk.checksumAlgorithm());
        return payload;
    }

    /**
     * Calculates lowercase SHA-256 hex for a UTF-8 string.
     */
    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    /**
     * Trims a string value or returns a default when blank.
     */
    private String trimOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    /**
     * Trims a string value or returns null when blank.
     */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
