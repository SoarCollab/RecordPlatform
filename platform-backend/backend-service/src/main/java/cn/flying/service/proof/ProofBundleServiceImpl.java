package cn.flying.service.proof;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.vo.file.ProofBundleVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.StorageObjectHeadVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.service.key.CryptoSuiteMetadata;
import cn.flying.service.key.CryptoSuitePolicyService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds proof bundle JSON contracts from file, storage, chain, batch, and leaf metadata.
 */
@Service
@RequiredArgsConstructor
public class ProofBundleServiceImpl implements ProofBundleService {

    private static final String BUNDLE_TYPE = "RECORD_PLATFORM_MERKLE_FILE_PROOF";
    private static final String BUNDLE_VERSION = "1.0";
    private static final String PLATFORM = "RecordPlatform";
    private static final String ISSUER_CONTRACT = "P1.2-proof-bundle";
    private static final String COMPLETED_STATUS = "COMPLETED";
    private static final String S3_PATH_FORMAT = "storage/tenant/%d/chunk/%s";

    private final FileMapper fileMapper;
    private final AttestationLeafMapper leafMapper;
    private final AttestationBatchMapper batchMapper;
    private final FileRemoteClient fileRemoteClient;
    private final CryptoSuitePolicyService suitePolicy;

    /**
     * Export a proof bundle for a file version selected by internal file ID.
     */
    @Override
    public ProofBundleVO exportByFileId(Long userId, Long fileId) {
        File file = loadAuthorizedFile(userId, fileId);
        AttestationLeaf leaf = findLatestLeafForFile(file);
        AttestationBatch batch = loadCompletedBatch(file.getTenantId(), leaf.getBatchId());
        return buildBundle(file, leaf, batch);
    }

