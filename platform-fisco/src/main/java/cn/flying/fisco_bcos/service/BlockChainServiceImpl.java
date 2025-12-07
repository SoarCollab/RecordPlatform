package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.constants.ContractConstants;
import cn.flying.fisco_bcos.model.bo.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.request.DeleteFilesRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.request.StoreFileRequest;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.*;
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

/**
 * 区块链综合服务实现 v2.0.0
 * 提供文件存证、查询、删除、分享等区块链操作。
 */
@Slf4j
@DubboService(version = BlockChainService.VERSION)
public class BlockChainServiceImpl implements BlockChainService {

    @Resource
    private SharingService sharingService;

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "分享文件")
    public Result<String> shareFiles(ShareFilesRequest request) {
        try {
            List<byte[]> fileHashArr = new ArrayList<>();
            request.getFileHashList().forEach(hash -> fileHashArr.add(Convert.hexTobyte(hash)));
            TransactionResponse response = sharingService.shareFiles(
                    new SharingShareFilesInputBO(request.getUploader(), fileHashArr, request.getMaxAccesses())
            );

            if (response != null) {
                if (response.getReturnCode() == 0) {
                    Object returnValue = response.getReturnObject();
                    if (returnValue instanceof List<?> returnList && !returnList.isEmpty()) {
                        String shareCode = String.valueOf(returnList.getFirst());
                        return Result.success(shareCode);
                    }
                    return Result.error("Invalid return value format");
                } else {
                    return Result.error(response.getReturnMessage());
                }
            }
            return Result.error("NETWORK ERROR");
        } catch (Exception e) {
            log.error("shareFiles error:", e);
            return Result.error(e.getMessage());
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取分享文件")
    public Result<SharingVO> getSharedFiles(String shareCode) {
        try {
            TransactionResponse response = sharingService.getSharedFiles(new SharingGetSharedFilesInputBO(shareCode));
            if (response != null) {
                if (response.getReturnCode() != 0) {
                    return Result.error(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
                }

                if (response.getReturnObject() instanceof List<?> returnList) {
                    if (returnList.size() == 2) {
                        String uploader = String.valueOf(returnList.getFirst());
                        List<String> fileList = new ArrayList<>();

                        if (returnList.getLast() instanceof List<?> files) {
                            for (Object file : files) {
                                if (file instanceof List<?> fileInfo && fileInfo.size() == 6) {
                                    String hexHash = Optional.ofNullable(fileInfo.get(4))
                                            .filter(byte[].class::isInstance)
                                            .map(byte[].class::cast)
                                            .map(Convert::bytesToHex)
                                            .map(hash -> hash.startsWith("0x") ? hash.substring(2) : hash)
                                            .orElse("");
                                    if (!hexHash.isEmpty()) {
                                        fileList.add(hexHash);
                                    }
                                }
                            }
                        }

                        return Result.success(SharingVO.builder()
                                .uploader(uploader)
                                .fileHashList(fileList)
                                .build());
                    }
                    return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
                }
                return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
            }
            return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
        } catch (Exception e) {
            log.error("getSharedFiles error:", e);
            return Result.error(ResultEnum.GET_USER_SHARE_FILE_ERROR, null);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "保存文件")
    public Result<StoreFileResponse> storeFile(StoreFileRequest request) {
        try {
            TransactionResponse response = sharingService.storeFile(
                    new SharingStoreFileInputBO(request.getFileName(), request.getUploader(),
                            request.getContent(), request.getParam()));
            if (response != null) {
                TransactionReceipt receipt = response.getTransactionReceipt();
                if (receipt == null || receipt.getTransactionHash() == null) {
                    return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
                }
                String transactionHash = receipt.getTransactionHash();
                transactionHash = transactionHash.startsWith("0x") ? transactionHash.substring(2) : transactionHash;

                if (response.getReturnCode() == 0) {
                    Object returnValue = response.getReturnObject();
                    if (returnValue instanceof List<?> returnList && !returnList.isEmpty()) {
                        Object firstValue = returnList.getFirst();
                        if (firstValue instanceof byte[]) {
                            String hexHash = Convert.bytesToHex((byte[]) firstValue);
                            hexHash = hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash;

                            return Result.success(StoreFileResponse.builder()
                                    .transactionHash(transactionHash)
                                    .fileHash(hexHash)
                                    .build());
                        }
                    }
                    return Result.error(ResultEnum.INVALID_RETURN_VALUE, null);
                }
            }
            return Result.error(ResultEnum.CONTRACT_ERROR, null);
        } catch (Exception e) {
            log.error("storeFile error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取用户所有文件列表")
    public Result<List<FileVO>> getUserFiles(String uploader) {
        try {
            CallResponse response = sharingService.getUserFiles(new SharingGetUserFilesInputBO(uploader));
            List<FileVO> fileList = new ArrayList<>();

            if (response != null && response.getReturnObject() instanceof List<?> files) {
                Object firstElement = files.getFirst();
                if (firstElement instanceof List<?> filesList) {
                    for (Object file : filesList) {
                        if (file instanceof List<?> fileInfo && fileInfo.size() == 2) {
                            String hexHash = Optional.ofNullable(fileInfo.get(1))
                                    .filter(byte[].class::isInstance)
                                    .map(byte[].class::cast)
                                    .map(Convert::bytesToHex)
                                    .map(hash -> hash.startsWith("0x") ? hash.substring(2) : hash)
                                    .orElse("");
                            if (!hexHash.isEmpty()) {
                                fileList.add(FileVO.builder()
                                        .fileName(String.valueOf(fileInfo.get(0)))
                                        .fileHash(hexHash)
                                        .build());
                            }
                        }
                    }
                }
            }

            return Result.success(fileList);
        } catch (Exception e) {
            log.error("getUserFiles error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "获取单个文件")
    public Result<FileDetailVO> getFile(String uploader, String fileHash) {
        try {
            CallResponse response = sharingService.getFile(new SharingGetFileInputBO(uploader, Convert.hexTobyte(fileHash)));
            if (response != null && response.getReturnObject() instanceof List<?> returnList && !returnList.isEmpty()) {
                Object returnValue = returnList.getFirst();
                if (returnValue instanceof List<?> fileInfo && fileInfo.size() == 6) {
                    long uploadTimeNanos = Long.parseLong(String.valueOf(fileInfo.get(5)));
                    String formattedUploadTime = Convert.timeStampToDate(uploadTimeNanos);

                    FileDetailVO fileDetailVO = FileDetailVO.builder()
                            .uploader(String.valueOf(fileInfo.get(0)))
                            .fileName(String.valueOf(fileInfo.get(1)))
                            .param(String.valueOf(fileInfo.get(2)))
                            .content(String.valueOf(fileInfo.get(3)))
                            .fileHash(fileHash)
                            .uploadTime(formattedUploadTime)
                            .uploadTimestamp(uploadTimeNanos / 1_000_000)
                            .build();
                    return Result.success(fileDetailVO);
                }
            }
            return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
        } catch (Exception e) {
            log.error("getFile error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
        }
    }

    @Override
    @Retry(name = "blockchain")
    @ApiDoc(value = "批量删除文件")
    public Result<Boolean> deleteFiles(DeleteFilesRequest request) {
        try {
            List<byte[]> fileHashArr = new ArrayList<>();
            request.getFileHashList().forEach(hash -> fileHashArr.add(Convert.hexTobyte(hash)));
            TransactionResponse response = sharingService.deleteFiles(
                    new SharingDeleteFilesInputBO(request.getUploader(), fileHashArr));

            if (response != null && response.getReturnCode() == 0) {
                return Result.success(true);
            }
            return Result.error(ResultEnum.GET_USER_FILE_ERROR, null);
        } catch (Exception e) {
            log.error("deleteFiles error:", e);
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR, null);
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
                    if (info.getBlockNumber() != null) {
                        message.setBlockNumber(new java.math.BigInteger(info.getBlockNumber()).longValue());
                    }
                    if (info.getTransactionCount() != null) {
                        message.setTransactionCount(new java.math.BigInteger(info.getTransactionCount()).longValue());
                    }
                    if (info.getFailedTransactionCount() != null) {
                        message.setFailedTransactionCount(new java.math.BigInteger(info.getFailedTransactionCount()).longValue());
                    }
                }
            }
            return Result.success(message);
        } catch (Exception e) {
            log.error("getCurrentBlockChainMessage error:", e);
            return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
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
            log.error("getTransactionByHash error:", e);
            return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
        }
    }
}
