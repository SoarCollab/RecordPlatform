package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;

import java.util.List;
import java.util.Map;

/**
 * 分布式存储接口 v1.0.0
 * 提供文件分片存储、下载、删除等操作。
 */
public interface DistributedStorageService {

    /** Dubbo 服务版本号 */
    String VERSION = "1.0.0";

    Result<List<byte[]>> getFileListByHash(List<String> filePathList, List<String> fileHashList);
    Result<List<String>> getFileUrlListByHash(List<String> filePathList, List<String> fileHashList);

    @Deprecated
    Result<Map<String, String>> storeFile(List<byte[]> fileList, List<String> FileHashList);

    Result<String> storeFileChunk(byte[] fileData, String fileHash);

    Result<Boolean> deleteFile(Map<String, String> fileContent);

    /**
     * Get cluster health status: node name -> online status
     */
    Result<Map<String, Boolean>> getClusterHealth();

}

