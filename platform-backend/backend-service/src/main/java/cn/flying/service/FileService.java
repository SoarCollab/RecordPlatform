package cn.flying.service;

import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.response.TransactionVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 文件服务接口（经过多层封装后对外暴露的统一接口）
 * @program: RecordPlatform
 * @author flyingcoding
 * @create: 2025-03-12 21:22
 */
public interface FileService extends IService<File> {

    /**
     * 在完成分片上传后预存储文件（此时设置文件状态为）
     * @param userId 用户ID
     * @param OriginFileName 原始文件名
     */
    void prepareStoreFile(Long userId, String OriginFileName);
    /**
     * 存储文件
     * @param userId 用户ID
     * @param fileList 加密后的文件分片列表
     * @param fileHashList 文件分片对应的哈希列表
     * @param fileParam 文件参数(JSON)
     * @return
     */
    File storeFile(Long userId, String OriginFileName, List<java.io.File> fileList, List<String> fileHashList, String fileParam);

    /**
     * 修改文件状态
     * @param userId
     * @param fileName
     * @param fileStatus
     */
    void changeFileStatusByName(Long userId, String fileName, Integer fileStatus);

    /**
     * 修改文件状态
     * @param userId
     * @param fileHash
     * @param fileStatus
     */
    void changeFileStatusByHash(Long userId, String fileHash, Integer fileStatus);

    /**
     * 批量删除文件
     * 支持通过文件哈希或文件ID进行删除
     * @param userId 用户ID
     * @param identifiers 文件哈希或文件ID列表
     */
    void deleteFiles(Long userId, List<String> identifiers);

    /**
     * 根据用户Id获取用户文件列表（只包含文件元信息，没有实际的文件数据）
     * @param userId 用户ID
     * @return 文件元信息列表
     */
    List<File> getUserFilesList(Long userId);

    /**
     * 获取用户文件分页（只包含文件元信息，没有实际的文件数据）
     *
     * @param userId
     * @param page
     */
    void getUserFilesPage(Long userId, Page<File> page);

    /**
     * 获取文件分片地址
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 文件分片地址
     */
    List<String> getFileAddress(Long userId, String fileHash);

    /**
     * 根据交易哈希获取文件交易信息
     * @param transactionHash 交易哈希
     * @return 交易信息
     */
    TransactionVO getTransactionByHash(String transactionHash);

    /**
     * 获取文件分片
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 文件分片列表
     */
    List<byte[]> getFile(Long userId, String fileHash);

    /**
     * 分享文件给其它用户
     * @param userId 用户ID
     * @param fileHash 待分享的文件哈希
     * @param expireMinutes 分享有效期（分钟）
     * @param shareType 分享类型：0-公开，1-私密
     * @return 分享码
     */
    String generateSharingCode(Long userId, List<String> fileHash, Integer expireMinutes, Integer shareType);

    /**
     * 获取根据分享码获取他人分享的文件
     * @param sharingCode 分享码
     * @return
     */
    List<File> getShareFile(String sharingCode);

    /**
     * 保存他人分享的文件
     * @param sharingFileIdList 分享文件Id列表
     * @param shareCode 分享码（用于追踪链路，可选）
     * @param clientIp 客户端IP（用于审计日志）
     */
    void saveShareFile(List<String> sharingFileIdList, String shareCode, String clientIp);

    /**
     * 获取文件解密信息
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 解密信息（包含初始密钥和元数据）
     */
    FileDecryptInfoVO getFileDecryptInfo(Long userId, String fileHash);

    /**
     * 获取用户创建的分享列表
     * @param userId 用户ID
     * @param page 分页参数
     * @return 分享记录分页
     */
    com.baomidou.mybatisplus.core.metadata.IPage<cn.flying.dao.vo.file.FileShareVO> getUserShares(Long userId, com.baomidou.mybatisplus.extension.plugins.pagination.Page<?> page);

    /**
     * 取消分享（调用区块链）
     * @param userId 用户ID（用于权限校验）
     * @param shareCode 分享码
     */
    void cancelShare(Long userId, String shareCode);

    /**
     * 更新分享设置（类型、有效期）
     * @param userId 用户ID（用于权限校验）
     * @param updateVO 更新参数
     */
    void updateShare(Long userId, UpdateShareVO updateVO);

    /**
     * 根据分享码获取分享元数据
     * @param shareCode 分享码
     * @return 分享记录
     */
    FileShare getShareByCode(String shareCode);

    /**
     * 公开分享下载文件（无需认证）
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 文件分片列表
     */
    List<byte[]> getPublicFile(String shareCode, String fileHash);

    /**
     * 公开分享获取解密信息（无需认证）
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 解密信息
     */
    FileDecryptInfoVO getPublicFileDecryptInfo(String shareCode, String fileHash);

    /**
     * 登录用户通过分享码下载文件（支持私密/公开分享）
     * @param userId 用户ID
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 文件分片列表
     */
    List<byte[]> getSharedFileContent(Long userId, String shareCode, String fileHash);

    /**
     * 登录用户通过分享码获取解密信息（支持私密/公开分享）
     * @param userId 用户ID
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 解密信息
     */
    FileDecryptInfoVO getSharedFileDecryptInfo(Long userId, String shareCode, String fileHash);

}
