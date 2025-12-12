package cn.flying.service;

import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.platformapi.response.TransactionVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 文件查询服务接口（CQRS Query Side）
 * <p>
 * 该服务专门处理文件相关的读操作，与 FileService 中的写操作（Command）分离。
 * 所有方法均为只读操作，不涉及数据修改，支持更激进的缓存策略。
 * </p>
 *
 * <h3>设计理念</h3>
 * <ul>
 *   <li><b>读写分离</b>：Query 操作独立，便于针对性优化（缓存、只读副本）</li>
 *   <li><b>Virtual Thread 友好</b>：I/O 密集型查询适合使用虚拟线程</li>
 *   <li><b>异步支持</b>：提供 CompletableFuture 异步方法，支持非阻塞调用</li>
 * </ul>
 *
 * @author flying
 * @since 1.0.0
 */
public interface FileQueryService {

    // ==================== 同步查询方法 ====================

    /**
     * 根据用户ID获取用户文件列表（元信息）
     *
     * @param userId 用户ID
     * @return 文件元信息列表
     */
    List<File> getUserFilesList(Long userId);

    /**
     * 分页获取用户文件列表
     *
     * @param userId 用户ID
     * @param page   分页参数（传入后会被填充结果）
     */
    void getUserFilesPage(Long userId, Page<File> page);

    /**
     * 获取文件分片存储地址（预签名URL）
     *
     * @param userId   用户ID
     * @param fileHash 文件哈希
     * @return 分片地址列表
     */
    List<String> getFileAddress(Long userId, String fileHash);

    /**
     * 根据交易哈希获取区块链交易信息
     *
     * @param transactionHash 交易哈希
     * @return 交易详情
     */
    TransactionVO getTransactionByHash(String transactionHash);

    /**
     * 获取文件分片内容（字节数组）
     *
     * @param userId   用户ID
     * @param fileHash 文件哈希
     * @return 分片字节数组列表
     */
    List<byte[]> getFile(Long userId, String fileHash);

    /**
     * 根据分享码获取分享的文件列表
     *
     * @param sharingCode 分享码
     * @return 分享的文件列表
     */
    List<File> getShareFile(String sharingCode);

    /**
     * 获取文件解密信息（客户端解密用）
     *
     * @param userId   用户ID
     * @param fileHash 文件哈希
     * @return 解密信息（初始密钥、元数据）
     */
    FileDecryptInfoVO getFileDecryptInfo(Long userId, String fileHash);

    // ==================== 异步查询方法（Virtual Thread 优化）====================

    /**
     * 异步获取用户文件列表
     * <p>
     * 使用 Virtual Thread 执行，适合高并发场景。
     * </p>
     *
     * @param userId 用户ID
     * @return 异步结果
     */
    CompletableFuture<List<File>> getUserFilesListAsync(Long userId);

    /**
     * 异步获取文件分片地址
     *
     * @param userId   用户ID
     * @param fileHash 文件哈希
     * @return 异步结果
     */
    CompletableFuture<List<String>> getFileAddressAsync(Long userId, String fileHash);

    /**
     * 异步获取文件解密信息
     *
     * @param userId   用户ID
     * @param fileHash 文件哈希
     * @return 异步结果
     */
    CompletableFuture<FileDecryptInfoVO> getFileDecryptInfoAsync(Long userId, String fileHash);
}
