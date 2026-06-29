package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.adapter.BlockChainAdapter;
import cn.flying.fisco_bcos.adapter.model.*;
import cn.flying.fisco_bcos.exception.BlockChainExceptionHandler;
import cn.flying.fisco_bcos.monitor.FiscoMetrics;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.DeleteFilesRequest;
import cn.flying.platformapi.request.GetShareInfoRequest;
import cn.flying.platformapi.request.GetUserShareCodesRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreAttestationBatchRequest;
import cn.flying.platformapi.request.StoreAttestationBatchResponse;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.*;
import cn.flying.platformapi.security.BlockChainRpcAuth;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.apidocs.annotations.ApiDoc;
import org.apache.dubbo.config.annotation.DubboService;
import io.github.resilience4j.retry.annotation.Retry;
import org.apache.dubbo.rpc.RpcContext;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;

/**
 * 区块链综合服务实现 v3.1.0
 * 提供文件存证、查询、删除、分享等区块链操作。
 * 通过 BlockChainAdapter SPI 支持多链切换 (LOCAL_FISCO, BSN_FISCO, BSN_BESU)。
 * 集成 Prometheus 监控指标和统一异常处理。
 *
 * @see BlockChainAdapter
 * @see BlockChainExceptionHandler
 */
@Slf4j
@DubboService(version = BlockChainService.VERSION)
public class BlockChainServiceImpl implements BlockChainService {

    private static final int MAX_EXPIRE_MINUTES = 43_200;

    @Resource
    private BlockChainAdapter chainAdapter;

    @Resource
    private FiscoMetrics fiscoMetrics;

    @Value("${record-platform.rpc.blockchain-token:}")
    private String blockchainRpcToken;

