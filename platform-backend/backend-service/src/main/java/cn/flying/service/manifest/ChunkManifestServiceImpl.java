package cn.flying.service.manifest;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.FileChunkManifest;
import cn.flying.dao.entity.FileChunkManifestItem;
import cn.flying.dao.mapper.FileChunkManifestItemMapper;
import cn.flying.dao.mapper.FileChunkManifestMapper;
import cn.flying.dao.mapper.FileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists and loads chunk manifests through the backend database boundary.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkManifestServiceImpl implements ChunkManifestService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_SUPERSEDED = "SUPERSEDED";

    private final FileMapper fileMapper;
    private final FileChunkManifestMapper manifestMapper;
    private final FileChunkManifestItemMapper manifestItemMapper;
    private final ChunkManifestCanonicalizer canonicalizer;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * Validates, canonicalizes, and persists a manifest for the given file.
     */
    @Override
    @Transactional
    public ChunkManifestView saveManifest(Long userId, Long fileId, ChunkManifestDraft draft) {
        Long tenantId = TenantContext.requireTenantId();
        File file = loadAndValidateFile(userId, tenantId, fileId);
        ChunkManifestDraft normalized = canonicalizer.normalize(draft);
        validateFileHash(file, normalized);

        String manifestHash = canonicalizer.manifestHash(normalized);
        String canonicalJson = canonicalizer.canonicalJson(normalized);
        supersedeActiveManifest(tenantId, fileId);

        FileChunkManifest manifest = insertManifest(tenantId, file, normalized, manifestHash, canonicalJson);
        insertChunks(tenantId, fileId, manifest.getId(), normalized.chunks());

        log.info("Saved chunk manifest: tenantId={}, fileId={}, manifestId={}, chunkCount={}",
                tenantId, fileId, manifest.getId(), normalized.chunks().size());
        return toView(manifest, normalized.chunks());
    }

    /**
     * Loads the active manifest header and ordered chunks for a file.
     */
    @Override
    public Optional<ChunkManifestView> findActiveManifest(Long userId, Long fileId) {
        Long tenantId = TenantContext.requireTenantId();
        loadAndValidateFile(userId, tenantId, fileId);

        FileChunkManifest manifest = manifestMapper.selectOne(new LambdaQueryWrapper<FileChunkManifest>()
                .eq(FileChunkManifest::getTenantId, tenantId)
                .eq(FileChunkManifest::getFileId, fileId)
                .eq(FileChunkManifest::getStatus, STATUS_ACTIVE)
                .eq(FileChunkManifest::getDeleted, 0)
                .orderByDesc(FileChunkManifest::getCreateTime)
                .last("LIMIT 1"));
        if (manifest == null) {
            return Optional.empty();
        }
        return Optional.of(toView(manifest, loadChunks(tenantId, manifest.getId())));
    }

    /**
     * Calculates a deterministic manifest hash without touching persistence.
     */
    @Override
    public String calculateManifestHash(ChunkManifestDraft draft) {
        return canonicalizer.manifestHash(draft);
    }

    /**
     * Loads a file record and enforces tenant and optional owner constraints.
     */
    private File loadAndValidateFile(Long userId, Long tenantId, Long fileId) {
        if (fileId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "fileId is required");
        }
        File file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        if (file.getTenantId() != null && !tenantId.equals(file.getTenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "file does not belong to current tenant");
        }
        if (userId != null && file.getUid() != null && !userId.equals(file.getUid())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "file does not belong to current user");
        }
        return file;
    }

    /**
     * Ensures the manifest describes the same file hash as the file record.
     */
    private void validateFileHash(File file, ChunkManifestDraft draft) {
        if (!StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "file hash is required before saving chunk manifest");
        }
        if (!Objects.equals(file.getFileHash().trim(), draft.fileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "manifest fileHash must match file record");
        }
    }

    /**
     * Soft-deletes currently active manifests before inserting the replacement.
     */
    private void supersedeActiveManifest(Long tenantId, Long fileId) {
        FileChunkManifest update = new FileChunkManifest()
                .setStatus(STATUS_SUPERSEDED)
                .setDeleted(1);
        manifestMapper.update(update, new LambdaUpdateWrapper<FileChunkManifest>()
                .eq(FileChunkManifest::getTenantId, tenantId)
                .eq(FileChunkManifest::getFileId, fileId)
                .eq(FileChunkManifest::getStatus, STATUS_ACTIVE)
                .eq(FileChunkManifest::getDeleted, 0));
    }

    /**
     * Inserts the manifest header row.
     */
    private FileChunkManifest insertManifest(Long tenantId, File file, ChunkManifestDraft draft,
                                             String manifestHash, String canonicalJson) {
        FileChunkManifest manifest = new FileChunkManifest()
                .setId(snowflakeIdGenerator.nextId())
                .setTenantId(tenantId)
                .setFileId(file.getId())
                .setFileVersion(file.getVersion())
                .setFileHash(draft.fileHash())
                .setSchemaId(draft.schemaId())
                .setManifestHash(manifestHash)
                .setHashAlgorithm(draft.hashAlgorithm())
                .setChunkSize(draft.chunkSize())
                .setChunkCount(draft.chunks().size())
                .setTotalSize(draft.totalSize())
                .setMerkleRoot(draft.merkleRoot())
                .setEncryptionAlgorithm(draft.encryptionAlgorithm())
                .setStorageBackend(draft.storageBackend())
                .setManifestJson(canonicalJson)
                .setStatus(STATUS_ACTIVE)
                .setDeleted(0);
        manifestMapper.insert(manifest);
        return manifest;
    }

    /**
     * Inserts ordered chunk rows for the manifest.
     */
    private void insertChunks(Long tenantId, Long fileId, Long manifestId, List<ChunkManifestChunk> chunks) {
        for (ChunkManifestChunk chunk : chunks) {
            FileChunkManifestItem item = new FileChunkManifestItem()
                    .setId(snowflakeIdGenerator.nextId())
                    .setTenantId(tenantId)
                    .setManifestId(manifestId)
                    .setFileId(fileId)
                    .setChunkIndex(chunk.index())
                    .setPlainHash(chunk.plainHash())
                    .setCipherHash(chunk.cipherHash())
                    .setSize(chunk.size())
                    .setStoragePath(chunk.storagePath())
                    .setStorageBackend(chunk.storageBackend())
                    .setEtag(chunk.etag())
                    .setChecksumAlgorithm(chunk.checksumAlgorithm())
                    .setDeleted(0);
            manifestItemMapper.insert(item);
        }
    }

    /**
     * Loads persisted chunks in manifest order.
     */
    private List<ChunkManifestChunk> loadChunks(Long tenantId, Long manifestId) {
        return manifestItemMapper.selectList(new LambdaQueryWrapper<FileChunkManifestItem>()
                        .eq(FileChunkManifestItem::getTenantId, tenantId)
                        .eq(FileChunkManifestItem::getManifestId, manifestId)
                        .eq(FileChunkManifestItem::getDeleted, 0)
                        .orderByAsc(FileChunkManifestItem::getChunkIndex))
                .stream()
                .map(this::toChunk)
                .toList();
    }

    /**
     * Converts a manifest entity and chunks into the service view contract.
     */
    private ChunkManifestView toView(FileChunkManifest manifest, List<ChunkManifestChunk> chunks) {
        return new ChunkManifestView(
                manifest.getId(),
                manifest.getFileId(),
                manifest.getFileVersion(),
                manifest.getSchemaId(),
                manifest.getFileHash(),
                manifest.getManifestHash(),
                manifest.getHashAlgorithm(),
                manifest.getChunkSize(),
                manifest.getTotalSize(),
                manifest.getMerkleRoot(),
                manifest.getEncryptionAlgorithm(),
                manifest.getStorageBackend(),
                List.copyOf(chunks)
        );
    }

    /**
     * Converts a chunk entity into the service chunk contract.
     */
    private ChunkManifestChunk toChunk(FileChunkManifestItem item) {
        return new ChunkManifestChunk(
                item.getChunkIndex(),
                item.getPlainHash(),
                item.getCipherHash(),
                item.getSize(),
                item.getStoragePath(),
                item.getStorageBackend(),
                item.getEtag(),
                item.getChecksumAlgorithm()
        );
    }
}
