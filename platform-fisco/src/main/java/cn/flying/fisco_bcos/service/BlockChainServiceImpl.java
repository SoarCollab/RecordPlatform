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
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.*;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.apidocs.annotations.ApiDoc;
import org.apache.dubbo.config.annotation.DubboService;
import io.github.resilience4j.retry.annotation.Retry;

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

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "分享文件")
    public Result<String> shareFiles(ShareFilesRequest request) {
        Timer.Sample timerSample = fiscoMetrics.startShareTimer();
        try {
            Integer expireMinutes = request != null ? request.getExpireMinutes() : null;
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
                    request.getUploader(),
                    request.getFileHashList(),
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
        try {
            ChainShareInfo shareInfo = chainAdapter.getSharedFiles(shareCode);

            return Result.success(SharingVO.builder()
                    .uploader(shareInfo.getUploader())
                    .fileHashList(shareInfo.getFileHashList())
                    .expirationTime(shareInfo.getExpireTimestamp())
                    .isValid(shareInfo.getIsValid())
                    .build());

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getSharedFiles", ResultEnum.GET_USER_SHARE_FILE_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "保存文件")
    public Result<StoreFileResponse> storeFile(StoreFileRequest request) {
        Timer.Sample timerSample = fiscoMetrics.startStoreTimer();
        try {
            ChainReceipt receipt = chainAdapter.storeFile(
                    request.getUploader(),
                    request.getFileName(),
                    request.getContent(),
                    request.getParam()
            );

            fiscoMetrics.recordSuccess();
            return Result.success(StoreFileResponse.builder()
                    .transactionHash(receipt.getTransactionHash())
                    .fileHash(receipt.getFileHash())
                    .build());

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "storeFile", ResultEnum.CONTRACT_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopStoreTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取用户所有文件列表")
    public Result<List<FileVO>> getUserFiles(String uploader) {
        try {
            List<ChainFileInfo> chainFiles = chainAdapter.getUserFiles(uploader);

            List<FileVO> fileList = chainFiles.stream()
                    .map(f -> FileVO.builder()
                            .fileName(f.getFileName())
                            .fileHash(f.getFileHash())
                            .build())
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
        Timer.Sample timerSample = fiscoMetrics.startQueryTimer();
        try {
            ChainFileDetail detail = chainAdapter.getFile(uploader, fileHash);

            FileDetailVO fileDetailVO = FileDetailVO.builder()
                    .uploader(detail.getUploader())
                    .fileName(detail.getFileName())
                    .param(detail.getParam())
                    .content(detail.getContent())
                    .fileHash(detail.getFileHash())
                    .uploadTime(detail.getUploadTimeFormatted())
                    .uploadTimestamp(detail.getUploadTimestamp())
                    .build();

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
        Timer.Sample timerSample = fiscoMetrics.startDeleteTimer();
        try {
            ChainReceipt receipt = chainAdapter.deleteFiles(
                    request.getUploader(),
                    request.getFileHashList()
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
        try {
            ChainStatus status = chainAdapter.getChainStatus();

            BlockChainMessage message = new BlockChainMessage();
            message.setBlockNumber(status.getBlockNumber());
            message.setTransactionCount(status.getTransactionCount());
            message.setFailedTransactionCount(status.getFailedTransactionCount());

            return Result.success(message);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getCurrentBlockChainMessage");
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "根据交易哈希获取交易详情")
    public Result<TransactionVO> getTransactionByHash(String transactionHash) {
        try {
            ChainTransaction tx = chainAdapter.getTransaction(transactionHash);

            TransactionVO transactionVO = new TransactionVO(
                    tx.getHash(),
                    tx.getChainId(),
                    tx.getGroupId(),
                    tx.getAbi(),
                    tx.getFrom(),
                    tx.getTo(),
                    tx.getInput(),
                    tx.getSignature(),
                    tx.getBlockNumber() != null ? String.valueOf(tx.getBlockNumber()) : null,
                    tx.getImportTime()
            );

            return Result.success(transactionVO);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getTransactionByHash", ResultEnum.TRANSACTION_NOT_FOUND);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "取消分享")
    public Result<Boolean> cancelShare(CancelShareRequest request) {
        try {
            ChainReceipt receipt = chainAdapter.cancelShare(request.getShareCode());

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
    public Result<List<String>> getUserShareCodes(String uploader) {
        try {
            List<String> shareCodes = chainAdapter.getUserShareCodes(uploader);
            return Result.success(shareCodes);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getUserShareCodes", ResultEnum.BLOCKCHAIN_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取分享详情")
    public Result<SharingVO> getShareInfo(String shareCode) {
        try {
            ChainShareInfo shareInfo = chainAdapter.getShareInfo(shareCode);

            return Result.success(SharingVO.builder()
                    .uploader(shareInfo.getUploader())
                    .fileHashList(shareInfo.getFileHashList())
                    .shareCode(shareCode)
                    .expirationTime(shareInfo.getExpireTimestamp())
                    .isValid(shareInfo.getIsValid())
                    .build());

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, "getShareInfo", ResultEnum.BLOCKCHAIN_ERROR);
        }
    }
}
