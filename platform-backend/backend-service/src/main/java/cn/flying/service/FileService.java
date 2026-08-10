package cn.flying.service;

import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileDownloadMetadataVO;
import cn.flying.dao.vo.file.ShareInfoVO;
import cn.flying.dao.vo.file.ShareFileVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.request.StoreFileResponse;
import cn.flying.platformapi.response.DirectMultipartCompletedPartVO;
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
     * 稳定 PREPARE 最终化的持久阶段，用于区分可失败、结果未知和可自动恢复状态。
     */
    enum FinalizationRecoveryPhase {
        NONE,
        CLAIMED,
        CHAIN_ATTESTING,
        CHAIN_ATTESTED,
        SUCCESS,
        UNKNOWN
    }

    /**
     * 在完成分片上传后预存储文件（此时设置文件状态为 PREPARE）。
     *
     * @param userId 用户ID
     * @param OriginFileName 原始文件名
     * @param fileSize 文件大小（字节），用于配额预占位
     */
    void prepareStoreFile(Long userId, String OriginFileName, long fileSize);

    /**
     * 在完成分片上传后预存储文件（支持绑定既有 PREPARE 记录）。
     *
     * @param userId 用户ID
     * @param targetFileId 目标文件ID（为空时创建新 PREPARE；非空时复用该记录）
     * @param originFileName 原始文件名
     * @param fileSize 文件大小（字节），用于配额预占位
     */
    void prepareStoreFile(Long userId, Long targetFileId, String originFileName, long fileSize);

    /**
     * 使用会话预先持久化的稳定文件ID创建或复用 PREPARE 记录。
     *
     * @param userId 用户ID
     * @param targetFileId 版本上传绑定的既有 PREPARE 文件ID
     * @param preparedFileId 当前上传会话分配的稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param fileSize 文件大小（字节）
     * @return 已创建或已验证的 PREPARE 文件
     */
    File prepareStoreFileWithStableId(Long userId, Long targetFileId, Long preparedFileId,
                                      String originFileName, long fileSize);

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
     * 存储文件（支持按目标 fileId 精确关联 PREPARE 记录）。
     *
     * @param userId 用户ID
     * @param targetFileId 目标 PREPARE 文件ID（旧调用可为空并按文件名回溯）
     * @param originFileName 原始文件名
     * @param fileList 加密后的文件分片列表
     * @param fileHashList 文件分片对应的哈希列表
     * @param fileParam 文件参数(JSON)
     * @return 已落库文件信息
     */
    File storeFile(Long userId, Long targetFileId, String originFileName, List<java.io.File> fileList, List<String> fileHashList, String fileParam);

    /**
     * 使用上传会话所有者令牌执行普通上传最终化。
     *
     * @param userId 用户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param fileList 加密后的文件分片列表
     * @param fileHashList 文件分片对应的哈希列表
     * @param fileParam 文件参数(JSON)
     * @param ownerToken 上传会话稳定所有者令牌
     * @return 已落库文件信息
     */
    File storeFile(Long userId, Long preparedFileId, String originFileName,
                   List<java.io.File> fileList, List<String> fileHashList,
                   String fileParam, String ownerToken);

    /**
     * Registers a direct-uploaded file whose chunks already exist in object storage.
     *
     * @param userId 用户ID
     * @param preparedFileId 已预先创建的稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param fileSize 文件大小（字节）
     * @param completedParts 已完成的有序分片存储元数据
     * @param fileParam 文件参数(JSON)
     * @return 已落库文件信息
     */
    File storeDirectUploadedFile(Long userId, Long preparedFileId, String originFileName, long fileSize,
                                 List<DirectMultipartCompletedPartVO> completedParts, String fileParam);

    /**
     * 将已经校验的直传对象证据写入区块链，但不推进本地文件状态。
     *
     * @param userId 用户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param completedParts 已校验的对象存储完成证据
     * @param fileParam 文件参数 JSON
     * @return 可持久化重放的链结果
     */
    StoreFileResponse attestDirectUploadedFile(Long userId, Long preparedFileId, String originFileName,
                                               List<DirectMultipartCompletedPartVO> completedParts,
                                               String fileParam);

    /**
     * 使用上传会话所有者令牌登记直传对象证据，并持久化最终化 claim。
     *
     * @param userId 用户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param completedParts 已校验的对象存储完成证据
     * @param fileParam 文件参数 JSON
     * @param ownerToken 上传会话稳定所有者令牌
     * @return 可恢复的链结果
     */
    StoreFileResponse attestDirectUploadedFile(Long userId, Long preparedFileId, String originFileName,
                                               List<DirectMultipartCompletedPartVO> completedParts,
                                               String fileParam, String ownerToken);

    /**
     * 使用已持久化的链结果幂等推进 PREPARE 文件为 SUCCESS。
     *
     * @param userId 用户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param fileSize 文件大小（字节）
     * @param fileParam 文件参数 JSON
     * @param chainResult 已持久化的链结果
     * @return SUCCESS 文件快照
     */
    File persistDirectUploadedFile(Long userId, Long preparedFileId, String originFileName, long fileSize,
                                   String fileParam, StoreFileResponse chainResult);

    /**
     * 使用同一会话 claim 中已确认的链结果推进 PREPARE 为 SUCCESS。
     *
     * @param userId 用户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @param originFileName 原始文件名
     * @param fileSize 文件大小（字节）
     * @param fileParam 文件参数 JSON
     * @param chainResult 已确认链结果
     * @param ownerToken 上传会话稳定所有者令牌
     * @return SUCCESS 文件快照
     */
    File persistDirectUploadedFile(Long userId, Long preparedFileId, String originFileName, long fileSize,
                                   String fileParam, StoreFileResponse chainResult, String ownerToken);

    /**
     * 判断 PREPARE 文件是否存在必须人工对账的不可重放最终化 claim。
     *
     * @param userId 用户ID
     * @param preparedFileId 稳定 PREPARE 文件ID
     * @return claim 已进入 CHAIN_ATTESTING 或 CHAIN_ATTESTED 时返回 true
     */
    boolean requiresManualFinalizationReconciliation(Long userId, Long preparedFileId);

    /**
     * 读取稳定文件记录的持久最终化阶段，不触发任何外部调用或状态变更。
     */
    FinalizationRecoveryPhase getFinalizationRecoveryPhase(Long userId, Long preparedFileId);

    /**
     * 修改文件状态
     * @param userId
     * @param fileHash
     * @param fileStatus
     */
    void changeFileStatusByHash(Long userId, String fileHash, Integer fileStatus);

    /**
     * 根据文件ID修改文件状态。
     *
     * @param userId 用户ID
     * @param fileId 文件ID
     * @param fileStatus 目标状态
     */
    void changeFileStatusById(Long userId, Long fileId, Integer fileStatus);

    /**
     * 将指定 PREPARE 文件安全标记为上传失败，并判断关联上传会话是否允许清理。
     *
     * @param userId 用户ID
     * @param fileId 目标文件ID
     * @return PREPARE 已成功转为 FAIL 或文件原本已为 FAIL 时返回 true；其他情况返回 false
     */
    boolean markFileUploadFailed(Long userId, Long fileId);

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
    List<ShareFileVO> getShareFile(String sharingCode);

    /**
     * 保存他人分享的文件
     * @param sharingFileIdList 分享文件Id列表
     * @param shareCode 分享码（用于追踪链路，可选）
     * @param clientIp 客户端IP（用于审计日志）
     */
    void saveShareFile(List<String> sharingFileIdList, String shareCode, String clientIp);

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
     * 获取分享详情（包含分享文件列表）
     * <p>
     * 包含分享状态校验、过期检查、文件哈希解析和文件查询等完整业务逻辑。
     * </p>
     *
     * @param shareCode 分享码
     * @return 分享详情
     */
    ShareInfoVO getShareInfo(String shareCode);

    /**
     * 公开分享下载文件（无需认证）
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 文件分片列表
     */
    List<byte[]> getPublicFile(String shareCode, String fileHash);

    /**
     * 按版本化密钥交付协议读取公开分享解密元数据。
     */
    FileDecryptInfoVO getPublicFileDecryptInfo(String shareCode,
                                               String fileHash,
                                               String keyDeliveryProtocol,
                                               String downloadSessionId,
                                               String publicClientIdentity);

    /**
     * 获取公开分享的 manifest 驱动预签名下载元数据。
     */
    FileDownloadMetadataVO getPublicFileDownloadMetadata(String shareCode,
                                                         String fileHash,
                                                         String keyDeliveryProtocol,
                                                         String downloadSessionId,
                                                         String publicClientIdentity);

    /**
     * 登录用户通过分享码下载文件（支持私密/公开分享）
     * @param userId 用户ID
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 文件分片列表
     */
    List<byte[]> getSharedFileContent(Long userId, String shareCode, String fileHash);

    /**
     * 按版本化密钥交付协议读取认证分享解密元数据。
     */
    FileDecryptInfoVO getSharedFileDecryptInfo(Long userId,
                                               String shareCode,
                                               String fileHash,
                                               String keyDeliveryProtocol,
                                               String downloadSessionId);

    /**
     * 获取认证分享的 manifest 驱动预签名下载元数据。
     */
    FileDownloadMetadataVO getSharedFileDownloadMetadata(Long userId,
                                                         String shareCode,
                                                         String fileHash,
                                                         String keyDeliveryProtocol,
                                                         String downloadSessionId);

    /**
     * 创建文件新版本（PREPARE 状态）
     * 客户端后续拿返回的 fileId 走现有上传流程
     *
     * @param userId 用户ID
     * @param parentFileId 父版本文件ID（内部ID）
     * @param fileName 新版本文件名
     * @param fileSize 文件大小（字节）
     * @param contentType 文件类型
     * @return PREPARE 状态的新版本 File
     */
    File createNewVersion(Long userId, Long parentFileId, String fileName, long fileSize, String contentType);

}
