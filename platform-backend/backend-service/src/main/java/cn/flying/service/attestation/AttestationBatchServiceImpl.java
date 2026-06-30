package cn.flying.service.attestation;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SnowflakeIdGenerator;
import cn.flying.dao.dto.File;
import cn.flying.dao.entity.AttestationBatch;
import cn.flying.dao.entity.AttestationLeaf;
import cn.flying.dao.mapper.AttestationBatchMapper;
import cn.flying.dao.mapper.AttestationLeafMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.response.StoreAttestationBatchResponse;
import cn.flying.service.remote.FileRemoteClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service implementation for creating persisted Merkle attestation batches.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttestationBatchServiceImpl implements AttestationBatchService {

    private static final String STATUS_CHAIN_PENDING = "CHAIN_PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private final FileMapper fileMapper;
    private final AttestationBatchMapper batchMapper;
    private final AttestationLeafMapper leafMapper;
    private final MerkleTreeService merkleTreeService;
    private final FileRemoteClient fileRemoteClient;
    private final SnowflakeIdGenerator snowflakeIdGenerator;

    /**
     * Creates a persisted Merkle batch and records its root through the blockchain RemoteClient boundary.
     */
    @Override
    @Transactional
    public AttestationBatch createBatch(Long userId, List<Long> fileIds) {
        Long tenantId = TenantContext.requireTenantId();
        List<Long> normalizedFileIds = normalizeFileIds(fileIds);
        List<File> files = loadAndValidateFiles(userId, tenantId, normalizedFileIds);
        MerkleTreeResult tree = merkleTreeService.buildTree(toMerkleInputs(files));

        AttestationBatch batch = insertBatch(tenantId, tree);
        insertLeaves(tenantId, batch.getId(), tree);
        StoreAttestationBatchResponse chainResponse = storeBatchRootOnChain(tenantId, batch, tree);

        batch.setStatus(STATUS_COMPLETED)
                .setChainTransactionHash(chainResponse.transactionHash())
                .setChainFileHash(chainResponse.batchRootHash())
                .setChainError(null);
        batchMapper.updateById(batch);

        log.info("Created Merkle attestation batch: tenantId={}, batchId={}, leafCount={}, root={}",
                tenantId, batch.getId(), batch.getLeafCount(), batch.getMerkleRoot());
        return batch;
    }

    private List<Long> normalizeFileIds(List<Long> fileIds) {
        if (CollectionUtils.isEmpty(fileIds)) {
            throw new GeneralException(ResultEnum.PARAM_IS_BLANK, "fileIds 不能为空");
        }
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (Long fileId : fileIds) {
            if (fileId == null) {
                throw new GeneralException(ResultEnum.PARAM_IS_INVALID, "fileId 不能为空");
            }
            distinctIds.add(fileId);
        }
        return List.copyOf(distinctIds);
    }

    private List<File> loadAndValidateFiles(Long userId, Long tenantId, List<Long> fileIds) {
        List<File> files = fileMapper.selectBatchIds(fileIds);
        if (files == null || files.size() != fileIds.size()) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST, "部分文件不存在或不属于当前租户");
        }

        Map<Long, File> filesById = files.stream()
                .collect(Collectors.toMap(File::getId, Function.identity()));
        List<File> orderedFiles = new ArrayList<>(fileIds.size());
        for (Long fileId : fileIds) {
            File file = filesById.get(fileId);
            validateFile(userId, tenantId, fileId, file);
            orderedFiles.add(file);
        }
        return orderedFiles;
    }

    private void validateFile(Long userId, Long tenantId, Long fileId, File file) {
        if (file == null) {
            throw new GeneralException(ResultEnum.FILE_NOT_EXIST, "文件不存在: " + fileId);
        }
        if (file.getTenantId() != null && !tenantId.equals(file.getTenantId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不属于当前租户: " + fileId);
        }
        if (userId != null && file.getUid() != null && !userId.equals(file.getUid())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不属于当前用户: " + fileId);
        }
        if (!Integer.valueOf(FileUploadStatus.SUCCESS.getCode()).equals(file.getStatus())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "仅上传成功的文件可创建批量存证: " + fileId);
        }
        if (!StringUtils.hasText(file.getFileHash())) {
            throw new GeneralException(ResultEnum.FILE_RECORD_ERROR, "文件缺少链上哈希: " + fileId);
        }
    }

    private List<MerkleLeafInput> toMerkleInputs(List<File> files) {
        return files.stream()
                .map(file -> new MerkleLeafInput(file.getId(), file.getFileHash()))
                .toList();
    }

    private AttestationBatch insertBatch(Long tenantId, MerkleTreeResult tree) {
        Long batchId = snowflakeIdGenerator.nextId();
        AttestationBatch batch = new AttestationBatch()
                .setId(batchId)
                .setTenantId(tenantId)
                .setBatchNo("MB-" + batchId)
                .setMerkleRoot(tree.merkleRoot())
                .setProofAlgorithm(tree.proofAlgorithm())
                .setLeafCount(tree.leaves().size())
                .setStatus(STATUS_CHAIN_PENDING)
                .setDeleted(0);
        batchMapper.insert(batch);
        return batch;
    }

    private void insertLeaves(Long tenantId, Long batchId, MerkleTreeResult tree) {
        for (MerkleLeafProof proof : tree.leaves()) {
            AttestationLeaf leaf = new AttestationLeaf()
                    .setId(snowflakeIdGenerator.nextId())
                    .setTenantId(tenantId)
                    .setBatchId(batchId)
                    .setFileId(proof.fileId())
                    .setFileHash(proof.fileHash())
                    .setLeafHash(proof.leafHash())
                    .setLeafIndex(proof.leafIndex())
                    .setProofPathJson(JsonConverter.toJson(proof.proofPath()))
                    .setProofAlgorithm(tree.proofAlgorithm())
                    .setDeleted(0);
            leafMapper.insert(leaf);
        }
    }

    private StoreAttestationBatchResponse storeBatchRootOnChain(Long tenantId, AttestationBatch batch, MerkleTreeResult tree) {
        Result<StoreAttestationBatchResponse> result = fileRemoteClient.storeAttestationBatch(
                new StoreAttestationBatchRequest(
                        tenantId,
                        batch.getId(),
                        batch.getBatchNo(),
                        tree.proofAlgorithm(),
                        tree.merkleRoot(),
                        tree.leaves().size()
                ));
        StoreAttestationBatchResponse response = ResultUtils.getData(result);
        if (response == null
                || !StringUtils.hasText(response.transactionHash())
                || !StringUtils.hasText(response.batchRootHash())) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "批量存证根上链失败");
        }
        return response;
    }
}
