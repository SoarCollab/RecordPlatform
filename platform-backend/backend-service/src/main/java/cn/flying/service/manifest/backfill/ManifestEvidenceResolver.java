package cn.flying.service.manifest.backfill;

import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.manifest.ChunkManifestBatchView;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.remote.FileRemoteClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves historical file evidence without guessing missing order, hashes, size, or tenancy.
 */
@Service
@RequiredArgsConstructor
public class ManifestEvidenceResolver {

    private static final String SNAPSHOT_SCHEMA = "cn.flying.manifest-backfill-evidence.v1";
    private static final String HASH_PREFIX = "sha256:";
    private static final int MAX_POINTER_BYTES = 1024 * 1024;
    private static final int MAX_CHUNKS = 10_000;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final Pattern TENANT_PATH_PATTERN = Pattern.compile(
            "^(?:storage|minio)/tenant/([0-9]+)/(?:(?:chunk/)|(?:node/[^/]+/))(.+)$");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChunkManifestService chunkManifestService;
    private final FileRemoteClient fileRemoteClient;

    /**
     * Produces one deterministic, fail-closed governance outcome for a tenant-owned file row.
     *
     * @param file candidate file
     * @return immutable evidence resolution
     */
    public ManifestEvidenceResolution resolve(File file) {
        Long tenantId = TenantContext.requireTenantId();
        if (file == null || file.getId() == null || !Objects.equals(tenantId, file.getTenantId())) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.MANUAL_REVIEW, false, false, "invalid-file-identity", null, null);
        }
        if (Integer.valueOf(1).equals(file.getDeleted())) {
            return terminal(ManifestBackfillClassification.IGNORED,
                    ManifestBackfillReason.FILE_DELETED, false, false, stableFileDigest(file), null, null);
        }
        if (!Objects.equals(file.getStatus(), FileUploadStatus.SUCCESS.getCode())) {
            return terminal(ManifestBackfillClassification.IGNORED,
                    ManifestBackfillReason.FILE_NOT_SUCCESS, false, false, stableFileDigest(file), null, null);
        }
        if (file.getVersion() == null || file.getVersion() <= 0) {
            return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                    ManifestBackfillReason.VERSION_UNSTABLE, false, false, stableFileDigest(file), null, null);
        }

        ManifestEvidenceResolution activeResolution = resolveExistingManifest(file);
        if (activeResolution != null) {
            return activeResolution;
        }
        if (file.getOrigin() != null) {
            return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                    ManifestBackfillReason.SHARE_SOURCE_UNPROVEN, false, false, stableFileDigest(file), null, null);
        }
        if (!StringUtils.hasText(file.getFileHash()) || file.getUid() == null) {
            return terminal(ManifestBackfillClassification.UNRECOVERABLE,
                    ManifestBackfillReason.MISSING_POINTER, false, false, stableFileDigest(file), null, null);
        }

        Result<FileDetailVO> pointerResult = fileRemoteClient.getFile(
                String.valueOf(file.getUid()), file.getFileHash().trim());
        if (pointerResult == null || !pointerResult.isSuccess()) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.CHAIN_RPC_TRANSIENT, true, false, stableFileDigest(file), null, null);
        }
        FileDetailVO detail = pointerResult.getData();
        if (detail == null || !StringUtils.hasText(detail.content())) {
            return terminal(ManifestBackfillClassification.UNRECOVERABLE,
                    ManifestBackfillReason.MISSING_POINTER, false, false, stableFileDigest(file), null, null);
        }
        if ((StringUtils.hasText(detail.fileHash())
                && !detail.fileHash().trim().equalsIgnoreCase(file.getFileHash().trim()))
                || (StringUtils.hasText(detail.uploader())
                && !Objects.equals(detail.uploader().trim(), String.valueOf(file.getUid())))) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.MANUAL_REVIEW, false, false, stableFileDigest(file), null, null);
        }
        if (detail.fileSize() != null && !Objects.equals(detail.fileSize(), file.getFileSize())) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.AGGREGATE_SIZE_MISMATCH, false, false,
                    stableFileDigest(file), null, null);
        }
        if (detail.content().getBytes(StandardCharsets.UTF_8).length > MAX_POINTER_BYTES) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.MALFORMED_POINTER, false, false, stableFileDigest(file), null, null);
        }
        return resolvePointer(file, detail);
    }

    /**
     * Revalidates a persisted apply snapshot against the current file and object HEAD evidence.
     *
     * @param file locked file row
     * @param evidencePayload persisted evidence JSON
     * @param expectedDigest claimed item evidence digest
     * @return normalized evidence snapshot
     */
    public ManifestBackfillEvidenceSnapshot revalidate(
            File file,
            String evidencePayload,
            String expectedDigest
    ) {
        ManifestBackfillEvidenceSnapshot snapshot = parseEvidencePayload(evidencePayload);
        if (snapshot == null
                || !Objects.equals(snapshot.tenantId(), file.getTenantId())
                || !Objects.equals(snapshot.fileId(), file.getId())
                || !Objects.equals(snapshot.fileVersion(), file.getVersion())
                || !Objects.equals(snapshot.chainRecordId(), file.getFileHash())
                || !Objects.equals(snapshot.contentHash(), file.getContentHash())
                || !Objects.equals(expectedDigest, digestPayload(evidencePayload))) {
            throw new ManifestEvidenceChangedException(ManifestBackfillReason.EVIDENCE_DIGEST_CHANGED);
        }
        String recalculated = chunkManifestService.calculateManifestHash(snapshot.manifestDraft());
        if (!Objects.equals(snapshot.manifestHash(), recalculated)) {
            throw new ManifestEvidenceChangedException(ManifestBackfillReason.EVIDENCE_DIGEST_CHANGED);
        }
        HeadVerification verification = verifyHeads(snapshot.tenantId(), snapshot.manifestDraft().chunks());
        if (verification.reason() != ManifestBackfillReason.BACKFILLABLE_EVIDENCE) {
            throw new ManifestEvidenceChangedException(verification.reason());
        }
        return snapshot;
    }

    /**
     * Parses the access-controlled evidence payload into its typed apply contract.
     *
     * @param evidencePayload JSON payload
     * @return typed snapshot
     */
    public ManifestBackfillEvidenceSnapshot parseEvidencePayload(String evidencePayload) {
        if (!StringUtils.hasText(evidencePayload)
                || evidencePayload.getBytes(StandardCharsets.UTF_8).length > MAX_POINTER_BYTES) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(evidencePayload);
            if (!SNAPSHOT_SCHEMA.equals(root.path("schema").asText())) {
                return null;
            }
            return OBJECT_MAPPER.treeToValue(root.path("snapshot"), ManifestBackfillEvidenceSnapshot.class);
        } catch (JsonProcessingException invalidPayload) {
            return null;
        }
    }

    /**
     * Resolves an existing active manifest without replacing or repairing it silently.
     */
    private ManifestEvidenceResolution resolveExistingManifest(File file) {
        ChunkManifestBatchView batch = chunkManifestService.findActiveManifests(List.of(file.getId()));
        if (batch.duplicateFileIds().contains(file.getId())) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.DUPLICATE_ACTIVE_MANIFEST, false, false,
                    stableFileDigest(file), null, null);
        }
        ChunkManifestView active = batch.manifests().get(file.getId());
        if (active == null) {
            return null;
        }
        try {
            ChunkManifestDraft draft = toDraft(active);
            String calculatedHash = chunkManifestService.calculateManifestHash(draft);
            if (!Objects.equals(active.manifestHash(), calculatedHash)) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.ACTIVE_MANIFEST_INVALID, false, false,
                        stableFileDigest(file), null, active.manifestId());
            }
            return terminal(ManifestBackfillClassification.ALREADY_MANIFEST,
                    ManifestBackfillReason.ALREADY_MANIFEST, false, false,
                    hashText(SNAPSHOT_SCHEMA + "\n" + calculatedHash), null, active.manifestId());
        } catch (RuntimeException invalidManifest) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.ACTIVE_MANIFEST_INVALID, false, false,
                    stableFileDigest(file), null, active.manifestId());
        }
    }

    /**
     * Parses ordered or legacy pointer JSON and derives the strict classification.
     */
    private ManifestEvidenceResolution resolvePointer(File file, FileDetailVO detail) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(detail.content());
            if (root.isObject()) {
                return resolveLegacyMap(file, root);
            }
            if (!root.isArray() || root.isEmpty() || root.size() > MAX_CHUNKS) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.MALFORMED_POINTER, false, false,
                        stableFileDigest(file), null, null);
            }
            return resolveOrderedPointer(file, detail, root);
        } catch (JsonProcessingException invalidPointer) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.MALFORMED_POINTER, false, false,
                    stableFileDigest(file), null, null);
        }
    }

    /**
     * Verifies a legacy map for classification only; unordered entries cannot authorize reconstruction.
     */
    private ManifestEvidenceResolution resolveLegacyMap(File file, JsonNode root) {
        if (root.isEmpty() || root.size() > MAX_CHUNKS) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.MALFORMED_POINTER, false, false,
                    stableFileDigest(file), null, null);
        }
        boolean singleChunkRecoverySafe = root.size() == 1
                && file.getFileSize() != null
                && file.getFileSize() > 0
                && SHA256_PATTERN.matcher(Objects.toString(file.getContentHash(), "")).matches();
        List<ChunkManifestChunk> minimalChunks = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, JsonNode> entry : iterableFields(root)) {
            String cipherHash = normalizeText(entry.getKey());
            String storagePath = normalizeText(entry.getValue().asText(null));
            if (!StringUtils.hasText(cipherHash) || !StringUtils.hasText(storagePath)) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.MALFORMED_POINTER, false, false,
                        stableFileDigest(file), null, null);
            }
            long declaredSize = singleChunkRecoverySafe ? file.getFileSize() : 1L;
            minimalChunks.add(new ChunkManifestChunk(index++, cipherHash, cipherHash, declaredSize,
                    storagePath, "S3", null, "SHA-256"));
        }
        HeadVerification verification = verifyHeads(
                file.getTenantId(), minimalChunks, singleChunkRecoverySafe);
        if (verification.reason() == ManifestBackfillReason.OBJECT_NOT_FOUND) {
            return terminal(ManifestBackfillClassification.UNRECOVERABLE,
                    verification.reason(), false, false, stableFileDigest(file), null, null);
        }
        if (verification.retryable()) {
            return terminal(ManifestBackfillClassification.FAILED,
                    verification.reason(), true, false, stableFileDigest(file), null, null);
        }
        if (verification.reason() != ManifestBackfillReason.BACKFILLABLE_EVIDENCE) {
            return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                    verification.reason(), false, false, stableFileDigest(file), null, null);
        }
        ManifestBackfillReason reason = singleChunkRecoverySafe
                ? ManifestBackfillReason.LEGACY_DOWNLOAD_ALLOWED
                : ManifestBackfillReason.LEGACY_ORDER_UNTRUSTED;
        return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                reason, false, singleChunkRecoverySafe,
                hashText(SNAPSHOT_SCHEMA + "\nlegacy\n" + file.getId() + "\n" + root.size()), null, null);
    }

    /**
     * Validates the rich ordered pointer and constructs a canonical NONE/direct manifest draft.
     */
    private ManifestEvidenceResolution resolveOrderedPointer(File file, FileDetailVO detail, JsonNode root) {
        Map<String, Object> parameters = parseParameters(detail.param(), file.getFileParam());
        String encryptionAlgorithm = normalizeText(parameters.get("encryptionAlgorithm"));
        String uploadMode = normalizeText(parameters.get("uploadMode"));
        if (!"NONE".equalsIgnoreCase(encryptionAlgorithm)
                || !"DIRECT_MULTIPART".equalsIgnoreCase(uploadMode)) {
            return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                    ManifestBackfillReason.UNKNOWN_ENCRYPTION_FORMAT, false, false,
                    stableFileDigest(file), null, null);
        }
        String parameterContentHash = normalizeText(parameters.get("contentHash"));
        String storedContentHash = normalizeText(file.getContentHash());
        if (!SHA256_PATTERN.matcher(Objects.toString(parameterContentHash, "")).matches()
                || !Objects.equals(parameterContentHash, storedContentHash)) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.CONTENT_HASH_MISMATCH, false, false,
                    stableFileDigest(file), null, null);
        }

        List<ChunkManifestChunk> chunks = new ArrayList<>(root.size());
        Set<Integer> indexes = new HashSet<>();
        for (int position = 0; position < root.size(); position++) {
            JsonNode entry = root.get(position);
            if (entry == null || !entry.isObject()) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.MALFORMED_POINTER, false, false,
                        stableFileDigest(file), null, null);
            }
            int index = entry.path("index").asInt(-1);
            if (!indexes.add(index)) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.DUPLICATE_INDEX, false, false,
                        stableFileDigest(file), null, null);
            }
            if (index != position) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.GAPPED_INDEX, false, false,
                        stableFileDigest(file), null, null);
            }
            String plainHash = normalizeText(entry.path("plainHash").asText(null));
            String cipherHash = normalizeText(entry.path("cipherHash").asText(null));
            String storagePath = normalizeText(entry.path("storagePath").asText(null));
            long size = entry.path("size").asLong(-1L);
            String etag = normalizeText(entry.path("eTag").asText(null));
            String checksum = normalizeText(entry.path("checksumAlgorithm").asText("SHA-256"));
            if (!SHA256_PATTERN.matcher(Objects.toString(plainHash, "")).matches()) {
                return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                        ManifestBackfillReason.MISSING_PLAIN_HASH, false, false,
                        stableFileDigest(file), null, null);
            }
            if (!SHA256_PATTERN.matcher(Objects.toString(cipherHash, "")).matches()
                    || !StringUtils.hasText(storagePath)) {
                return terminal(ManifestBackfillClassification.FAILED,
                        ManifestBackfillReason.MALFORMED_POINTER, false, false,
                        stableFileDigest(file), null, null);
            }
            if (size <= 0) {
                return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                        ManifestBackfillReason.MISSING_SIZE, false, false,
                        stableFileDigest(file), null, null);
            }
            chunks.add(new ChunkManifestChunk(index, plainHash, cipherHash, size,
                    storagePath, "S3", etag, checksum));
        }

        long totalSize;
        long chunkSize;
        try {
            totalSize = chunks.stream().mapToLong(ChunkManifestChunk::size).reduce(0L, Math::addExact);
            chunkSize = requiredLong(parameters.get("chunkSize"));
        } catch (ArithmeticException | IllegalArgumentException invalidSize) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.AGGREGATE_SIZE_MISMATCH, false, false,
                    stableFileDigest(file), null, null);
        }
        Long fileSize = file.getFileSize();
        if (fileSize == null || fileSize <= 0 || totalSize != fileSize) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.AGGREGATE_SIZE_MISMATCH, false, false,
                    stableFileDigest(file), null, null);
        }
        HeadVerification verification = verifyHeads(file.getTenantId(), chunks);
        if (verification.reason() == ManifestBackfillReason.OBJECT_NOT_FOUND) {
            return terminal(ManifestBackfillClassification.UNRECOVERABLE,
                    verification.reason(), false, false, stableFileDigest(file), null, null);
        }
        if (verification.retryable()) {
            return terminal(ManifestBackfillClassification.FAILED,
                    verification.reason(), true, false, stableFileDigest(file), null, null);
        }
        if (verification.reason() != ManifestBackfillReason.BACKFILLABLE_EVIDENCE) {
            return terminal(ManifestBackfillClassification.REUPLOAD_REQUIRED,
                    verification.reason(), false, false, stableFileDigest(file), null, null);
        }

        try {
            ChunkManifestDraft draft = new ChunkManifestDraft(
                    ChunkManifestCanonicalizer.SCHEMA_ID,
                    file.getFileHash().trim(),
                    ChunkManifestCanonicalizer.HASH_ALGORITHM,
                    chunkSize,
                    totalSize,
                    null,
                    "NONE",
                    "S3",
                    chunks);
            String manifestHash = chunkManifestService.calculateManifestHash(draft);
            ManifestBackfillEvidenceSnapshot snapshot = new ManifestBackfillEvidenceSnapshot(
                    file.getTenantId(), file.getId(), file.getVersion(), file.getUid(),
                    file.getFileHash(), file.getContentHash(), manifestHash, draft);
            String payload = serializeSnapshot(snapshot);
            return new ManifestEvidenceResolution(
                    ManifestBackfillItemStatus.PENDING,
                    ManifestBackfillClassification.BACKFILLABLE,
                    ManifestBackfillReason.BACKFILLABLE_EVIDENCE,
                    false,
                    false,
                    digestPayload(payload),
                    payload,
                    null);
        } catch (RuntimeException invalidDraft) {
            return terminal(ManifestBackfillClassification.FAILED,
                    ManifestBackfillReason.MALFORMED_POINTER, false, false,
                    stableFileDigest(file), null, null);
        }
    }

    /**
     * Verifies all object identities including path tenant, metadata tenant, hash, size, and ETag.
     */
    private HeadVerification verifyHeads(Long tenantId, List<ChunkManifestChunk> chunks) {
        return verifyHeads(tenantId, chunks, true);
    }

    /**
     * Verifies object HEAD evidence, optionally enforcing declared size for rich pointers.
     */
    private HeadVerification verifyHeads(
            Long tenantId,
            List<ChunkManifestChunk> chunks,
            boolean verifyDeclaredSize
    ) {
        for (ChunkManifestChunk chunk : chunks) {
            Long pathTenantId = parsePathTenant(chunk.storagePath());
            if (!Objects.equals(tenantId, pathTenantId)) {
                return new HeadVerification(ManifestBackfillReason.CROSS_TENANT_PATH, false);
            }
            Result<StorageObjectHeadVO> headResult = fileRemoteClient.headObject(
                    chunk.storagePath(), chunk.cipherHash());
            if (headResult == null || !headResult.isSuccess() || headResult.getData() == null) {
                return new HeadVerification(ManifestBackfillReason.STORAGE_RPC_TRANSIENT, true);
            }
            StorageObjectHeadVO head = headResult.getData();
            if (!head.exists()) {
                return new HeadVerification(ManifestBackfillReason.OBJECT_NOT_FOUND, false);
            }
            if (!Objects.equals(head.filePath(), chunk.storagePath())
                    || !Objects.equals(head.fileHash(), chunk.cipherHash())
                    || !Objects.equals(head.tenantId(), tenantId)
                    || !Objects.equals(head.metadataTenantId(), tenantId)
                    || !Objects.equals(head.metadataHash(), chunk.cipherHash())
                    || (verifyDeclaredSize && !Objects.equals(head.contentLength(), chunk.size()))
                    || (StringUtils.hasText(chunk.etag()) && !Objects.equals(head.eTag(), chunk.etag()))) {
                return new HeadVerification(ManifestBackfillReason.OBJECT_HEAD_MISMATCH, false);
            }
        }
        return new HeadVerification(ManifestBackfillReason.BACKFILLABLE_EVIDENCE, false);
    }

    /**
     * Serializes the evidence under an explicit schema wrapper.
     */
    private String serializeSnapshot(ManifestBackfillEvidenceSnapshot snapshot) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of(
                    "schema", SNAPSHOT_SCHEMA,
                    "snapshot", snapshot));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("manifest evidence serialization failed", serializationFailure);
        }
    }

    /**
     * Parses chain/file parameters while treating malformed metadata as an empty map.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseParameters(String chainParam, String databaseParam) {
        String source = StringUtils.hasText(chainParam) ? chainParam : databaseParam;
        if (!StringUtils.hasText(source) || source.getBytes(StandardCharsets.UTF_8).length > MAX_POINTER_BYTES) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonConverter.parse(source, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (RuntimeException invalidParameters) {
            return Map.of();
        }
    }

    /**
     * Converts an active view into the exact canonical draft used to validate its hash.
     */
    private ChunkManifestDraft toDraft(ChunkManifestView view) {
        return new ChunkManifestDraft(
                view.schemaId(), view.fileHash(), view.hashAlgorithm(), view.chunkSize(),
                view.totalSize(), view.merkleRoot(), view.encryptionAlgorithm(),
                view.storageBackend(), view.encryption(), view.chunks());
    }

    /**
     * Maps a classification to the scan item terminal/pending state.
     */
    private ManifestEvidenceResolution terminal(
            ManifestBackfillClassification classification,
            ManifestBackfillReason reason,
            boolean retryable,
            boolean legacyDownloadAllowed,
            String digest,
            String payload,
            Long manifestId
    ) {
        ManifestBackfillItemStatus status = switch (classification) {
            case BACKFILLABLE -> ManifestBackfillItemStatus.PENDING;
            case REUPLOAD_REQUIRED -> ManifestBackfillItemStatus.REUPLOAD_REQUIRED;
            case UNRECOVERABLE -> ManifestBackfillItemStatus.UNRECOVERABLE;
            case FAILED -> ManifestBackfillItemStatus.FAILED;
            case ALREADY_MANIFEST, IGNORED -> ManifestBackfillItemStatus.IGNORED;
        };
        return new ManifestEvidenceResolution(status, classification, reason, retryable,
                legacyDownloadAllowed, digest, payload, manifestId);
    }

    /**
     * Calculates a bounded non-secret digest for early terminal classifications.
     */
    private String stableFileDigest(File file) {
        return hashText(String.join("\n",
                SNAPSHOT_SCHEMA,
                Objects.toString(file.getTenantId(), ""),
                Objects.toString(file.getId(), ""),
                Objects.toString(file.getVersion(), ""),
                Objects.toString(file.getStatus(), ""),
                Objects.toString(file.getDeleted(), ""),
                Objects.toString(file.getFileHash(), ""),
                Objects.toString(file.getContentHash(), "")));
    }

    /**
     * Hashes the exact serialized evidence payload.
     */
    private String digestPayload(String payload) {
        return hashText(payload);
    }

    /**
     * Produces a canonical lowercase SHA-256 digest.
     */
    private String hashText(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HASH_PREFIX + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Extracts and validates the tenant ID encoded by a supported logical storage path.
     */
    private Long parsePathTenant(String storagePath) {
        Matcher matcher = TENANT_PATH_PATTERN.matcher(Objects.toString(storagePath, ""));
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException invalidTenant) {
            return null;
        }
    }

    /**
     * Converts a JSON object field iterator into an iterable for deterministic traversal.
     */
    private Iterable<Map.Entry<String, JsonNode>> iterableFields(JsonNode node) {
        return node::fields;
    }

    /**
     * Normalizes a nullable scalar into bounded trimmed text.
     */
    private String normalizeText(Object value) {
        if (value == null) {
            return null;
        }
        String normalized = String.valueOf(value).trim();
        return normalized.isEmpty() || normalized.length() > 1024 ? null : normalized;
    }

    /**
     * Reads a required positive long from JSON-compatible metadata.
     */
    private long requiredLong(Object value) {
        long result;
        if (value instanceof Number number) {
            result = number.longValue();
        } else if (value instanceof String text) {
            result = Long.parseLong(text.trim());
        } else {
            throw new IllegalArgumentException("required long is missing");
        }
        if (result <= 0) {
            throw new IllegalArgumentException("required long must be positive");
        }
        return result;
    }

    private record HeadVerification(ManifestBackfillReason reason, boolean retryable) {
    }

    /**
     * Signals that apply-time evidence no longer matches the frozen scan snapshot.
     */
    public static final class ManifestEvidenceChangedException extends RuntimeException {

        private final ManifestBackfillReason reason;

        /**
         * Creates an evidence-change signal carrying a stable reason.
         *
         * @param reason stable reason
         */
        public ManifestEvidenceChangedException(ManifestBackfillReason reason) {
            super(reason.name());
            this.reason = reason;
        }

        /**
         * Returns the stable evidence failure reason.
         *
         * @return reason
         */
        public ManifestBackfillReason reason() {
            return reason;
        }
    }
}
