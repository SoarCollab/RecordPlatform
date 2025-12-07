package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.*;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 区块链调用接口 v1.0.0
 * 提供文件存证、查询、删除、分享等区块链操作。
 */
public interface BlockChainService {

    /** Dubbo 服务版本号 */
    String VERSION = "1.0.0";

    Result<List<String>> storeFile(String uploader, String fileName, String param, String content);
    Result<List<FileVO>> getUserFiles(String uploader);
    Result<FileDetailVO> getFile(String uploader, String fileHash);
    Result<Boolean> deleteFiles(String uploader, List<String> fileHashList);
    Result<Boolean> deleteFile(String uploader, String fileHash);
    Result<String> shareFiles(String uploader, List<String> fileHash, Integer maxAccesses);
    Result<SharingVO> getSharedFiles(String shareCode);
    Result<BlockChainMessage> getCurrentBlockChainMessage();
    Result<TransactionVO> getTransactionByHash(String transactionHash);
}
