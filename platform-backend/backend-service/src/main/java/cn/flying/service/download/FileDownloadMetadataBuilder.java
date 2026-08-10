package cn.flying.service.download;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.DownloadAccessIdentityVO;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileDownloadEncryptionVO;
import cn.flying.dao.vo.file.FileDownloadMetadataVO;
import cn.flying.dao.vo.file.FileDownloadPartVO;
import cn.flying.dao.vo.file.ManifestErrorDetail;
import cn.flying.service.encryption.FramedAeadCrypto;
import cn.flying.service.key.FileKeyGrantAccessKind;
import cn.flying.service.manifest.ChunkManifestCanonicalizer;
import cn.flying.service.manifest.ChunkManifestChunk;
import cn.flying.service.manifest.ChunkManifestDraft;
import cn.flying.service.manifest.ChunkManifestEncryption;
import cn.flying.service.manifest.ChunkManifestService;
import cn.flying.service.manifest.ChunkManifestView;
import cn.flying.service.manifest.backfill.ManifestGovernanceStatusService;
import cn.flying.service.remote.FileRemoteClient;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Shared manifest-driven metadata builder for owner and share-code downloads.
 */
public final class FileDownloadMetadataBuilder {

    private static final long DOWNLOAD_URL_TTL_SECONDS = 24L * 60L * 60L;
    private static final String ENCRYPTION_NONE = "NONE";
    private static final String FRAMED_ENCRYPTION_ALGORITHM = "FRAMED_AEAD_V2";
    private static final Pattern CANONICAL_SHA256_PATTERN = Pattern.compile("sha256:[0-9a-f]{64}");

    private FileDownloadMetadataBuilder() {
    }

    /**
     * Carries the server-authorized principal and share identity used by refresh fencing.
     */
    public record DownloadAccessBinding(
            String accessKind,
            Long tenantId,
            Long actorId,
            Long shareId,
            String publicClientIdentity
    ) {
    }

    /**
     * Builds one fully validated metadata response and delays key delivery until URLs are ready.
     */
    public static FileDownloadMetadataVO build(
            File file,
            String requestedFileHash,
            FileDecryptInfoVO decryptInfo,
            Supplier<FileDecryptInfoVO> deliveredDecryptInfoSupplier,
            DownloadAccessBinding accessBinding,
            ChunkManifestService chunkManifestService,
            ManifestGovernanceStatusService manifestGovernanceStatusService,
            FileRemoteClient fileRemoteClient
    ) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(decryptInfo, "decryptInfo");
        Objects.requireNonNull(deliveredDecryptInfoSupplier, "deliveredDecryptInfoSupplier");
        if (file.getId() == null || !StringUtils.hasText(requestedFileHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载文件身份不完整");
        }
        validateAccessBinding(accessBinding);

        ChunkManifestView manifest = chunkManifestService.findActiveManifest(file.getUid(), file.getId())
                .orElseThrow(() -> new GeneralException(
                        ResultEnum.FILE_RECORD_ERROR,
                        manifestGovernanceStatusService.missingManifest(file)));
        if (manifest.chunks() == null || manifest.chunks().isEmpty()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    new ManifestErrorDetail("ACTIVE", "ALREADY_MANIFEST",
                            "ACTIVE_MANIFEST_EMPTY", false));
        }

        long fileSize = resolveDownloadFileSize(file, decryptInfo);
        long responseChunkSize = validateDownloadManifest(
                file, requestedFileHash, decryptInfo, manifest, fileSize, chunkManifestService);
        ChunkManifestDraft downloadDraft = toDownloadDraft(manifest);
        String canonicalManifestJson = chunkManifestService.calculateCanonicalJson(downloadDraft);

