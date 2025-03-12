package cn.flying.backendapi.external;

import cn.flying.backendapi.response.FileDetailVO;
import cn.flying.backendapi.response.FileVO;
import cn.flying.backendapi.response.SharingVO;
import cn.flying.common.constant.Result;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 区块链调用接口
 * @author: flyingcoding
 * @create: 2025-03-10 09:40
 */

public interface BlockChainService {
    Result<String> storeFile(String uploader, String fileName, String param, String content);
    Result<List<FileVO>> getUserFiles(String uploader);
    Result<FileDetailVO> getFile(String uploader, String fileHash);
    Result<Boolean> deleteFiles(String uploader, List<String> fileHashList);
    Result<Boolean> deleteFile(String uploader, String fileHash);
    Result<String> shareFiles(String uploader, List<String> fileHash, Integer maxAccesses);
    Result<SharingVO> getSharedFiles(String shareCode);
}
