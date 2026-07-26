package cn.flying.service.manifest;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.verifier.contract.SignedProofBundleContract;
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
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Normalizes chunk manifest payloads and calculates deterministic manifest hashes.
 */
@Service
public class ChunkManifestCanonicalizer {

    public static final String SCHEMA_ID = SignedProofBundleContract.SOURCE_CHUNK_MANIFEST_SCHEMA;
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

        ChunkManifestEncryption encryption = normalizeEncryption(draft.encryption());
        List<ChunkManifestChunk> chunks = normalizeChunks(draft, encryption);
        boolean framedV2 = encryption != null
                && encryption.formatVersion() == ChunkManifestEncryption.FORMAT_FRAMED_V2;
        long totalChunkSize = chunks.stream()
                .mapToLong(chunk -> framedV2 ? chunk.plainSize() : chunk.size())
                .reduce(0L, Math::addExact);
        if (totalChunkSize != draft.totalSize()) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                    framedV2
                            ? "v2 totalSize must equal the sum of chunk plain sizes"
                            : "totalSize must equal the sum of chunk sizes");
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
                encryption,
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
     * 序列化并校验加密描述，作为数据库 encryption_metadata 的稳定副本。
     */
    public String encryptionJson(ChunkManifestEncryption encryption) {
        ChunkManifestEncryption normalized = normalizeEncryption(encryption);
        if (normalized == null) {
            return null;
        }
        try {
            return CANONICAL_OBJECT_MAPPER.writeValueAsString(toCanonicalEncryptionPayload(normalized));
        } catch (JsonProcessingException e) {
            throw new GeneralException(ResultEnum.JSON_PARSE_ERROR, "加密描述 JSON 序列化失败");
        }
    }

    /**
     * 解析数据库中的加密描述，并复用同一套 allowlist 校验。
     */
    public ChunkManifestEncryption parseEncryptionJson(String encryptionJson) {
        if (!StringUtils.hasText(encryptionJson)) {
            return null;
        }
        try {
            ChunkManifestEncryption parsed = CANONICAL_OBJECT_MAPPER.readValue(
                    encryptionJson, ChunkManifestEncryption.class);
            return normalizeEncryption(parsed);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "manifest 加密描述格式无效");
        }
    }

    /**
     * Validates chunk-level fields, applies chunk defaults, and returns chunks ordered by index.
     */
    private List<ChunkManifestChunk> normalizeChunks(
            ChunkManifestDraft draft,
            ChunkManifestEncryption encryption
    ) {
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
            Long plainSize = chunk.plainSize();
            Integer frameCount = chunk.frameCount();
            if (plainSize != null && plainSize <= 0) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk plainSize must be positive");
            }
            if (frameCount != null && frameCount <= 0) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "chunk frameCount must be positive");
            }
            if (encryption != null && encryption.formatVersion() == ChunkManifestEncryption.FORMAT_FRAMED_V2) {
                if (plainSize == null || frameCount == null) {
                    throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                            "v2 chunk plainSize and frameCount are required");
                }
                long expectedFrameCount = (plainSize + encryption.framePlainSize() - 1L)
                        / encryption.framePlainSize();
                long expectedCipherSize = 44L + plainSize
                        + expectedFrameCount * (12L + encryption.tagSize());
                if (frameCount.longValue() != expectedFrameCount || chunk.size() != expectedCipherSize) {
                    throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                            "v2 chunk plain/cipher size or frame count is inconsistent");
                }
            }
            chunks.add(new ChunkManifestChunk(
                    chunk.index(),
                    chunk.plainHash().trim(),
                    chunk.cipherHash().trim(),
                    chunk.size(),
                    chunk.storagePath().trim(),
                    trimOrDefault(chunk.storageBackend(), trimOrDefault(draft.storageBackend(), DEFAULT_STORAGE_BACKEND)),
                    trimToNull(chunk.etag()),
                    trimOrDefault(chunk.checksumAlgorithm(), HASH_ALGORITHM),
                    plainSize,
                    frameCount
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
        payload.put("encryption", draft.encryption() == null
                ? null : toCanonicalEncryptionPayload(draft.encryption()));
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
        payload.put("plainSize", chunk.plainSize());
        payload.put("frameCount", chunk.frameCount());
        return payload;
    }

    /**
     * Builds the sorted encryption descriptor map used by canonical JSON and persistence.
     */
    private Map<String, Object> toCanonicalEncryptionPayload(ChunkManifestEncryption encryption) {
        Map<String, Object> payload = new TreeMap<>();
        payload.put("formatVersion", encryption.formatVersion());
        payload.put("algorithmSuite", encryption.algorithmSuite());
        payload.put("fileNonce", encryption.fileNonce());
        payload.put("framePlainSize", encryption.framePlainSize());
        payload.put("keyDerivation", encryption.keyDerivation());
        payload.put("nonceDerivation", encryption.nonceDerivation());
        payload.put("aadSchema", encryption.aadSchema());
        payload.put("tagSize", encryption.tagSize());
        return payload;
    }

    /**
     * 校验并规范化 encryption descriptor，拒绝未知格式、套件和危险长度。
     */
    private ChunkManifestEncryption normalizeEncryption(ChunkManifestEncryption encryption) {
        if (encryption == null) {
            return null;
        }
        Integer formatVersion = encryption.formatVersion();
        if (formatVersion == null
                || (formatVersion != ChunkManifestEncryption.FORMAT_NONE
                && formatVersion != ChunkManifestEncryption.FORMAT_LEGACY_V1
                && formatVersion != ChunkManifestEncryption.FORMAT_FRAMED_V2)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "不支持的 manifest encryption formatVersion");
        }
        if (formatVersion != ChunkManifestEncryption.FORMAT_FRAMED_V2) {
            if (encryption.algorithmSuite() != null
                    || encryption.fileNonce() != null
                    || encryption.framePlainSize() != null
                    || encryption.keyDerivation() != null
                    || encryption.nonceDerivation() != null
                    || encryption.aadSchema() != null
                    || encryption.tagSize() != null) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID,
                        "legacy/none manifest encryption descriptor must not contain v2 fields");
            }
            return new ChunkManifestEncryption(formatVersion, null, null, null, null, null, null, null);
        }

        if (!ChunkManifestEncryption.SUITE_FRAMED_V2.equals(encryption.algorithmSuite())
                || !ChunkManifestEncryption.DERIVATION_HKDF_SHA256.equals(encryption.keyDerivation())
                || !ChunkManifestEncryption.DERIVATION_HKDF_SHA256.equals(encryption.nonceDerivation())
                || !ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2.equals(encryption.aadSchema())
                || !Objects.equals(encryption.tagSize(), ChunkManifestEncryption.TAG_SIZE_BYTES)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "v2 manifest encryption descriptor 不在 allowlist");
        }
        if (encryption.framePlainSize() == null
                || encryption.framePlainSize() < ChunkManifestEncryption.MIN_FRAME_PLAIN_SIZE
                || encryption.framePlainSize() > ChunkManifestEncryption.MAX_FRAME_PLAIN_SIZE) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "v2 framePlainSize 超出有界范围");
        }
        if (!StringUtils.hasText(encryption.fileNonce())
                || encryption.fileNonce().contains("=")
                || !isExactBase64Url(encryption.fileNonce(), ChunkManifestEncryption.FILE_NONCE_SIZE_BYTES)) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "v2 fileNonce 必须是 16 字节 Base64URL");
        }
        return new ChunkManifestEncryption(
                formatVersion,
                encryption.algorithmSuite(),
                encryption.fileNonce(),
                encryption.framePlainSize(),
                encryption.keyDerivation(),
                encryption.nonceDerivation(),
                encryption.aadSchema(),
                encryption.tagSize());
    }

    /**
     * 验证 Base64URL 字符串解码后长度和无填充规范化结果。
     */
    private boolean isExactBase64Url(String value, int expectedBytes) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            return decoded.length == expectedBytes
                    && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
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
