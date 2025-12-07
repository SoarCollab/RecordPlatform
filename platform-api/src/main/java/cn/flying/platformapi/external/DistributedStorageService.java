package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;

import java.util.List;
import java.util.Map;

/**
 * 分布式存储接口 v2.0.0
 * 提供文件分片存储、下载、删除等操作。
 *
 * <p>v2.0.0 破坏性变更：
 * <ul>
 *   <li>移除已弃用的 storeFile(List, List) 批量存储方法</li>
 *   <li>统一使用 storeFileChunk 进行单文件块存储</li>
 * </ul>
 */
public interface DistributedStorageService {

    /** Dubbo 服务版本号 */
    String VERSION = "2.0.0";

    /**
     * 批量获取文件内容
     *
     * @param filePathList 文件逻辑路径列表
     * @param fileHashList 文件哈希列表
     * @return 文件内容字节数组列表
     */
    Result<List<byte[]>> getFileListByHash(List<String> filePathList, List<String> fileHashList);

    /**
     * 批量获取文件预签名下载 URL
     *
     * @param filePathList 文件逻辑路径列表
     * @param fileHashList 文件哈希列表
     * @return 预签名 URL 列表
     */
    Result<List<String>> getFileUrlListByHash(List<String> filePathList, List<String> fileHashList);

    /**
     * 存储单个文件块
     *
     * @param fileData 文件数据
     * @param fileHash 文件哈希
     * @return 存储的逻辑路径
     */
    Result<String> storeFileChunk(byte[] fileData, String fileHash);

    /**
     * 删除文件
     *
     * @param fileContent 文件哈希到逻辑路径的映射 (fileHash -> logicalPath)
     * @return 删除结果
     */
    Result<Boolean> deleteFile(Map<String, String> fileContent);

    /**
     * 获取集群健康状态
     *
     * @return 节点名称到在线状态的映射 (nodeName -> online)
     */
    Result<Map<String, Boolean>> getClusterHealth();
}
