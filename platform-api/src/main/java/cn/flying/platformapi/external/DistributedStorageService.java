package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.StorageCapacityVO;

import java.util.List;
import java.util.Map;

/**
 * 分布式存储接口 v3.0.0
 * 提供文件分片存储、下载、删除等操作。
 *
 * <p>v3.0.0 新特性：
 * <ul>
 *   <li>支持故障域（Fault Domain）分布策略</li>
 *   <li>50% 节点宕机容错</li>
 *   <li>一致性哈希均匀分布</li>
 *   <li>新增域健康监控 API</li>
 * </ul>
 *
 * <p>v2.0.0 破坏性变更：
 * <ul>
 *   <li>移除已弃用的 storeFile(List, List) 批量存储方法</li>
 *   <li>统一使用 storeFileChunk 进行单文件块存储</li>
 * </ul>
 */
public interface DistributedStorageService {

    /** Dubbo 服务版本号 */
    String VERSION = "3.0.0";

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

    // ===== v3.0.0 新增：故障域管理 API =====

    /**
     * 获取故障域健康状态
     *
     * @return 域名称到健康信息的映射
     *         格式: {domainName -> {healthyNodes: n, totalNodes: m, status: "healthy"|"degraded"}}
     */
    default Result<Map<String, Map<String, Object>>> getDomainHealth() {
        return Result.success(Map.of());
    }

    /**
     * 获取分片的存储位置
     *
     * @param chunkHash 分片哈希
     * @return 分片所在的节点列表
     */
    default Result<List<String>> getChunkLocations(String chunkHash) {
        return Result.success(List.of());
    }

    /**
     * 触发手动再平衡
     *
     * @param targetDomain 目标域（可选，null 表示全部域）
     * @return 再平衡任务 ID
     */
    default Result<String> triggerRebalance(String targetDomain) {
        return Result.success(null);
    }

    /**
     * 获取再平衡状态
     *
     * @return 再平衡状态信息
     */
    default Result<Map<String, Object>> getRebalanceStatus() {
        return Result.success(Map.of());
    }

    /**
     * 获取存储容量信息。
     * <p>
     * 该方法用于监控场景，返回集群总容量、已用容量以及节点/故障域维度汇总。
     * 作为 default 方法提供，保证对已有 Provider 的非破坏性兼容。
     *
     * @return 存储容量信息
     */
    default Result<StorageCapacityVO> getStorageCapacity() {
        return Result.success(null);
    }
}