        List<String> storagePaths = manifest.chunks().stream()
                .map(ChunkManifestChunk::storagePath)
                .toList();
        List<String> cipherHashes = manifest.chunks().stream()
                .map(ChunkManifestChunk::cipherHash)
                .toList();
        List<String> downloadUrls = ResultUtils.getData(
                fileRemoteClient.getFileUrlListByHash(storagePaths, cipherHashes));
        if (downloadUrls == null || downloadUrls.size() != manifest.chunks().size()) {
            throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "对象存储返回的下载 URL 数量不一致");
        }

        long expiresAtEpochSeconds = System.currentTimeMillis() / 1000L + DOWNLOAD_URL_TTL_SECONDS;
        List<FileDownloadPartVO> parts = buildDownloadParts(manifest, downloadUrls, expiresAtEpochSeconds);
        ManifestErrorDetail manifestStatus = manifestGovernanceStatusService.activeManifest();
        FileDecryptInfoVO deliveredDecryptInfo = Objects.requireNonNull(
                deliveredDecryptInfoSupplier.get(), "deliveredDecryptInfo");
        DownloadAccessIdentityVO accessIdentity = buildAccessIdentity(accessBinding, file, manifest);

        return new FileDownloadMetadataVO(
                IdUtils.toExternalId(file.getId()),
                requestedFileHash,
                decryptInfo.fileName(),
                fileSize,
                decryptInfo.contentType(),
                deliveredDecryptInfo.initialKey(),
                deliveredDecryptInfo.keyGrant(),
                manifest.schemaId(),
                manifest.manifestHash(),
                canonicalManifestJson,
                manifestStatus.manifestStatus(),
                manifestStatus.manifestClassification(),
                manifestStatus.manifestErrorCode(),
                manifestStatus.legacyDownloadAllowed(),
                manifest.hashAlgorithm(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                responseChunkSize,
                parts.size(),
                toDownloadEncryption(manifest.encryption()),
                accessIdentity,
                parts);
    }

    /**
     * Validates that the access binding represents exactly one supported authorization source.
     */
    private static void validateAccessBinding(DownloadAccessBinding binding) {
        if (binding == null
                || !StringUtils.hasText(binding.accessKind())
                || binding.tenantId() == null
                || binding.tenantId() < 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载访问身份不完整");
        }
        FileKeyGrantAccessKind accessKind;
        try {
            accessKind = FileKeyGrantAccessKind.valueOf(binding.accessKind());
        } catch (IllegalArgumentException unsupportedAccessKind) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载访问来源无效");
        }
        boolean publicAccess = accessKind == FileKeyGrantAccessKind.PUBLIC_SHARE;
        boolean shareAccess = publicAccess
                || accessKind == FileKeyGrantAccessKind.AUTHENTICATED_SHARE
                || accessKind == FileKeyGrantAccessKind.FRIEND_SHARE;
        if (shareAccess && binding.shareId() == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载分享身份不完整");
        }
        if (!shareAccess && binding.shareId() != null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "非分享下载携带了分享身份");
        }
        if (publicAccess) {
            if (!StringUtils.hasText(binding.publicClientIdentity()) || binding.actorId() != null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "公开下载身份不完整");
            }
        } else if (binding.actorId() == null || binding.publicClientIdentity() != null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "认证下载身份不完整");
        }
    }

    /**
     * Resolves and cross-checks the persistent file size and file-parameter size.
     */
    private static long resolveDownloadFileSize(File file, FileDecryptInfoVO decryptInfo) {
        Long persistedSize = file.getFileSize();
        Long parameterSize = decryptInfo.fileSize();
        if (persistedSize != null && parameterSize != null && !persistedSize.equals(parameterSize)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件大小与 fileParam 不一致");
        }
        Long resolved = parameterSize != null ? parameterSize : persistedSize;
        if (resolved == null || resolved <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件大小缺失或无效");
        }
        return resolved;
    }

    /**
     * Validates the complete manifest contract before any object URL is requested.
     */
    private static long validateDownloadManifest(
            File file,
            String requestedFileHash,
            FileDecryptInfoVO decryptInfo,
            ChunkManifestView manifest,
            long fileSize,
            ChunkManifestService chunkManifestService
    ) {
        List<ChunkManifestChunk> chunks = manifest.chunks();
        if (!Objects.equals(manifest.fileId(), file.getId())
                || !Objects.equals(manifest.fileHash(), requestedFileHash)
                || !Objects.equals(manifest.fileHash(), file.getFileHash())
                || !ChunkManifestCanonicalizer.SCHEMA_ID.equals(manifest.schemaId())
                || !ChunkManifestCanonicalizer.HASH_ALGORITHM.equals(manifest.hashAlgorithm())
                || manifest.chunkSize() <= 0
                || manifest.totalSize() <= 0
                || manifest.chunkCount() == null
                || manifest.chunkCount() != chunks.size()
                || !StringUtils.hasText(manifest.storageBackend())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 顶层合同无效");
        }
        if (decryptInfo.chunkCount() != null
                && !Objects.equals(decryptInfo.chunkCount(), manifest.chunkCount())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "fileParam 分片数量与 manifest 不一致");
        }

        ChunkManifestEncryption encryption = manifest.encryption();
        boolean framedV2 = encryption != null
                && Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_FRAMED_V2);
        boolean unencrypted = ENCRYPTION_NONE.equalsIgnoreCase(manifest.encryptionAlgorithm());
        validateEncryptionCoherence(manifest, framedV2, unencrypted);

        long aggregateLogicalSize = 0L;
        Set<String> storagePaths = new HashSet<>();
        for (int index = 0; index < chunks.size(); index++) {
            ChunkManifestChunk chunk = chunks.get(index);
            if (chunk == null
                    || chunk.index() != index
                    || chunk.size() <= 0
                    || !StringUtils.hasText(chunk.plainHash())
                    || !StringUtils.hasText(chunk.cipherHash())
                    || !StringUtils.hasText(chunk.storagePath())
                    || !storagePaths.add(chunk.storagePath())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 分片顺序或证据无效");
            }
            long logicalSize = framedV2
                    ? validateFramedChunk(manifest, chunk, index)
                    : chunk.size();
            try {
                aggregateLogicalSize = Math.addExact(aggregateLogicalSize, logicalSize);
            } catch (ArithmeticException overflow) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 分片大小溢出");
            }
        }
        if (aggregateLogicalSize != manifest.totalSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 分片总量不一致");
        }
        if ((framedV2 || unencrypted) && manifest.totalSize() != fileSize) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest 明文总量与文件大小不一致");
        }

        long responseChunkSize = manifest.chunkSize();
        if (decryptInfo.chunkSize() != null) {
            if (decryptInfo.chunkSize() <= 0
                    || ((framedV2 || unencrypted) && decryptInfo.chunkSize() != manifest.chunkSize())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "fileParam 分片大小与 manifest 不一致");
            }
            responseChunkSize = decryptInfo.chunkSize();
        }

        validateV2FileParam(file, manifest, framedV2);
        String calculatedManifestHash;
        try {
            calculatedManifestHash = chunkManifestService.calculateManifestHash(toDownloadDraft(manifest));
        } catch (GeneralException | ArithmeticException invalidManifest) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest canonical 合同无效");
        }
        if (!CANONICAL_SHA256_PATTERN.matcher(Objects.toString(manifest.manifestHash(), "")).matches()
                || !Objects.equals(manifest.manifestHash(), calculatedManifestHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "下载 manifest hash 不一致");
        }
        return responseChunkSize;
    }

    /**
     * Validates encryption-algorithm and descriptor coherence.
     */
    private static void validateEncryptionCoherence(
            ChunkManifestView manifest,
            boolean framedV2,
            boolean unencrypted
    ) {
        ChunkManifestEncryption encryption = manifest.encryption();
        if (framedV2 && !FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(manifest.encryptionAlgorithm())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 加密算法声明不一致");
        }
        if (FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(manifest.encryptionAlgorithm()) && !framedV2) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 缺少加密描述");
        }
        if (encryption == null) {
            return;
        }
        if (Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_NONE) && !unencrypted) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "NONE descriptor 与加密算法冲突");
        }
        if (Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_LEGACY_V1)
                && (unencrypted || framedV2)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "legacy descriptor 与加密算法冲突");
        }
        if (unencrypted && !Objects.equals(encryption.formatVersion(), ChunkManifestEncryption.FORMAT_NONE)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "NONE manifest formatVersion 无效");
        }
    }

    /**
     * Validates framed-v2 plaintext, frame-count and ciphertext-size evidence.
     */
    private static long validateFramedChunk(ChunkManifestView manifest, ChunkManifestChunk chunk, int index) {
        ChunkManifestEncryption encryption = manifest.encryption();
        Long plainSize = chunk.plainSize();
        Integer frameCount = chunk.frameCount();
        if (plainSize == null || plainSize <= 0 || frameCount == null || frameCount <= 0
                || encryption == null
                || encryption.framePlainSize() == null
                || encryption.tagSize() == null
                || !CANONICAL_SHA256_PATTERN.matcher(chunk.plainHash()).matches()
                || !CANONICAL_SHA256_PATTERN.matcher(chunk.cipherHash()).matches()
                || plainSize > manifest.chunkSize()
                || (index < manifest.chunks().size() - 1 && plainSize != manifest.chunkSize())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 分片明文或 hash 合同无效");
        }
        long expectedFrameCount = (plainSize + encryption.framePlainSize() - 1L)
                / encryption.framePlainSize();
        long expectedCipherSize;
        try {
            expectedCipherSize = Math.addExact(
                    Math.addExact(FramedAeadCrypto.CHUNK_HEADER_SIZE, plainSize),
                    Math.multiplyExact(expectedFrameCount,
                            FramedAeadCrypto.FRAME_HEADER_SIZE + (long) encryption.tagSize()));
        } catch (ArithmeticException overflow) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 密文大小溢出");
        }
        if (frameCount.longValue() != expectedFrameCount || chunk.size() != expectedCipherSize) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest frame 或密文大小不一致");
        }
        return plainSize;
    }

    /**
     * Validates that v2 file parameters and the active descriptor remain identical.
     */
    private static void validateV2FileParam(File file, ChunkManifestView manifest, boolean framedV2) {
        if (!framedV2) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(file.getFileParam(), Map.class);
            ChunkManifestEncryption encryption = manifest.encryption();
            if (params == null
                    || !FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(Objects.toString(
                    params.get("encryptionAlgorithm"), ""))
                    || !Objects.equals(params.get("algorithmSuite"), encryption.algorithmSuite())
                    || !Objects.equals(params.get("fileNonce"), encryption.fileNonce())
                    || !Objects.equals(params.get("keyDerivation"), encryption.keyDerivation())
                    || !Objects.equals(params.get("nonceDerivation"), encryption.nonceDerivation())
                    || !Objects.equals(params.get("aadSchema"), encryption.aadSchema())
                    || !numericEquals(params.get("formatVersion"), encryption.formatVersion())
                    || !numericEquals(params.get("framePlainSize"), encryption.framePlainSize())
                    || !numericEquals(params.get("tagSize"), encryption.tagSize())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "v2 fileParam 与 manifest 加密描述不一致");
            }
        } catch (GeneralException e) {
            throw e;
        } catch (RuntimeException parseError) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 fileParam 格式无效");
        }
    }

    /**
     * Compares JSON numeric values using long semantics.
     */
    private static boolean numericEquals(Object actual, Number expected) {
        return actual instanceof Number actualNumber
                && expected != null
                && actualNumber.longValue() == expected.longValue();
    }

    /**
     * Maps the manifest encryption descriptor to the OpenAPI response contract.
     */
    private static FileDownloadEncryptionVO toDownloadEncryption(ChunkManifestEncryption encryption) {
        if (encryption == null) {
            return null;
        }
        return new FileDownloadEncryptionVO(
                encryption.formatVersion(),
                encryption.algorithmSuite(),
                encryption.fileNonce(),
                encryption.framePlainSize(),
                encryption.keyDerivation(),
                encryption.nonceDerivation(),
                encryption.aadSchema(),
                encryption.tagSize());
    }

    /**
     * Combines ordered manifest chunks with the corresponding presigned URLs.
     */
    private static List<FileDownloadPartVO> buildDownloadParts(
            ChunkManifestView manifest,
            List<String> downloadUrls,
            long expiresAtEpochSeconds
    ) {
        List<ChunkManifestChunk> chunks = manifest.chunks();
        List<FileDownloadPartVO> parts = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            ChunkManifestChunk chunk = chunks.get(index);
            String downloadUrl = downloadUrls.get(index);
            if (!StringUtils.hasText(downloadUrl)) {
                throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "对象存储返回空下载 URL");
            }
            parts.add(new FileDownloadPartVO(
                    chunk.index(),
                    chunk.size(),
                    downloadUrl,
                    expiresAtEpochSeconds,
                    chunk.storagePath(),
                    chunk.storageBackend(),
                    chunk.etag(),
                    chunk.plainHash(),
                    chunk.cipherHash(),
                    chunk.checksumAlgorithm(),
                    chunk.plainSize(),
                    chunk.frameCount()));
        }
        return parts;
    }

    /**
     * Converts a loaded manifest into the canonical download draft.
     */
    private static ChunkManifestDraft toDownloadDraft(ChunkManifestView manifest) {
        return new ChunkManifestDraft(
                manifest.schemaId(),
                manifest.fileHash(),
                manifest.hashAlgorithm(),
                manifest.chunkSize(),
                manifest.totalSize(),
                manifest.merkleRoot(),
                manifest.encryptionAlgorithm(),
                manifest.storageBackend(),
                manifest.encryption(),
                manifest.chunks());
    }

    /**
     * Produces a non-secret hash binding authorization, file version and immutable download evidence.
     */
    private static DownloadAccessIdentityVO buildAccessIdentity(
            DownloadAccessBinding binding,
            File file,
            ChunkManifestView manifest
    ) {
        String algorithmSuite = manifest.encryption() != null
                && StringUtils.hasText(manifest.encryption().algorithmSuite())
                ? manifest.encryption().algorithmSuite()
                : Objects.toString(manifest.encryptionAlgorithm(), ENCRYPTION_NONE);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
        List<Object> fields = List.of(
                binding.accessKind(),
                binding.tenantId(),
                Objects.toString(binding.actorId(), ""),
                Objects.toString(binding.shareId(), ""),
                Objects.toString(binding.publicClientIdentity(), ""),
                file.getId(),
                Objects.toString(file.getVersion(), ""),
                manifest.fileHash(),
                manifest.manifestHash(),
                algorithmSuite);
        for (Object field : fields) {
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    Objects.toString(field, "").getBytes(StandardCharsets.UTF_8));
            digest.update(encoded.getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) '\n');
        }
        return new DownloadAccessIdentityVO(
                binding.accessKind(),
                "sha256:" + HexFormat.of().formatHex(digest.digest()),
                file.getVersion(),
                manifest.manifestHash(),
                algorithmSuite);
    }
}
