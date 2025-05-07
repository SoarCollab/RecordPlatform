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
    Result<Map<String, String>> storeFile(List<byte[]> fileList, List<String> FileHashList);
    Result<Boolean> deleteFile(Map<String, String> fileContent);

}

