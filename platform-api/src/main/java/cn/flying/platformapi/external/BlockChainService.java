package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.*;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 区块链调用接口
 * @author: flyingcoding
 * @create: 2025-03-10 09:40
 */
@DubboService
public interface BlockChainService {
    Result<String> storeFile(String uploader, String fileName, String param, String content);
    Result<List<FileVO>> getUserFiles(String uploader);
    Result<FileDetailVO> getFile(String uploader, String fileHash);
    Result<Boolean> deleteFiles(String uploader, List<String> fileHashList);
    Result<Boolean> deleteFile(String uploader, String fileHash);
    Result<String> shareFiles(String uploader, List<String> fileHash, Integer maxAccesses);
    Result<SharingVO> getSharedFiles(String shareCode);
    Result<BlockChainMessage> getCurrentBlockChainMessage();
    Result<TransactionVO> getTransactionByHash(String transactionHash);
}
