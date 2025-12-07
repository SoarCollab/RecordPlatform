package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

/**
 * @program: RecordPlatform
 * @description: 分布式存储接口
 * @author: flyingcoding
 * @create: 2025-04-06 12:46
 */
@DubboService
public interface DistributedStorageService {

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

