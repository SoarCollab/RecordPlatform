package cn.flying.service;

import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
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
 * @author flyingcoding
 * @since 1.0.0
 */
public interface FileQueryService {

    // ==================== 同步查询方法 ====================

    /**
     * 根据文件ID获取文件详情
     *
     * @param userId 用户ID（用于权限校验）
     * @param fileId 文件ID
     * @return 文件详情
     */
    File getFileById(Long userId, Long fileId);

    /**
     * 根据文件哈希获取文件详情（支持好友分享访问）
     *
     * <p>权限策略：</p>
     * <ul>
     *   <li>管理员：可查询任意文件</li>
     *   <li>普通用户：可查询自己的文件</li>
     *   <li>普通用户：若存在有效的好友分享记录，可查询分享者对应文件</li>
     * </ul>
     *
     * @param userId   当前用户ID（用于权限校验）
     * @param fileHash 文件哈希
     * @return 文件详情
     */
    File getFileByHash(Long userId, String fileHash);

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
     * @param userId  用户ID
     * @param page    分页参数（传入后会被填充结果）
     * @param keyword 搜索关键词（可选，匹配文件名或文件哈希）
     * @param status  文件状态过滤（可选）
     */
    void getUserFilesPage(Long userId, Page<File> page, String keyword, Integer status);

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

    /**
     * 获取用户创建的分享列表
     *
     * @param userId 用户ID
     * @param page   分页参数
     * @return 分享记录分页
     */
    com.baomidou.mybatisplus.core.metadata.IPage<cn.flying.dao.vo.file.FileShareVO> getUserShares(Long userId, Page<?> page);

    /**
     * 获取用户文件统计信息
     * <p>
     * 用于 Dashboard 展示：文件总数、存储用量等
     * </p>
     *
     * @param userId 用户ID
     * @return 文件统计信息
     */
    UserFileStatsVO getUserFileStats(Long userId);

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
