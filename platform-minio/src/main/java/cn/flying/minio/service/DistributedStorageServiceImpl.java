package cn.flying.minio.service;

import cn.flying.platformapi.external.DistributedStorageService;
import org.apache.dubbo.config.annotation.DubboService;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * @program: RecordPlatform
 * @description: 分布式存储实现类（MinIO）
 * @author: flyingcoding
 * @create: 2025-04-07 00:07
 */
@DubboService
public class DistributedStorageServiceImpl implements DistributedStorageService {
    @Override
    public List<File> getFileListByHash(List<String> filePathList, List<String> fileHashList) {
        return List.of();
    }

    @Override
    public List<String> getFileUrlListByHash(List<String> filePathList, List<String> fileHashList) {
        return List.of();
    }

    @Override
    public Map<String, String> storeFile(List<File> fileList, List<String> FileHashList) {
        return Map.of();
    }
}
