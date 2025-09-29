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
     * @param fileList     要存储的文件内容列表（字节数组）
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

    // ==================== 分块上传相关接口（流式传输优化） ====================

    /**
     * 初始化分块上传会话
     * 创建一个新的分块上传任务，返回上传会话ID
     *
     * @param fileName  文件名
     * @param fileHash  文件的完整哈希值（用于完整性校验）
     * @param totalSize 文件总大小（字节）
     * @param metadata  文件元数据（可选，如文件类型、创建时间等）
     * @return 包含上传会话ID的结果对象
     */
    Result<String> initMultipartUpload(String fileName, String fileHash, long totalSize, Map<String, String> metadata);

    /**
     * 上传单个分块
     * 将文件的一个分块上传到存储系统
     *
     * @param uploadId   上传会话ID（由initMultipartUpload返回）
     * @param partNumber 分块编号（从1开始）
     * @param data       分块数据
     * @param partHash   分块的哈希值（用于完整性校验）
     * @return 包含分块ETag的结果对象
     */
    Result<String> uploadPart(String uploadId, int partNumber, byte[] data, String partHash);

    /**
     * 完成分块上传
     * 将所有已上传的分块合并为完整文件
     *
     * @param uploadId  上传会话ID
     * @param partETags 所有分块的ETag列表（按partNumber排序）
     * @return 包含文件存储路径的结果对象
     */
    Result<String> completeMultipartUpload(String uploadId, List<String> partETags);

    /**
     * 取消分块上传
     * 中止上传会话并清理已上传的分块
     *
     * @param uploadId 上传会话ID
     * @return 操作结果
     */
    Result<Boolean> abortMultipartUpload(String uploadId);

    /**
     * 列出已上传的分块
     * 获取指定上传会话中已成功上传的分块列表
     *
     * @param uploadId 上传会话ID
     * @return 已上传分块的信息列表
     */
    Result<List<Map<String, Object>>> listUploadedParts(String uploadId);

    /**
     * 流式存储文件（优化版本）
     * 使用分块流式传输替代一次性传输，支持大文件和断点续传
     *
     * @param fileList     要存储的文件内容列表（分块传输）
     * @param fileHashList 对应的文件哈希列表
     * @param chunkSize    建议的分块大小（字节）
     * @return 存储结果映射
     */
    Result<Map<String, String>> storeFileStreaming(List<byte[]> fileList, List<String> fileHashList, int chunkSize);

}

