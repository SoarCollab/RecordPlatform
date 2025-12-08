package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.constants.ContractConstants;
import cn.flying.fisco_bcos.exception.BlockChainExceptionHandler;
import cn.flying.fisco_bcos.model.bo.*;
import cn.flying.fisco_bcos.monitor.FiscoMetrics;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
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
import org.fisco.bcos.sdk.v3.client.protocol.model.JsonTransactionResponse;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import cn.flying.fisco_bcos.utils.Convert;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cn.flying.fisco_bcos.parser.ContractResponseParser.*;

/**
 * 区块链综合服务实现 v2.2.0
 * 提供文件存证、查询、删除、分享等区块链操作。
 * 使用统一的异常处理和响应解析机制。
 * 集成 Prometheus 监控指标。
 */
@Slf4j
@DubboService(version = BlockChainService.VERSION)
public class BlockChainServiceImpl implements BlockChainService {

    private static final String OP_SHARE_FILES = "shareFiles";
    private static final String OP_GET_SHARED_FILES = "getSharedFiles";
    private static final String OP_STORE_FILE = "storeFile";
    private static final String OP_GET_USER_FILES = "getUserFiles";
    private static final String OP_GET_FILE = "getFile";
    private static final String OP_DELETE_FILES = "deleteFiles";
    private static final String OP_GET_BLOCK_INFO = "getCurrentBlockChainMessage";
    private static final String OP_GET_TX = "getTransactionByHash";

    @Resource
    private SharingService sharingService;

