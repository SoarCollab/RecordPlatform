package cn.flying.fisco_bcos.service;

import cn.flying.fisco_bcos.model.bo.*;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.constant.ResultEnum;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.response.*;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.apidocs.annotations.ApiDoc;
import org.apache.dubbo.config.annotation.DubboService;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.BcosTransactionReceipt;
import org.fisco.bcos.sdk.v3.client.protocol.response.TotalTransactionCount;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;
import cn.flying.fisco_bcos.utils.Convert;
import org.springframework.beans.BeanUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * @program: RecordPlatform
 * @description: 区块链综合接口
 * @author: 王贝强
 * @create: 2025-03-19 21:30
 */
@Slf4j
@DubboService
public class BlockChainServiceImpl implements BlockChainService{

    @Resource
    private SharingService sharingService;

    @ApiDoc(value = "分享文件")
    public Result<String> shareFiles(String uploader, List<String> fileHash, Integer maxAccesses) {
        try {
            List<byte[]> fileHashArr = new ArrayList<>();
            fileHash.forEach(hash -> fileHashArr.add(Convert.hexTobyte(hash)));
            TransactionResponse response = sharingService.shareFiles(
                    new SharingShareFilesInputBO(uploader, fileHashArr, maxAccesses)
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

    @ApiDoc(value = "获取分享文件")
    public Result<SharingVO> getSharedFiles(String shareCode) {
        try {
            TransactionResponse response = sharingService.getSharedFiles(new SharingGetSharedFilesInputBO(shareCode));
            if (response != null) {
                // 检查合约执行是否成功
                if (response.getReturnCode() != 0) {
                    // 返回合约的错误信息
                    return Result.error(ResultEnum.FAIL,null);
                }

                if (response.getReturnObject() instanceof List<?> returnList) {
                    if (returnList.size() == 2) {
                        String uploader = String.valueOf(returnList.getFirst());
                        List<FileSharingVO> fileList = new ArrayList<>();

                        // 处理文件列表
                        if (returnList.getLast() instanceof List<?> files) {
                            for (Object file : files) {
                                if (file instanceof List<?> fileInfo && fileInfo.size() == 6) {
                                    fileList.add(new FileSharingVO(
                                            // fileName
                                            String.valueOf(fileInfo.getFirst()),
                                            // fileParam
                                            String.valueOf(fileInfo.get(3)),
                                            // fileContent
                                            String.valueOf(fileInfo.get(2)),
                                            // fileUploadTime
                                            Convert.timeStampToDate(Long.parseLong(String.valueOf(fileInfo.getLast())))
                                    ));
                                }
                            }
                        }

                        return Result.success(new SharingVO(
                                uploader,
                                fileList
                        ));
                    }
                    return Result.error(ResultEnum.INVALID_RETURN_VALUE,null);
                }
                return Result.error(ResultEnum.INVALID_RETURN_VALUE,null);
            }
            return Result.error(ResultEnum.INVALID_RETURN_VALUE,null);
        } catch (Exception e) {
            log.error("getSharedFiles error:", e);
            return Result.error(ResultEnum.GET_USER_SHARE_FILE_ERROR,null);
        }
    }

    @ApiDoc(value = "保存文件")
    public Result<String> storeFile(String uploader, String fileName, String param, String content) {
        try {
            TransactionResponse response = sharingService.storeFile(new SharingStoreFileInputBO(fileName, uploader, content, param));
            if (response != null) {
                if (response.getReturnCode() == 0) {
                    Object returnValue = response.getReturnObject();
                    if (returnValue instanceof List<?> returnList && !returnList.isEmpty()) {
                        Object firstValue = returnList.getFirst();
                        if (firstValue instanceof byte[]) {
                            String hexHash = Convert.bytesToHex((byte[]) firstValue);
                            return Result.success(hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash);
                        }
                    }
                    return Result.error(ResultEnum.INVALID_RETURN_VALUE);
                }
            }
            return Result.error(ResultEnum.CONTRACT_ERROR);
        } catch (Exception e) {
            log.error("storeFile error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR);
        }
    }

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
                            String hexHash = Convert.bytesToHex((byte[]) fileInfo.get(1));
                            hexHash = hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash;
                            fileList.add(new FileVO(
                                    String.valueOf(fileInfo.get(0)),
                                    hexHash
                            ));
                        }
                    }
                }
            }

