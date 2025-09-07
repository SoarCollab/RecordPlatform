package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;
import java.util.Map;

/**
 * 分布式存储服务接口
 * 提供基于 MinIO 的分布式文件存储功能
 * 支持文件的存储、获取、删除等操作，与区块链服务配合实现完整的存证系统
 * 
 * @program: RecordPlatform
 * @description: 分布式存储接口
 * @author: 王贝强
 * @create: 2025-04-06 12:46
 */
@DubboService
public interface DistributedStorageService {

    /**
     * 根据文件哈希批量获取文件内容
     * 从分布式存储中读取指定文件的二进制数据
     * 
     * @param filePathList 文件路径列表
     * @param fileHashList 文件哈希列表，用于验证文件完整性
     * @return 文件内容的字节数组列表
     */
    Result<List<byte[]>> getFileListByHash(List<String> filePathList, List<String> fileHashList);
    
    /**
     * 根据文件哈希批量获取文件访问URL
     * 生成文件的临时访问链接，用于前端下载或预览
     * 
     * @param filePathList 文件路径列表
     * @param fileHashList 文件哈希列表
     * @return 文件访问URL列表
     */
    Result<List<String>> getFileUrlListByHash(List<String> filePathList, List<String> fileHashList);
    
    /**
     * 批量存储文件到分布式存储系统
     * 将文件数据保存到 MinIO 存储集群中
     * 
     * @param fileList 要存储的文件内容列表（字节数组）
     * @param FileHashList 对应的文件哈希列表，用于文件标识和完整性校验
     * @return 存储结果映射，包含文件哈希与存储路径的对应关系
     */
    Result<Map<String, String>> storeFile(List<byte[]> fileList, List<String> FileHashList);
    
    /**
     * 删除分布式存储中的文件
     * 从 MinIO 存储系统中移除指定的文件
     * 
     * @param fileContent 文件内容映射，包含要删除的文件信息
     * @return 删除操作结果
     */
    Result<Boolean> deleteFile(Map<String, String> fileContent);

}

