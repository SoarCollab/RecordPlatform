package cn.flying.service.manifest;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.JsonConverter;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileUploadState;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.encryption.FramedAeadCrypto;
import cn.flying.service.encryption.FramedAeadWriter;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.support.StoredObjectReference;
import cn.flying.service.support.StoredObjectReferenceCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 在普通代理存储成功后解析链上对象引用并幂等保存 framed v2 manifest。
 */
@Service
@RequiredArgsConstructor
public class FramedManifestFinalizationService {

    private static final String FRAMED_ENCRYPTION_ALGORITHM = "FRAMED_AEAD_V2";
    private static final String STORAGE_BACKEND = "S3";
    private static final int MAX_REFERENCE_TEXT_LENGTH = 2048;
    private static final int ENCRYPTION_RECOVERY_VERSION = 2;

    private final FileRemoteClient fileRemoteClient;
    private final ChunkManifestService chunkManifestService;
    private final FramedAeadWriter framedAeadWriter;

    /**
     * 为已成功写入链和数据库的普通 v2 上传创建或恢复 active manifest。
     *
     * @param userId 上传用户
     * @param file 已持久化的 SUCCESS 文件
     * @param state Redis 中的稳定加密检查点
     * @param processedFiles 已生成的 v2 对象文件
     * @param cipherHashes 本地计算的对象密文摘要
     * @return active manifest；非 v2 会话返回空
     */
    public Optional<ChunkManifestView> ensureManifest(
            Long userId,
            File file,
            FileUploadState state,
            List<java.io.File> processedFiles,
            List<String> cipherHashes
    ) {
        if (!isFramedV2(state)) {
            if (declaresFramedV2(state) || declaresFramedV2(file)) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "显式 v2 文件缺少完整上传加密检查点");
            }
            return Optional.empty();
        }

        // DB SUCCESS 后重试时临时目录可能已经清理；已有 active manifest 是唯一可复用的完成证据。
        validateFramedState(state);
        validateFileIdentity(userId, file, state);
        validateFileParam(file, state);
        Optional<ChunkManifestView> active = chunkManifestService.findActiveManifest(userId, file.getId());
        if (active.isPresent()) {
            validateActiveManifest(active.get(), file, state);
            return active;
        }

        validateFileAndState(userId, file, state, processedFiles, cipherHashes);

        List<StoredObjectReference> references = resolveChainReferences(userId, file);
        validateReferences(file, state, references, cipherHashes);

        ChunkManifestEncryption encryption = buildEncryptionDescriptor(state);
        List<ChunkManifestChunk> chunks = buildChunks(state, processedFiles, cipherHashes, references, encryption);
        ChunkManifestDraft draft = new ChunkManifestDraft(
                ChunkManifestCanonicalizer.SCHEMA_ID,
                file.getFileHash(),
                ChunkManifestCanonicalizer.HASH_ALGORITHM,
                state.getChunkSize(),
                state.getFileSize(),
                null,
                FRAMED_ENCRYPTION_ALGORITHM,
                STORAGE_BACKEND,
                encryption,
                chunks
        );

        String expectedManifestHash = state.getManifestHash();
        String calculatedManifestHash = chunkManifestService.calculateManifestHash(draft);
        if (StringUtils.hasText(expectedManifestHash)
                && !expectedManifestHash.equals(calculatedManifestHash)) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "v2 manifest 检查点与当前对象证据不一致");
        }

        if (active.isPresent()) {
            if (!Objects.equals(active.get().manifestHash(), calculatedManifestHash)) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "已有 active manifest 与 v2 对象证据不一致");
            }
            return active;
        }
        return Optional.of(chunkManifestService.saveManifest(userId, file.getId(), draft));
    }

    /**
     * 判断检查点是否完整声明 framed v2。
     */
    private boolean isFramedV2(FileUploadState state) {
        return state != null
                && Objects.equals(state.getEncryptionFormatVersion(), FramedAeadCrypto.FORMAT_VERSION)
                && Objects.equals(state.getEncryptionAlgorithmSuite(), ChunkManifestEncryption.SUITE_FRAMED_V2);
    }

    /**
     * 判断残缺 state 是否已经声明 v2，避免将字段缺失的显式 v2 会话误当 legacy 跳过。
     */
    private boolean declaresFramedV2(FileUploadState state) {
        return state != null
                && (Objects.equals(state.getEncryptionFormatVersion(), FramedAeadCrypto.FORMAT_VERSION)
                || Objects.equals(state.getEncryptionAlgorithmSuite(), ChunkManifestEncryption.SUITE_FRAMED_V2));
    }

    /**
     * 从已持久化 fileParam 识别显式 v2；解析失败且包含 v2 标识时同样失败关闭。
     */
    private boolean declaresFramedV2(File file) {
        if (file == null || !StringUtils.hasText(file.getFileParam())) {
            return false;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(file.getFileParam(), Map.class);
            return params != null
                    && (FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(
                    Objects.toString(params.get("encryptionAlgorithm"), ""))
                    || numericEquals(params.get("formatVersion"), FramedAeadCrypto.FORMAT_VERSION)
                    || Objects.equals(params.get("algorithmSuite"), ChunkManifestEncryption.SUITE_FRAMED_V2));
        } catch (RuntimeException parseError) {
            if (file.getFileParam().toUpperCase(Locale.ROOT).contains(FRAMED_ENCRYPTION_ALGORITHM)) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "显式 v2 fileParam 格式无效");
            }
            return false;
        }
    }

    /**
     * 校验 Redis 中所有 v2 不可变字段，统一转换为结构化业务异常而不是空指针。
     */
    private void validateFramedState(FileUploadState state) {
        if (state == null
                || !Objects.equals(state.getEncryptionRecoveryVersion(), ENCRYPTION_RECOVERY_VERSION)
                || !Objects.equals(state.getEncryptionFormatVersion(), FramedAeadCrypto.FORMAT_VERSION)
                || !Objects.equals(state.getEncryptionAlgorithmSuite(), ChunkManifestEncryption.SUITE_FRAMED_V2)
                || state.getFileDataKey() == null
                || state.getFileDataKey().length != FramedAeadCrypto.FILE_DEK_SIZE
                || state.getFileNonce() == null
                || state.getFileNonce().length != FramedAeadCrypto.FILE_NONCE_SIZE
                || state.getFramePlainSize() == null
                || state.getFramePlainSize() < ChunkManifestEncryption.MIN_FRAME_PLAIN_SIZE
                || state.getFramePlainSize() > ChunkManifestEncryption.MAX_FRAME_PLAIN_SIZE
                || !Objects.equals(state.getKeyDerivation(), ChunkManifestEncryption.DERIVATION_HKDF_SHA256)
                || !Objects.equals(state.getNonceDerivation(), ChunkManifestEncryption.DERIVATION_HKDF_SHA256)
                || !Objects.equals(state.getAadSchema(), ChunkManifestEncryption.AAD_SCHEMA_FRAMED_V2)
                || !Objects.equals(state.getTagSize(), FramedAeadCrypto.TAG_SIZE)
                || state.getFileSize() <= 0
                || state.getChunkSize() <= 0
                || state.getTotalChunks() <= 0) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 上传加密检查点不完整");
        }
        long expectedChunks = (state.getFileSize() + state.getChunkSize() - 1L) / state.getChunkSize();
        if (expectedChunks != state.getTotalChunks()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 上传分片计划与文件大小不一致");
        }
        if (StringUtils.hasText(state.getManifestHash())
                && !state.getManifestHash().matches("sha256:[0-9a-f]{64}")) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest hash 检查点格式无效");
        }
    }

    /**
     * 校验文件、会话身份和输入数量，避免将其他会话对象写入 manifest。
     */
    private void validateFileAndState(
            Long userId,
            File file,
            FileUploadState state,
            List<java.io.File> processedFiles,
            List<String> cipherHashes
    ) {
        validateFileIdentity(userId, file, state);
        if (state.getTotalChunks() <= 0
                || processedFiles == null
                || processedFiles.size() != state.getTotalChunks()
                || cipherHashes == null
                || cipherHashes.size() != state.getTotalChunks()
                || !Objects.equals(file.getFileSize(), state.getFileSize())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 输入证据不完整");
        }
    }

    /**
     * 校验文件与上传检查点的稳定身份字段。
     */
    private void validateFileIdentity(Long userId, File file, FileUploadState state) {
        if (userId == null || file == null || file.getId() == null
                || state == null
                || !StringUtils.hasText(file.getFileHash())
                || file.getTenantId() == null
                || state.getTenantId() == null
                || state.getUserId() == null
                || state.getPreparedFileId() == null
                || !Objects.equals(file.getFileSize(), state.getFileSize())
                || !Objects.equals(file.getTenantId(), state.getTenantId())
                || !Objects.equals(state.getUserId(), userId)
                || !Objects.equals(state.getPreparedFileId(), file.getId())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 manifest 文件身份证据不完整");
        }
        if (file.getUid() != null && !Objects.equals(file.getUid(), userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "v2 manifest 用户不匹配");
        }
    }

    /**
     * 校验持久化 fileParam 与稳定 v2 state 的格式、nonce、派生和分片计划完全一致。
     */
    private void validateFileParam(File file, FileUploadState state) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(file.getFileParam(), Map.class);
            String expectedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(state.getFileNonce());
            if (params == null
                    || !FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(
                    Objects.toString(params.get("encryptionAlgorithm"), ""))
                    || !Objects.equals(params.get("algorithmSuite"), state.getEncryptionAlgorithmSuite())
                    || !Objects.equals(params.get("fileNonce"), expectedNonce)
                    || !Objects.equals(params.get("keyDerivation"), state.getKeyDerivation())
                    || !Objects.equals(params.get("nonceDerivation"), state.getNonceDerivation())
                    || !Objects.equals(params.get("aadSchema"), state.getAadSchema())
                    || !numericEquals(params.get("formatVersion"), state.getEncryptionFormatVersion())
                    || !numericEquals(params.get("framePlainSize"), state.getFramePlainSize())
                    || !numericEquals(params.get("tagSize"), state.getTagSize())
                    || !numericEquals(params.get("fileSize"), state.getFileSize())
                    || !numericEquals(params.get("chunkSize"), state.getChunkSize())
                    || !numericEquals(params.get("chunkCount"), state.getTotalChunks())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "v2 fileParam 与上传检查点不一致");
            }
        } catch (GeneralException e) {
            throw e;
        } catch (RuntimeException parseError) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 fileParam 格式无效");
        }
    }

    /**
     * 验证已存在 active manifest 的 descriptor、明文总量和 canonical hash 后再作为恢复证据复用。
     */
    private void validateActiveManifest(
            ChunkManifestView manifest,
            File file,
            FileUploadState state
    ) {
        ChunkManifestEncryption expectedEncryption = buildEncryptionDescriptor(state);
        if (manifest == null
                || !Objects.equals(manifest.fileId(), file.getId())
                || !Objects.equals(manifest.fileVersion(), file.getVersion())
                || !Objects.equals(manifest.fileHash(), file.getFileHash())
                || !ChunkManifestCanonicalizer.SCHEMA_ID.equals(manifest.schemaId())
                || !ChunkManifestCanonicalizer.HASH_ALGORITHM.equals(manifest.hashAlgorithm())
                || manifest.chunkSize() != state.getChunkSize()
                || !Objects.equals(manifest.chunkCount(), state.getTotalChunks())
                || manifest.totalSize() != state.getFileSize()
                || !FRAMED_ENCRYPTION_ALGORITHM.equalsIgnoreCase(manifest.encryptionAlgorithm())
                || !STORAGE_BACKEND.equalsIgnoreCase(manifest.storageBackend())
                || !Objects.equals(manifest.encryption(), expectedEncryption)
                || manifest.chunks() == null
                || manifest.chunks().size() != state.getTotalChunks()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                    "已有 active manifest 与 v2 上传检查点不一致");
        }

        long aggregatePlainSize = 0L;
        Set<String> storagePaths = new HashSet<>();
        for (int index = 0; index < manifest.chunks().size(); index++) {
            ChunkManifestChunk chunk = manifest.chunks().get(index);
            if (chunk == null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "已有 active v2 manifest 分片证据无效: " + index);
            }
            long expectedPlainSize = expectedPlainChunkSize(state, index);
            long expectedFrameCount = (expectedPlainSize + state.getFramePlainSize() - 1L)
                    / state.getFramePlainSize();
            long expectedCipherSize;
            try {
                expectedCipherSize = Math.addExact(
                        Math.addExact(FramedAeadCrypto.CHUNK_HEADER_SIZE, expectedPlainSize),
                        Math.multiplyExact(expectedFrameCount,
                                FramedAeadCrypto.FRAME_HEADER_SIZE + (long) FramedAeadCrypto.TAG_SIZE));
                aggregatePlainSize = Math.addExact(aggregatePlainSize, expectedPlainSize);
            } catch (ArithmeticException overflow) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "active v2 manifest 分片大小溢出");
            }
            if (chunk.index() != index
                    || !Objects.equals(chunk.plainSize(), expectedPlainSize)
                    || !Objects.equals(chunk.frameCount(), Math.toIntExact(expectedFrameCount))
                    || chunk.size() != expectedCipherSize
                    || !isCanonicalSha256(chunk.plainHash())
                    || !isCanonicalSha256(chunk.cipherHash())
                    || !ChunkManifestCanonicalizer.HASH_ALGORITHM.equals(chunk.checksumAlgorithm())
                    || !STORAGE_BACKEND.equalsIgnoreCase(chunk.storageBackend())
                    || !StringUtils.hasText(chunk.storagePath())
                    || !storagePaths.add(chunk.storagePath())
                    || !isTenantStoragePath(file.getTenantId(), chunk.storagePath(), chunk.cipherHash())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "已有 active v2 manifest 分片证据无效: " + index);
            }
        }
        if (aggregatePlainSize != state.getFileSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "active v2 manifest 明文总量不一致");
        }

        ChunkManifestDraft draft = new ChunkManifestDraft(
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
        String calculatedHash = chunkManifestService.calculateManifestHash(draft);
        if (!Objects.equals(manifest.manifestHash(), calculatedHash)
                || (StringUtils.hasText(state.getManifestHash())
                && !Objects.equals(state.getManifestHash(), manifest.manifestHash()))) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "active v2 manifest canonical hash 不一致");
        }
    }

    /**
     * 从已确认的链记录读取对象引用，不接受客户端或本地路径作为权威来源。
     */
    private List<StoredObjectReference> resolveChainReferences(Long userId, File file) {
        FileDetailVO detail = ResultUtils.getData(
                fileRemoteClient.getFile(String.valueOf(userId), file.getFileHash()));
        if (detail == null || !StringUtils.hasText(detail.content())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上存储引用缺失");
        }
        try {
            return StoredObjectReferenceCodec.parseChainContent(detail.content());
        } catch (RuntimeException parseError) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上存储引用格式无效");
        }
    }

    /**
     * 校验链引用的连续索引、数量、密文摘要和租户绑定路径。
     */
    private void validateReferences(
            File file,
            FileUploadState state,
            List<StoredObjectReference> references,
            List<String> cipherHashes
    ) {
        if (references == null || references.size() != state.getTotalChunks()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上存储引用数量不一致");
        }
        Long tenantId = file.getTenantId();
        String expectedPrefix = tenantId == null ? null : "storage/tenant/" + tenantId + "/chunk/";
        Set<String> paths = new HashSet<>();
        for (int index = 0; index < references.size(); index++) {
            StoredObjectReference reference = references.get(index);
            if (reference == null
                    || reference.index() != index
                    || !StringUtils.hasText(reference.cipherHash())
                    || !StringUtils.hasText(reference.storagePath())
                    || reference.cipherHash().length() > MAX_REFERENCE_TEXT_LENGTH
                    || reference.storagePath().length() > MAX_REFERENCE_TEXT_LENGTH
                    || containsUnsafePathText(reference.storagePath())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上存储引用索引或路径无效");
            }
            String expectedHash = normalizeDigest(cipherHashes.get(index));
            String actualHash = normalizeDigest(reference.cipherHash());
            if (!Objects.equals(expectedHash, actualHash)) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上密文摘要与本地证据不一致");
            }
            if (expectedPrefix != null && !reference.storagePath().startsWith(expectedPrefix)) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上 storagePath 租户不匹配");
            }
            String pathSuffix = reference.storagePath().substring(
                    reference.storagePath().lastIndexOf('/') + 1);
            if (!Objects.equals(normalizeDigest(pathSuffix), actualHash)
                    || !paths.add(reference.storagePath())) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "链上 storagePath 与密文摘要不匹配");
            }
        }
    }

    /**
     * 逐个重新认证对象文件并构造 manifest chunk，避免只相信文件名或 Redis 数量。
     */
    private List<ChunkManifestChunk> buildChunks(
            FileUploadState state,
            List<java.io.File> processedFiles,
            List<String> cipherHashes,
            List<StoredObjectReference> references,
            ChunkManifestEncryption encryption
    ) {
        List<ChunkManifestChunk> chunks = new ArrayList<>(processedFiles.size());
        long aggregatePlainSize = 0L;
        for (int index = 0; index < processedFiles.size(); index++) {
            java.io.File processedFile = processedFiles.get(index);
            if (processedFile == null) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 processed 文件为空");
            }
            try {
                FramedAeadWriter.WriteResult result = framedAeadWriter.verify(
                        processedFile.toPath(),
                        state.getFileDataKey(),
                        state.getFileNonce(),
                        index,
                        state.getTotalChunks(),
                        state.getFramePlainSize());
                String expectedCipherHash = normalizeDigest(cipherHashes.get(index));
                if (!Objects.equals(expectedCipherHash, normalizeDigest(result.cipherHash()))) {
                    throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 对象密文摘要不一致");
                }
                long expectedPlainSize = expectedPlainChunkSize(state, index);
                if (result.plainSize() != expectedPlainSize) {
                    throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 对象明文大小不一致");
                }
                aggregatePlainSize = Math.addExact(aggregatePlainSize, result.plainSize());
                chunks.add(new ChunkManifestChunk(
                        index,
                        result.plainHash(),
                        result.cipherHash(),
                        result.cipherSize(),
                        references.get(index).storagePath(),
                        STORAGE_BACKEND,
                        null,
                        ChunkManifestCanonicalizer.HASH_ALGORITHM,
                        result.plainSize(),
                        result.frameCount()
                ));
            } catch (IOException | ArithmeticException | IllegalArgumentException verificationError) {
                throw new GeneralException(ResultEnum.FILE_RECORD_ERROR,
                        "v2 对象认证或长度校验失败: " + index);
            }
        }
        if (aggregatePlainSize != state.getFileSize()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 对象明文总量与文件大小不一致");
        }
        return List.copyOf(chunks);
    }

    /**
     * 从稳定 Redis 检查点构造并验证 v2 manifest encryption descriptor。
     */
    private ChunkManifestEncryption buildEncryptionDescriptor(FileUploadState state) {
        String fileNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(state.getFileNonce());
        return new ChunkManifestEncryption(
                FramedAeadCrypto.FORMAT_VERSION,
                state.getEncryptionAlgorithmSuite(),
                fileNonce,
                state.getFramePlainSize(),
                state.getKeyDerivation(),
                state.getNonceDerivation(),
                state.getAadSchema(),
                state.getTagSize()
        );
    }

    /**
     * 计算上传计划中指定分片的精确明文长度。
     */
    private long expectedPlainChunkSize(FileUploadState state, int index) {
        if (index < 0 || index >= state.getTotalChunks()) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "v2 分片索引超出上传计划");
        }
        return index == state.getTotalChunks() - 1
                ? state.getFileSize() - ((long) state.getChunkSize() * index)
                : state.getChunkSize();
    }

    /**
     * 校验 active manifest 路径同时绑定租户前缀和规范密文摘要。
     */
    private boolean isTenantStoragePath(Long tenantId, String storagePath, String cipherHash) {
        if (tenantId == null || !StringUtils.hasText(storagePath) || containsUnsafePathText(storagePath)) {
            return false;
        }
        String expectedPrefix = "storage/tenant/" + tenantId + "/chunk/";
        String pathSuffix = storagePath.substring(storagePath.lastIndexOf('/') + 1);
        return storagePath.startsWith(expectedPrefix)
                && Objects.equals(normalizeDigest(pathSuffix), normalizeDigest(cipherHash));
    }

    /**
     * 判断摘要是否为 manifest 使用的规范 sha256 小写十六进制格式。
     */
    private boolean isCanonicalSha256(String value) {
        return value != null && value.matches("sha256:[0-9a-f]{64}");
    }

    /**
     * 以 long 语义比较 JSON 数值和预期整数。
     */
    private boolean numericEquals(Object actual, Number expected) {
        return actual instanceof Number actualNumber
                && expected != null
                && actualNumber.longValue() == expected.longValue();
    }

    /**
     * 将摘要统一为 sha256: 小写十六进制，兼容链上历史 Base64URL 表示。
     */
    private String normalizeDigest(String value) {
        if (!StringUtils.hasText(value) || value.length() > MAX_REFERENCE_TEXT_LENGTH) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "密文摘要格式无效");
        }
        String trimmed = value.trim();
        String hex = trimmed.toLowerCase(Locale.ROOT);
        if (hex.startsWith("sha256:")) {
            hex = hex.substring("sha256:".length());
        }
        if (hex.matches("[0-9a-f]{64}")) {
            return "sha256:" + hex;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(trimmed);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("digest length");
            }
            return "sha256:" + java.util.HexFormat.of().formatHex(decoded);
        } catch (IllegalArgumentException decodeError) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "密文摘要格式无效");
        }
    }

    /**
     * 拒绝控制字符、绝对路径和路径穿越片段。
     */
    private boolean containsUnsafePathText(String value) {
        return value.startsWith("/")
                || value.contains("..")
                || value.chars().anyMatch(Character::isISOControl)
                || value.contains("\\");
    }
}