    /**
     * Export a proof bundle for an attestation leaf selected by internal leaf ID.
     */
    @Override
    public ProofBundleVO exportByLeafId(Long userId, Long leafId) {
        Long tenantId = TenantContext.requireTenantId();
        if (leafId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "leafId 不能为空");
        }
        AttestationLeaf leaf = leafMapper.selectOne(new LambdaQueryWrapper<AttestationLeaf>()
                .eq(AttestationLeaf::getTenantId, tenantId)
                .eq(AttestationLeaf::getId, leafId)
                .last("LIMIT 1"));
        if (leaf == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "证明叶子不存在");
        }
        File file = loadAuthorizedFile(userId, leaf.getFileId());
        AttestationBatch batch = loadCompletedBatch(file.getTenantId(), leaf.getBatchId());
        return buildBundle(file, leaf, batch);
    }

    /**
     * Load a file and enforce tenant, ownership, and deletion constraints.
     */
    private File loadAuthorizedFile(Long userId, Long fileId) {
        Long tenantId = TenantContext.requireTenantId();
        if (fileId == null) {
            throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "fileId 不能为空");
        }

        File file = fileMapper.selectById(fileId);
        if (file == null || Integer.valueOf(1).equals(file.getDeleted())) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
        }
        if (!Objects.equals(tenantId, file.getTenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不属于当前租户");
        }
        if (!SecurityUtils.isAdmin() && userId != null && !Objects.equals(userId, file.getUid())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权导出此文件证明包");
        }
        if (!StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少哈希，无法导出证明包");
        }
        return file;
    }

    /**
     * Find the newest attestation leaf associated with the file.
     */
    private AttestationLeaf findLatestLeafForFile(File file) {
        AttestationLeaf leaf = leafMapper.selectOne(new LambdaQueryWrapper<AttestationLeaf>()
                .eq(AttestationLeaf::getTenantId, file.getTenantId())
                .eq(AttestationLeaf::getFileId, file.getId())
                .orderByDesc(AttestationLeaf::getCreateTime)
                .orderByDesc(AttestationLeaf::getId)
                .last("LIMIT 1"));
        if (leaf == null) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少批量存证证明");
        }
        return leaf;
    }

    /**
     * Load the completed batch referenced by a leaf.
     */
    private AttestationBatch loadCompletedBatch(Long tenantId, Long batchId) {
        AttestationBatch batch = batchMapper.selectById(batchId);
        if (batch == null || Integer.valueOf(1).equals(batch.getDeleted())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "批量存证记录不存在");
        }
        if (!Objects.equals(tenantId, batch.getTenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "批量存证记录不属于当前租户");
        }
        if (!COMPLETED_STATUS.equals(batch.getStatus())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "批量存证尚未完成");
        }
        return batch;
    }

    /**
     * Build the immutable proof bundle from validated entities.
     */
    private ProofBundleVO buildBundle(File file, AttestationLeaf leaf, AttestationBatch batch) {
        String externalFileId = IdUtils.toExternalId(file.getId());
        String externalLeafId = IdUtils.toExternalId(leaf.getId());
        ProofBundleVO.Manifest manifest = new ProofBundleVO.Manifest(
                BUNDLE_TYPE,
                BUNDLE_VERSION,
                externalFileId,
                externalLeafId,
                batch.getBatchNo()
        );

        return new ProofBundleVO(
                ProofBundleVO.CONTRACT_VERSION,
                manifest,
                buildFileEvidence(file, externalFileId),
                buildStorageEvidence(file),
                buildMerkleEvidence(leaf, batch),
                buildChainEvidence(file, batch),
                buildIssuerEvidence(batch),
                buildVerificationPolicy(),
                verificationGuide()
        );
    }

    /**
     * Build the public file evidence section without exposing encryption secrets.
     */
    private ProofBundleVO.FileEvidence buildFileEvidence(File file, String externalFileId) {
        return new ProofBundleVO.FileEvidence(
                externalFileId,
                file.getFileName(),
                file.getFileHash(),
                file.getTransactionHash(),
                file.getFileSize(),
                file.getContentType(),
                readChunkCount(file.getFileParam()),
                file.getVersion(),
                file.getIsLatest(),
                cloneDate(file.getCreateTime())
        );
    }

    /**
     * Build the storage metadata section from a HEAD lookup.
     */
    private ProofBundleVO.StorageEvidence buildStorageEvidence(File file) {
        String objectPath = buildStoragePath(file);
        Result<StorageObjectHeadVO> result = fileRemoteClient.headObject(objectPath, file.getFileHash());
        if (result == null || !result.isSuccess()) {
            throw new GeneralException(ResultEnum.FILE_SERVICE_ERROR, "存储元数据查询失败");
        }
        StorageObjectHeadVO head = result.getData();
        if (head == null) {
            head = StorageObjectHeadVO.missing(objectPath, file.getFileHash(), file.getTenantId());
        }
        return new ProofBundleVO.StorageEvidence(List.of(toStorageObjectEvidence(file, objectPath, head)));
    }

    /**
     * Convert a storage HEAD response into public proof evidence.
     */
    private ProofBundleVO.StorageObjectEvidence toStorageObjectEvidence(File file, String objectPath, StorageObjectHeadVO head) {
        Boolean tenantMatches = head.tenantId() == null || Objects.equals(file.getTenantId(), head.tenantId());
        Boolean metadataHashMatches = !StringUtils.hasText(head.metadataHash())
                || file.getFileHash().equalsIgnoreCase(head.metadataHash());
        return new ProofBundleVO.StorageObjectEvidence(
                objectPath,
                head.exists(),
                head.nodeName(),
                head.contentLength(),
                head.eTag(),
                head.metadataHash(),
                metadataHashMatches,
                tenantMatches
        );
    }

    /**
     * Build the Merkle proof section from persisted P1-1 leaf metadata.
     */
    private ProofBundleVO.MerkleEvidence buildMerkleEvidence(AttestationLeaf leaf, AttestationBatch batch) {
        return new ProofBundleVO.MerkleEvidence(
                leaf.getProofAlgorithm(),
                batch.getMerkleRoot(),
                leaf.getLeafHash(),
                leaf.getLeafIndex(),
                parseProofPath(leaf.getProofPathJson())
        );
    }

    /**
     * Build chain receipt evidence from persisted file and batch receipts.
     */
    private ProofBundleVO.ChainEvidence buildChainEvidence(File file, AttestationBatch batch) {
        return new ProofBundleVO.ChainEvidence(
                batch.getChainTransactionHash(),
                batch.getChainFileHash(),
                file.getTransactionHash()
        );
    }

    /**
     * Build issuer metadata for the unsigned v1 bundle.
     */
    private ProofBundleVO.IssuerEvidence buildIssuerEvidence(AttestationBatch batch) {
        return new ProofBundleVO.IssuerEvidence(
                PLATFORM,
                ISSUER_CONTRACT,
                batch.getStatus(),
                null,
                null
        );
    }

    /**
     * Build deterministic verifier policy text matching MerkleTreeService.
     */
    private ProofBundleVO.VerificationPolicy buildVerificationPolicy() {
        CryptoSuiteMetadata suiteMetadata = suitePolicy.currentMetadata(null);
        return new ProofBundleVO.VerificationPolicy(
                suiteMetadata.algorithmSuite(),
                suiteMetadata.signatureSuite(),
                suiteMetadata.kemSuite(),
                suiteMetadata.proofSuite(),
                suiteMetadata.keyVersion(),
                suiteMetadata.deprecatedAfterDate(),
                "SHA-256",
                "hex(sha256('leaf\\n' + fileHash.trim()))",
                "hex(sha256('node\\n' + leftHash.trim() + '\\n' + rightHash.trim()))",
                "fileHash ASC, internal fileId ASC at issuance time",
                "duplicate the last leaf hash when a tree level has an odd leaf count",
                "apply each proofPath node from leaf to root; LEFT prepends sibling, RIGHT appends sibling"
        );
    }

    /**
     * Build a stable human-readable verification guide.
     */
    private List<String> verificationGuide() {
        return List.of(
                "Hash the original file content with SHA-256 and compare it to file.fileHash.",
                "Recompute merkle.leafHash from file.fileHash using verificationPolicy.leafHashRule.",
                "Apply merkle.proofPath with verificationPolicy.parentHashRule until the computed value equals merkle.merkleRoot.",
                "Compare merkle.merkleRoot with the batch root recorded by chain.batchTransactionHash or chain.batchChainFileHash.",
                "Inspect storage.objects metadata for object existence and hash or tenant mismatches."
        );
    }

    /**
     * Parse persisted proof path JSON into the bundle node contract.
     */
    private List<ProofBundleVO.ProofNode> parseProofPath(String proofPathJson) {
        if (!StringUtils.hasText(proofPathJson)) {
            return List.of();
        }
        List<ProofBundleVO.ProofNode> nodes = JsonConverter.parse(
                proofPathJson,
                new TypeReference<List<ProofBundleVO.ProofNode>>() {
                }
        );
        return nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * Read safe chunk count metadata while omitting fileParam secrets from the bundle.
     */
    private Integer readChunkCount(String fileParam) {
        if (!StringUtils.hasText(fileParam)) {
            return null;
        }
        Map<String, Object> params;
        try {
            params = JsonConverter.parse(fileParam, new TypeReference<Map<String, Object>>() {
            });
        } catch (GeneralException ex) {
            return null;
        }
        Object chunkCount = params == null ? null : params.get("chunkCount");
        return chunkCount instanceof Number number ? number.intValue() : null;
    }

    /**
     * Build the logical storage path used by integrity and storage HEAD checks.
     */
    private String buildStoragePath(File file) {
        return String.format(S3_PATH_FORMAT, file.getTenantId(), file.getFileHash());
    }

    /**
     * Clone a Date so callers cannot mutate entity state through the bundle.
     */
    private Date cloneDate(Date date) {
        return date == null ? null : new Date(date.getTime());
    }
}
