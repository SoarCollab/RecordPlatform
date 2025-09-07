package cn.flying.platformapi.external;

import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.*;
import org.apache.dubbo.config.annotation.DubboService;

import java.util.List;

/**
 * 区块链服务接口
 * 提供文件存储、查询、删除、分享等区块链相关操作
 * 基于 FISCO BCOS 区块链平台实现分布式存证功能
 * 
 * @program: RecordPlatform
 * @description: 区块链调用接口
 * @author: 王贝强
 * @create: 2025-03-10 09:40
 */
@DubboService
public interface BlockChainService {
    
    /**
     * 存储文件到区块链
     * 将文件信息和内容存储到区块链网络中，确保数据不可篡改
     * 
     * @param uploader 文件上传者标识
     * @param fileName 文件名称
     * @param param 文件参数（元数据信息）
     * @param content 文件内容
     * @return 操作结果，包含存储成功后的相关信息
     */
    Result<List<String>> storeFile(String uploader, String fileName, String param, String content);
    
    /**
     * 获取用户的所有文件列表
     * 从区块链中查询指定用户上传的所有文件信息
     * 
     * @param uploader 文件上传者标识
     * @return 用户文件列表，包含文件名和文件哈希
     */
    Result<List<FileVO>> getUserFiles(String uploader);
    
    /**
     * 获取指定文件的详细信息
     * 根据文件哈希值查询文件的完整详情
     * 
     * @param uploader 文件上传者标识
     * @param fileHash 文件哈希值
     * @return 文件详细信息，包含内容、参数、上传时间等
     */
    Result<FileDetailVO> getFile(String uploader, String fileHash);
    
    /**
     * 批量删除文件
     * 从区块链中删除指定的多个文件
     * 
     * @param uploader 文件上传者标识
     * @param fileHashList 要删除的文件哈希列表
     * @return 删除操作结果
     */
    Result<Boolean> deleteFiles(String uploader, List<String> fileHashList);
    
    /**
     * 删除单个文件
     * 从区块链中删除指定的文件
     * 
     * @param uploader 文件上传者标识
     * @param fileHash 要删除的文件哈希
     * @return 删除操作结果
     */
    Result<Boolean> deleteFile(String uploader, String fileHash);
    
    /**
     * 分享文件
     * 创建文件分享链接，设置访问次数限制
     * 
     * @param uploader 文件上传者标识
     * @param fileHash 要分享的文件哈希列表
     * @param maxAccesses 最大访问次数限制
     * @return 分享码，用于访问分享的文件
     */
    Result<String> shareFiles(String uploader, List<String> fileHash, Integer maxAccesses);
    
    /**
     * 获取分享的文件信息
     * 根据分享码获取被分享的文件列表
     * 
     * @param shareCode 文件分享码
     * @return 分享文件信息，包含上传者和文件哈希列表
     */
    Result<SharingVO> getSharedFiles(String shareCode);
    
    /**
     * 获取当前区块链状态信息
     * 查询区块链网络的实时状态，包括区块高度、交易数量等
     * 
     * @return 区块链状态信息
     */
    Result<BlockChainMessage> getCurrentBlockChainMessage();
    
    /**
     * 根据交易哈希获取交易详情
     * 查询指定交易的详细信息和执行状态
     * 
     * @param transactionHash 交易哈希值
     * @return 交易详细信息
     */
    Result<TransactionVO> getTransactionByHash(String transactionHash);
}