            return Result.success(fileList);
        } catch (Exception e) {
            log.error("getUserFiles error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        }
    }

    @ApiDoc(value = "获取单个文件")
    public Result<FileDetailVO> getFile(String uploader, String fileHash) {
        try {
            CallResponse response = sharingService.getFile(new SharingGetFileInputBO(uploader, Convert.hexTobyte(fileHash)));
            if (response != null && response.getReturnObject() instanceof List<?> returnList && !returnList.isEmpty()) {
                Object returnValue = returnList.getFirst();
                if (returnValue instanceof List<?> fileInfo && fileInfo.size() == 6) {
//                    String hexHash = Convert.bytesToHex((byte[]) fileInfo.get(4));
//                    hexHash = hexHash.startsWith("0x") ? hexHash.substring(2) : hexHash;

                    // 获取时间戳
                    long uploadTimeNanos = Long.parseLong(String.valueOf(fileInfo.get(5)));
                    String formattedUploadTime = Convert.timeStampToDate(uploadTimeNanos);

                    return Result.success(new FileDetailVO(
                            // fileName
                            String.valueOf(fileInfo.get(1)),
                            // uploader
                            String.valueOf(fileInfo.get(0)),
                            // content
                            String.valueOf(fileInfo.get(3)),
                            // param
                            String.valueOf(fileInfo.get(2)),
                            // fileHash
//                          hexHash,
                            // uploadTime
                            formattedUploadTime
                    ));
                }
            }
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        } catch (Exception e) {
            log.error("getFile error:", e);
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        }
    }

    @ApiDoc(value = "删除多个文件")
    public Result<Boolean> deleteFiles(String uploader, List<String> fileHashList) {
        try {
            List<byte[]> fileHashArr = new ArrayList<>();
            fileHashList.forEach(hash -> fileHashArr.add(Convert.hexTobyte(hash)));
            TransactionResponse response = sharingService.deleteFiles(new SharingDeleteFilesInputBO(uploader, fileHashArr));

            if (response != null && response.getReturnCode() == 0) {
                return Result.success(true);
            }
            return Result.error(ResultEnum.GET_USER_FILE_ERROR,null);
        } catch (Exception e) {
            log.error("deleteFiles error:", e);
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR,null);
        }
    }

    @ApiDoc(value = "删除文件")
    public Result<Boolean> deleteFile(String uploader, String fileHash) {
        try {
            TransactionResponse response =  sharingService.deleteFile(new SharingDeleteFileInputBO(uploader, Convert.hexTobyte(fileHash)));
            if (response != null && response.getReturnCode() == 0) {
                return Result.success(true);
            }
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR,null);
        } catch (Exception e) {
            log.error("deleteFiles error:", e);
            return Result.error(ResultEnum.DELETE_USER_FILE_ERROR,null);
        }
    }

    @ApiDoc(value = "获取当前区块链状态")
    public Result<BlockChainMessage> getCurrentBlockChainMessage() {
        try {
            TotalTransactionCount totalTransactionCount = sharingService.getCurrentBlockChainMessage();
            BlockChainMessage message=new BlockChainMessage();
            BeanUtils.copyProperties(totalTransactionCount,message);
            return Result.success(message);
        }catch (Exception e){
            log.error("getCurrentBlockChainMessage error:", e);
            return Result.error(ResultEnum.BLOCKCHAIN_ERROR,null);
        }
    }
    
    @ApiDoc(value = "根据交易哈希获取交易详情")
    public Result<TransactionVO> getTransactionByHash(String transactionHash) {
        try {
            // 获取交易信息
            BcosTransaction transaction = sharingService.getTransactionByHash(transactionHash);
            if (transaction == null || transaction.getResult() == null) {
                return Result.error(ResultEnum.TRANSACTION_NOT_FOUND, null);
            }
            
            // 获取交易回执
            BcosTransactionReceipt receipt = sharingService.getTransactionReceipt(transactionHash);
            if (receipt == null || receipt.getResult() == null) {
                return Result.error(ResultEnum.TRANSACTION_RECEIPT_NOT_FOUND, null);
            }
            
            // 解析交易信息
            Object result = transaction.getResult();
            Map<String, Object> txResponse;
            if (result instanceof Map) {
                txResponse = (Map<String, Object>) result;
            } else {
                log.error("交易结果格式不符合预期: {}", result);
                return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
            }
            TransactionReceipt txReceipt = receipt.getResult();
            
            // 构建响应VO对象
            TransactionVO transactionVO = new TransactionVO(
                    (String) txResponse.get("hash"),
                    (String) txResponse.get("blockHash"),
                    (String) txResponse.get("blockNumber"),
                    (String) txResponse.get("from"),
                    (String) txResponse.get("to"),
                    (String) txResponse.get("input"),
                    txReceipt.getOutput(),
                    Long.valueOf(txResponse.get("gas").toString()),
                    Convert.timeStampToDate(Long.parseLong(txResponse.get("blockTimestamp").toString()))
            );
            
            return Result.success(transactionVO);
        } catch (Exception e) {
            log.error("getTransactionByHash error:", e);
            return Result.error(ResultEnum.BLOCKCHAIN_ERROR, null);
        }
    }
}