    /**
     * 启动时校验区块链 RPC 共享令牌配置，避免 provider 在未授权状态下对外服务。
     */
    @PostConstruct
    void validateRpcTokenConfiguration() {
        if (!BlockChainRpcAuth.hasToken(blockchainRpcToken)) {
            throw new IllegalStateException(BlockChainRpcAuth.TOKEN_PROPERTY_NAME + " must be configured");
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "分享文件")
    public Result<String> shareFiles(ShareFilesRequest request) {
        requireTrustedRpcCaller();
        Timer.Sample timerSample = fiscoMetrics.startShareTimer();
        try {
            Integer expireMinutes = request != null ? request.expireMinutes() : null;
            if (expireMinutes == null) {
                return new Result<>(
                        ResultEnum.PARAM_IS_INVALID.getCode(),
                        "参数错误：expireMinutes 不能为空，范围 1-43200（分钟）",
                        null
                );
            }
            if (expireMinutes <= 0 || expireMinutes > MAX_EXPIRE_MINUTES) {
                return new Result<>(
                        ResultEnum.PARAM_IS_INVALID.getCode(),
                        "参数错误：expireMinutes 需在 1-43200（分钟）范围内",
                        null
                );
            }

            ChainReceipt receipt = chainAdapter.shareFiles(
                    request.uploader(),
                    request.fileHashList(),
                    expireMinutes
            );

            fiscoMetrics.recordSuccess();
            return Result.success(receipt.getShareCode());

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "shareFiles", ResultEnum.BLOCKCHAIN_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopShareTimer(timerSample);
        }
    }

    /**
     * 获取分享文件信息并返回过期时间（取消分享时 expirationTime 为 -1）
     */
    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取分享文件")
    public Result<SharingVO> getSharedFiles(String shareCode) {
        requireTrustedRpcCaller();
        try {
            ChainShareInfo shareInfo = chainAdapter.getSharedFiles(shareCode);

            return Result.success(new SharingVO(
                    shareInfo.getUploader(),
                    shareInfo.getFileHashList(),
                    null,
                    null,
                    null,
                    shareInfo.getExpireTimestamp(),
                    shareInfo.getIsValid()
            ));

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getSharedFiles", ResultEnum.GET_USER_SHARE_FILE_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "保存文件")
    public Result<StoreFileResponse> storeFile(StoreFileRequest request) {
        requireTrustedRpcCaller();
        Timer.Sample timerSample = fiscoMetrics.startStoreTimer();
        try {
            ChainReceipt receipt = chainAdapter.storeFile(
                    request.uploader(),
                    request.fileName(),
                    request.content(),
                    request.param()
            );

            fiscoMetrics.recordSuccess();
            return Result.success(new StoreFileResponse(
                    receipt.getTransactionHash(),
                    receipt.getFileHash()
            ));

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "storeFile", ResultEnum.CONTRACT_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopStoreTimer(timerSample);
        }
    }

    /**
     * 存储 Merkle 批量存证根，不复用普通文件 storeFile 合约记录。
     */
    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "保存 Merkle 批量存证根")
    public Result<StoreAttestationBatchResponse> storeAttestationBatch(StoreAttestationBatchRequest request) {
        requireTrustedRpcCaller();
        Timer.Sample timerSample = fiscoMetrics.startStoreTimer();
        try {
            if (!isValidAttestationBatchRequest(request)) {
                return new Result<>(
                        ResultEnum.PARAM_IS_INVALID.getCode(),
                        "参数错误：tenantId、batchId、batchNo、proofAlgorithm、merkleRoot、leafCount 均不能为空，merkleRoot 必须为 bytes32 十六进制",
                        null
                );
            }

            ChainReceipt receipt = chainAdapter.storeAttestationBatch(
                    request.tenantId(),
                    request.batchId(),
                    request.batchNo(),
                    request.proofAlgorithm(),
                    request.merkleRoot(),
                    request.leafCount()
            );

            if (receipt == null || !receipt.isSuccess() || !hasText(receipt.getFileHash())) {
                fiscoMetrics.recordFailure();
                return Result.error(ResultEnum.CONTRACT_ERROR, null);
            }

            fiscoMetrics.recordSuccess();
            return Result.success(new StoreAttestationBatchResponse(
                    receipt.getTransactionHash(),
                    receipt.getFileHash()
            ));

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "storeAttestationBatch", ResultEnum.CONTRACT_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopStoreTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取用户所有文件列表")
    public Result<List<FileVO>> getUserFiles(String uploader) {
        requireTrustedRpcCaller();
        try {
            List<ChainFileInfo> chainFiles = chainAdapter.getUserFiles(uploader);

            List<FileVO> fileList = chainFiles.stream()
                    .map(f -> new FileVO(
                            f.getFileName(),
                            f.getFileHash(),
                            null,
                            null,
                            null
                    ))
                    .toList();

            return Result.success(fileList);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getUserFiles", ResultEnum.GET_USER_FILE_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取单个文件")
    public Result<FileDetailVO> getFile(String uploader, String fileHash) {
        requireTrustedRpcCaller();
        Timer.Sample timerSample = fiscoMetrics.startQueryTimer();
        try {
            ChainFileDetail detail = chainAdapter.getFile(uploader, fileHash);

            FileDetailVO fileDetailVO = new FileDetailVO(
                    detail.getUploader(),
                    detail.getFileName(),
                    detail.getParam(),
                    detail.getContent(),
                    detail.getFileHash(),
                    detail.getUploadTimeFormatted(),
                    detail.getUploadTimestamp(),
                    null,
                    null
            );

            return Result.success(fileDetailVO);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getFile", ResultEnum.GET_USER_FILE_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopQueryTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "批量删除文件")
    public Result<Boolean> deleteFiles(DeleteFilesRequest request) {
        requireTrustedRpcCaller();
        Timer.Sample timerSample = fiscoMetrics.startDeleteTimer();
        try {
            ChainReceipt receipt = chainAdapter.deleteFiles(
                    request.uploader(),
                    request.fileHashList()
            );

            if (receipt.isSuccess()) {
                fiscoMetrics.recordSuccess();
                return Result.success(true);
            }

            log.warn("[deleteFiles] 删除失败: {}", receipt.getErrorMessage());
            fiscoMetrics.recordFailure();
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR, null);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "deleteFiles", ResultEnum.DELETE_USER_FILE_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopDeleteTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取当前区块链状态")
    public Result<BlockChainMessage> getCurrentBlockChainMessage() {
        requireTrustedRpcCaller();
        try {
            ChainStatus status = chainAdapter.getChainStatus();

            BlockChainMessage message = new BlockChainMessage(
                    status.getBlockNumber(),
                    status.getTransactionCount(),
                    status.getFailedTransactionCount(),
                    status.getNodeCount(),
                    status.getChainType() != null ? status.getChainType().name() : null
            );

            return Result.success(message);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getCurrentBlockChainMessage");
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "根据交易哈希获取交易详情")
    public Result<TransactionVO> getTransactionByHash(String transactionHash) {
        requireTrustedRpcCaller();
        try {
            ChainTransaction tx = chainAdapter.getTransaction(transactionHash);

            TransactionVO transactionVO = toSafeTransactionVO(tx);

            return Result.success(transactionVO);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getTransactionByHash", ResultEnum.TRANSACTION_NOT_FOUND);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "取消分享")
    public Result<Boolean> cancelShare(CancelShareRequest request) {
        requireTrustedRpcCaller();
        try {
            if (!hasText(request != null ? request.shareCode() : null)
                    || !hasText(request != null ? request.uploader() : null)
                    || !hasText(request != null ? request.requester() : null)) {
                return new Result<>(ResultEnum.PARAM_IS_INVALID, false);
            }
            if (!samePrincipal(request.uploader(), request.requester())) {
                return new Result<>(ResultEnum.PERMISSION_UNAUTHORIZED, false);
            }
            ChainReceipt receipt = chainAdapter.cancelShare(request.shareCode(), request.uploader());

            if (receipt.isSuccess()) {
                fiscoMetrics.recordSuccess();
                return Result.success(true);
            }

            log.warn("[cancelShare] 取消分享失败: {}", receipt.getErrorMessage());
            fiscoMetrics.recordFailure();
            return Result.error(ResultEnum.BLOCKCHAIN_ERROR, false);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "cancelShare", ResultEnum.BLOCKCHAIN_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取用户分享码列表")
    public Result<List<String>> getUserShareCodes(GetUserShareCodesRequest request) {
        requireTrustedRpcCaller();
        try {
            if (!hasText(request != null ? request.uploader() : null)
                    || !hasText(request != null ? request.requester() : null)) {
                return new Result<>(ResultEnum.PARAM_IS_INVALID, List.of());
            }
            if (!samePrincipal(request.uploader(), request.requester())) {
                return new Result<>(ResultEnum.PERMISSION_UNAUTHORIZED, List.of());
            }
            List<String> shareCodes = chainAdapter.getUserShareCodes(request.uploader());
            return Result.success(shareCodes);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getUserShareCodes", ResultEnum.BLOCKCHAIN_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取分享详情")
    public Result<SharingVO> getShareInfo(GetShareInfoRequest request) {
        requireTrustedRpcCaller();
        try {
            if (!hasText(request != null ? request.shareCode() : null)
                    || !hasText(request != null ? request.requester() : null)) {
                return new Result<>(ResultEnum.PARAM_IS_INVALID, null);
            }
            ChainShareInfo shareInfo = chainAdapter.getShareInfo(request.shareCode());
            if (!samePrincipal(shareInfo.getUploader(), request.requester())) {
                return new Result<>(ResultEnum.PERMISSION_UNAUTHORIZED, null);
            }

            return Result.success(new SharingVO(
                    shareInfo.getUploader(),
                    shareInfo.getFileHashList(),
                    request.shareCode(),
                    null,
                    null,
                    shareInfo.getExpireTimestamp(),
                    shareInfo.getIsValid()
            ));

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getShareInfo", ResultEnum.BLOCKCHAIN_ERROR);
        }
    }

    /**
     * 判断两个业务主体是否为同一个上传者。
     */
    private boolean samePrincipal(String uploader, String requester) {
        return hasText(uploader) && uploader.equals(requester);
    }

    /**
     * 判断文本参数是否有效。
     */
    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 校验批量存证根上链请求的必填字段和 bytes32 根格式。
     */
    private boolean isValidAttestationBatchRequest(StoreAttestationBatchRequest request) {
        return request != null
                && request.tenantId() != null
                && request.batchId() != null
                && hasText(request.batchNo())
                && hasText(request.proofAlgorithm())
                && isBytes32Hex(request.merkleRoot())
                && request.leafCount() != null
                && request.leafCount() > 0;
    }

    /**
     * 判断文本是否为 Solidity bytes32 十六进制哈希。
     */
    private boolean isBytes32Hex(String value) {
        if (!hasText(value)) {
            return false;
        }
        String normalized = value.startsWith("0x") || value.startsWith("0X")
                ? value.substring(2)
                : value;
        return normalized.length() == 64 && normalized.matches("[0-9a-fA-F]+");
    }

    /**
     * 将链上交易映射为外部安全视图，避免暴露 ABI、交易输入和签名原文。
     */
    private TransactionVO toSafeTransactionVO(ChainTransaction tx) {
        return new TransactionVO(
                tx.getHash(),
                tx.getChainId(),
                tx.getGroupId(),
                null,
                tx.getFrom(),
                tx.getTo(),
                null,
                null,
                tx.getBlockNumber() != null ? String.valueOf(tx.getBlockNumber()) : null,
                tx.getImportTime()
        );
    }

    /**
     * 校验 Dubbo 调用是否携带后端服务共享令牌。
     */
    private void requireTrustedRpcCaller() {
        String actualToken = RpcContext.getServerAttachment()
                .getAttachment(BlockChainRpcAuth.TOKEN_ATTACHMENT_KEY);
        if (!BlockChainRpcAuth.matches(blockchainRpcToken, actualToken)) {
            throw new SecurityException("Unauthorized blockchain RPC caller");
        }
    }
}
