package cn.flying.fisco_bcos.adapter;

import cn.flying.fisco_bcos.adapter.model.*;

import java.util.List;

/**
 * 区块链适配器 SPI 接口
 * 定义与区块链交互的统一契约，支持多链切换
 *
 * <p>实现类：
 * <ul>
 *   <li>{@code LocalFiscoAdapter} - 本地 FISCO BCOS 节点</li>
 *   <li>{@code BsnFiscoAdapter} - BSN 托管的 FISCO BCOS 节点</li>
 *   <li>{@code BsnBesuAdapter} - BSN 托管的 Hyperledger Besu 节点</li>
 * </ul>
 *
 * <p>通过 Spring 的 {@code @ConditionalOnProperty} 注解实现配置驱动的链切换
 */
public interface BlockChainAdapter {

    /**
     * 获取当前适配器对应的链类型
     *
     * @return 链类型枚举
     */
    ChainType getChainType();

    // ==================== 文件存储操作 ====================

    /**
     * 存储文件元数据到区块链
     *
     * @param uploader 上传者标识
     * @param fileName 文件名
     * @param content  文件内容 (分片路径 JSON)
     * @param param    自定义参数 (JSON)
     * @return 交易回执，包含 transactionHash 和 fileHash
     * @throws ChainException 链操作异常
     */
    ChainReceipt storeFile(String uploader, String fileName, String content, String param);

    /**
     * 获取用户的所有文件列表
     *
     * @param uploader 上传者标识
     * @return 文件基本信息列表
     * @throws ChainException 链操作异常
     */
    List<ChainFileInfo> getUserFiles(String uploader);

    /**
     * 获取指定文件的详情
     *
     * @param uploader 上传者标识
     * @param fileHash 文件哈希
     * @return 文件详情，包含完整元数据
     * @throws ChainException 链操作异常
     */
    ChainFileDetail getFile(String uploader, String fileHash);

    /**
     * 批量删除文件
     *
     * @param uploader   上传者标识
     * @param fileHashes 要删除的文件哈希列表
     * @return 交易回执
     * @throws ChainException 链操作异常
     */
    ChainReceipt deleteFiles(String uploader, List<String> fileHashes);

    // ==================== 文件分享操作 ====================

    /**
     * 创建文件分享
     *
     * @param uploader    上传者标识
     * @param fileHashes  要分享的文件哈希列表
     * @param expireMinutes 分享有效期（分钟）
     * @return 交易回执，包含 shareCode
     * @throws ChainException 链操作异常
     */
    ChainReceipt shareFiles(String uploader, List<String> fileHashes, int expireMinutes);

    /**
     * 获取分享信息
     *
     * @param shareCode 分享码
     * @return 分享信息，包含分享者和文件列表
     * @throws ChainException 链操作异常
     */
    ChainShareInfo getSharedFiles(String shareCode);

    /**
     * 取消分享
     *
     * @param shareCode 分享码
     * @return 交易回执
     * @throws ChainException 链操作异常
     */
    ChainReceipt cancelShare(String shareCode);

    /**
     * 获取用户的所有分享码列表
     *
     * @param uploader 上传者标识
     * @return 分享码列表
     * @throws ChainException 链操作异常
     */
    List<String> getUserShareCodes(String uploader);

    /**
     * 获取分享详情（不校验有效性，包含已取消的分享）
     *
     * @param shareCode 分享码
     * @return 分享详情，包含 isValid 状态
     * @throws ChainException 链操作异常
     */
    ChainShareInfo getShareInfo(String shareCode);

    // ==================== 链状态查询 ====================

    /**
     * 获取区块链状态信息
     *
     * @return 链状态，包含区块高度、交易数等
     * @throws ChainException 链操作异常
     */
    ChainStatus getChainStatus();

    /**
     * 根据交易哈希获取交易详情
     *
     * @param txHash 交易哈希
     * @return 交易详情
     * @throws ChainException 链操作异常
     */
    ChainTransaction getTransaction(String txHash);

    // ==================== 健康检查 ====================

    /**
     * 检查链连接是否健康
     *
     * @return true 如果连接正常，false 否则
     */
    boolean isHealthy();
}