    @Resource
    private FiscoMetrics fiscoMetrics;

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "分享文件")
    public Result<String> shareFiles(ShareFilesRequest request) {
        Timer.Sample timerSample = fiscoMetrics.startShareTimer();
        try {
            List<byte[]> fileHashArr = request.getFileHashList().stream()
                    .map(Convert::hexTobyte)
                    .toList();

            TransactionResponse response = sharingService.shareFiles(
                    new SharingShareFilesInputBO(request.getUploader(), fileHashArr, request.getMaxAccesses())
            );

            Result<String> result = parseTransaction(response, returnList ->
                    safeGetString(returnList, 0).orElse(null), OP_SHARE_FILES);

            if (result.isSuccess()) {
                fiscoMetrics.recordSuccess();
            } else {
                fiscoMetrics.recordFailure();
            }
            return result;

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_SHARE_FILES, ResultEnum.BLOCKCHAIN_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopShareTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取分享文件")
    public Result<SharingVO> getSharedFiles(String shareCode) {
        try {
            TransactionResponse response = sharingService.getSharedFiles(
                    new SharingGetSharedFilesInputBO(shareCode));

            if (response == null || response.getReturnCode() != 0) {
                return Result.error(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
            }

            if (!(response.getReturnObject() instanceof List<?> returnList) || returnList.size() != 2) {
                return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
            }

            String uploader = safeGetString(returnList, 0).orElse("");
            List<String> fileList = new ArrayList<>();

            Optional<List<?>> filesOpt = safeGetList(returnList, 1);
            if (filesOpt.isPresent()) {
                for (Object file : filesOpt.get()) {
                    if (file instanceof List<?> fileInfo && validateSize(fileInfo, 6)) {
                        extractFileHash(fileInfo, 4).ifPresent(fileList::add);
                    }
                }
            }

            return Result.success(SharingVO.builder()
                    .uploader(uploader)
                    .fileHashList(fileList)
                    .build());

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_GET_SHARED_FILES, ResultEnum.GET_USER_SHARE_FILE_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "保存文件")
    public Result<StoreFileResponse> storeFile(StoreFileRequest request) {
        Timer.Sample timerSample = fiscoMetrics.startStoreTimer();
        try {
            TransactionResponse response = sharingService.storeFile(
                    new SharingStoreFileInputBO(request.getFileName(), request.getUploader(),
                            request.getContent(), request.getParam()));

            if (response == null) {
                fiscoMetrics.recordFailure();
                return Result.error(ResultEnum.CONTRACT_ERROR, null);
            }

            TransactionReceipt receipt = response.getTransactionReceipt();
            if (receipt == null || receipt.getTransactionHash() == null) {
                fiscoMetrics.recordFailure();
                return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
            }

            String transactionHash = normalizeHash(receipt.getTransactionHash());

            if (response.getReturnCode() != 0) {
                log.warn("[{}] 合约返回错误: {}", OP_STORE_FILE, response.getReturnMessage());
                fiscoMetrics.recordFailure();
                return Result.error(ResultEnum.CONTRACT_ERROR, null);
            }

            if (!(response.getReturnObject() instanceof List<?> returnList) || returnList.isEmpty()) {
                fiscoMetrics.recordFailure();
                return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
            }

            Optional<String> fileHashOpt = safeGet(returnList, 0, byte[].class)
                    .map(Convert::bytesToHex)
                    .map(this::normalizeHash);

            if (fileHashOpt.isEmpty()) {
                fiscoMetrics.recordFailure();
                return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
            }

            fiscoMetrics.recordSuccess();
            return Result.success(StoreFileResponse.builder()
                    .transactionHash(transactionHash)
                    .fileHash(fileHashOpt.get())
                    .build());

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_STORE_FILE, ResultEnum.GET_USER_FILE_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopStoreTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取用户所有文件列表")
    public Result<List<FileVO>> getUserFiles(String uploader) {
        try {
            CallResponse response = sharingService.getUserFiles(new SharingGetUserFilesInputBO(uploader));
            List<FileVO> fileList = new ArrayList<>();

            if (response == null || !(response.getReturnObject() instanceof List<?> files) || files.isEmpty()) {
                return Result.success(fileList);
            }

            Object firstElement = files.getFirst();
            if (!(firstElement instanceof List<?> filesList)) {
                return Result.success(fileList);
            }

            for (Object file : filesList) {
                if (file instanceof List<?> fileInfo && validateSize(fileInfo, 2)) {
                    String fileName = safeGetString(fileInfo, 0).orElse("");
                    Optional<String> hashOpt = extractFileHash(fileInfo, 1);

                    if (hashOpt.isPresent() && !hashOpt.get().isEmpty()) {
                        fileList.add(FileVO.builder()
                                .fileName(fileName)
                                .fileHash(hashOpt.get())
                                .build());
                    }
                }
            }

            return Result.success(fileList);
        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_GET_USER_FILES, ResultEnum.GET_USER_FILE_ERROR);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取单个文件")
    public Result<FileDetailVO> getFile(String uploader, String fileHash) {
        Timer.Sample timerSample = fiscoMetrics.startQueryTimer();
        try {
            CallResponse response = sharingService.getFile(
                    new SharingGetFileInputBO(uploader, Convert.hexTobyte(fileHash)));

            if (response == null || !(response.getReturnObject() instanceof List<?> returnList) || returnList.isEmpty()) {
                return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
            }

            Object returnValue = returnList.getFirst();
            if (!(returnValue instanceof List<?> fileInfo) || !validateSize(fileInfo, 6)) {
                return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
            }

            long uploadTimeNanos = parseLong(fileInfo.get(5));
            String formattedUploadTime = Convert.timeStampToDate(uploadTimeNanos);

            FileDetailVO fileDetailVO = FileDetailVO.builder()
                    .uploader(safeGetString(fileInfo, 0).orElse(""))
                    .fileName(safeGetString(fileInfo, 1).orElse(""))
                    .param(safeGetString(fileInfo, 2).orElse(""))
                    .content(safeGetString(fileInfo, 3).orElse(""))
                    .fileHash(fileHash)
                    .uploadTime(formattedUploadTime)
                    .uploadTimestamp(uploadTimeNanos / 1_000_000)
                    .build();

            return Result.success(fileDetailVO);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_GET_FILE, ResultEnum.GET_USER_FILE_ERROR, fiscoMetrics);
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
            List<byte[]> fileHashArr = request.getFileHashList().stream()
                    .map(Convert::hexTobyte)
                    .toList();

            TransactionResponse response = sharingService.deleteFiles(
                    new SharingDeleteFilesInputBO(request.getUploader(), fileHashArr));

            if (response != null && response.getReturnCode() == 0) {
                fiscoMetrics.recordSuccess();
                return Result.success(true);
            }

            log.warn("[{}] 删除失败: response={}", OP_DELETE_FILES,
                    response != null ? response.getReturnMessage() : "null");
            fiscoMetrics.recordFailure();
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR, null);

        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_DELETE_FILES, ResultEnum.DELETE_USER_FILE_ERROR, fiscoMetrics);
        } finally {
            fiscoMetrics.stopDeleteTimer(timerSample);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取当前区块链状态")
    public Result<BlockChainMessage> getCurrentBlockChainMessage() {
        try {
            TotalTransactionCount totalTransactionCount = sharingService.getCurrentBlockChainMessage();
            BlockChainMessage message = new BlockChainMessage();

            if (totalTransactionCount != null) {
                TotalTransactionCount.TransactionCountInfo info = totalTransactionCount.getTotalTransactionCount();
                if (info != null) {
                    message.setBlockNumber(parseHexLong(info.getBlockNumber()));
                    message.setTransactionCount(parseHexLong(info.getTransactionCount()));
                    message.setFailedTransactionCount(parseHexLong(info.getFailedTransactionCount()));
                }
            }

            return Result.success(message);
        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_GET_BLOCK_INFO);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "根据交易哈希获取交易详情")
    public Result<TransactionVO> getTransactionByHash(String transactionHash) {
        try {
            BcosTransaction transaction = sharingService.getTransactionByHash(transactionHash);
            if (transaction == null || transaction.getResult() == null) {
                return Result.error(ResultEnum.TRANSACTION_NOT_FOUND, null);
            }

            BcosTransactionReceipt receipt = sharingService.getTransactionReceipt(transactionHash);
            if (receipt == null || receipt.getResult() == null) {
                return Result.error(ResultEnum.TRANSACTION_RECEIPT_NOT_FOUND, null);
            }

            JsonTransactionResponse result = transaction.getResult();
            result.setAbi(ContractConstants.SharingAbi);

            TransactionVO transactionVO = new TransactionVO(
                    result.getHash(),
                    result.getChainID(),
                    result.getGroupID(),
                    result.getAbi(),
                    result.getFrom(),
                    result.getTo(),
                    result.getInput(),
                    result.getSignature(),
                    result.getImportTime()
            );

            return Result.success(transactionVO);
        } catch (Exception e) {
            return BlockChainExceptionHandler.handle(e, OP_GET_TX);
        }
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 从字节数组提取并规范化文件哈希
     */
    private Optional<String> extractFileHash(List<?> list, int index) {
        return safeGet(list, index, byte[].class)
                .map(Convert::bytesToHex)
                .map(this::normalizeHash);
    }

    /**
     * 规范化哈希值（去除 0x 前缀）
     */
    private String normalizeHash(String hash) {
        if (hash == null) return "";
        return hash.startsWith("0x") || hash.startsWith("0X") ? hash.substring(2) : hash;
    }

    /**
     * 安全解析 Long
     */
    private long parseLong(Object value) {
        if (value == null) return 0L;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 安全解析十六进制 Long
     */
    private long parseHexLong(String hexValue) {
        if (hexValue == null || hexValue.isEmpty()) return 0L;
        try {
            return new java.math.BigInteger(hexValue).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
