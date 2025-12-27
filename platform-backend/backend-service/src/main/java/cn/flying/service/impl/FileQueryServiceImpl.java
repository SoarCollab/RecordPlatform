package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.ShareType;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.mapper.AccountMapper;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.remote.FileRemoteClient;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 文件查询服务实现类（CQRS Query Side）
 * <p>
 * 专注于文件读操作，与 FileServiceImpl 中的写操作分离。
 * 所有方法均为只读，支持更激进的缓存和并发策略。
 * </p>
 *
 * <h3>Virtual Thread 优化</h3>
 * <p>
 * 异步方法使用 virtualThreadExecutor，适合 I/O 密集型操作：
 * <ul>
 *   <li>数据库查询自动让出底层平台线程</li>
 *   <li>Dubbo 远程调用期间不阻塞平台线程</li>
 *   <li>支持百万级并发查询</li>
 * </ul>
 * </p>
 *
 * <h3>缓存策略</h3>
 * <p>
 * 使用 Caffeine 本地缓存，缓存名称定义在 CacheConfiguration 中：
 * <ul>
 *   <li>userFiles - 用户文件列表（高命中率）</li>
 *   <li>fileDecryptInfo - 文件解密信息</li>
 *   <li>transaction - 区块链交易信息</li>
 *   <li>sharedFiles - 分享文件列表</li>
 * </ul>
 * </p>
 *
 * @author flyingcoding
 * @since 1.0.0
 */
@Slf4j
@Service
public class FileQueryServiceImpl implements FileQueryService {

    @Resource
    private FileMapper fileMapper;

    @Resource
    private AccountMapper accountMapper;

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource(name = "virtualThreadExecutor")
    private TaskExecutor virtualThreadExecutor;

    // ==================== 同步查询方法 ====================

    @Override
    public File getFileById(Long userId, Long fileId) {
        File file = fileMapper.selectById(fileId);
        if (file == null) {
            throw new GeneralException(ResultEnum.FAIL, "文件不存在");
        }
        // 权限校验：用户只能查看自己的文件，管理员可查看所有
        if (!SecurityUtils.isAdmin() && !file.getUid().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权访问此文件");
        }

        // 如果是从分享保存的文件，查询原上传者用户名和直接分享者用户名
        if (file.getOrigin() != null) {
            File originFile = fileMapper.selectById(file.getOrigin());
            if (originFile != null) {
                Account originOwner = accountMapper.selectById(originFile.getUid());
                if (originOwner != null) {
                    file.setOriginOwnerName(originOwner.getUsername());
                }
            }
        }

        // 查询直接分享者用户名
        if (file.getSharedFromUserId() != null) {
            Account sharedFromUser = accountMapper.selectById(file.getSharedFromUserId());
            if (sharedFromUser != null) {
                file.setSharedFromUserName(sharedFromUser.getUsername());
            }
        }
        return file;
    }

    @Override
    @Cacheable(cacheNames = "userFiles", key = "#userId", unless = "#result == null || #result.isEmpty()")
    public List<File> getUserFilesList(Long userId) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }
        return fileMapper.selectList(wrapper);
    }

    @Override
    public void getUserFilesPage(Long userId, Page<File> page) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }
        fileMapper.selectPage(page, wrapper);
    }

    @Override
    public List<String> getFileAddress(Long userId, String fileHash) {
        validateFileOwnership(userId, fileHash);

        String userIdStr = String.valueOf(userId);
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(userIdStr, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情，文件可能不存在");
        }
        String fileContent = detailVO.getContent();
        if (CommonUtils.isEmpty(fileContent)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        if (fileContentMap == null || fileContentMap.isEmpty()) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
        }
        Result<List<String>> urlListResult = fileRemoteClient.getFileUrlListByHash(
                fileContentMap.values().stream().toList(),
                fileContentMap.keySet().stream().toList()
        );
        return ResultUtils.getData(urlListResult);
    }

    @Override
    @Cacheable(cacheNames = "transaction", key = "#transactionHash", unless = "#result == null")
    public TransactionVO getTransactionByHash(String transactionHash) {
        Result<TransactionVO> result = fileRemoteClient.getTransactionByHash(transactionHash);
        return ResultUtils.getData(result);
    }

    @Override
    public List<byte[]> getFile(Long userId, String fileHash) {
        validateFileOwnership(userId, fileHash);

        String userIdStr = String.valueOf(userId);
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(userIdStr, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情，文件可能不存在");
        }
        String fileContent = detailVO.getContent();
        if (CommonUtils.isEmpty(fileContent)) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容为空");
        }
        @SuppressWarnings("unchecked")
        Map<String, String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        if (fileContentMap == null || fileContentMap.isEmpty()) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
        }
        Result<List<byte[]>> fileListResult = fileRemoteClient.getFileListByHash(
                fileContentMap.values().stream().toList(),
                fileContentMap.keySet().stream().toList()
        );
        return ResultUtils.getData(fileListResult);
    }

    /**
     * 根据分享码获取分享文件列表，并按过期时间判定取消/过期状态
     */
    @Override
    @Cacheable(cacheNames = "sharedFiles", key = "#sharingCode", unless = "#result == null || #result.isEmpty()")
    public List<File> getShareFile(String sharingCode) {
        Result<SharingVO> result = fileRemoteClient.getSharedFiles(sharingCode);
        if (ResultUtils.isSuccess(result)) {
            SharingVO sharingFiles = ResultUtils.getData(result);
            Long expirationTime = sharingFiles.getExpirationTime();
            if (expirationTime != null) {
                if (expirationTime < 0) {
                    throw new GeneralException(ResultEnum.SHARE_CANCELLED);
                }
                if (expirationTime > 0 && expirationTime < System.currentTimeMillis()) {
                    throw new GeneralException(ResultEnum.SHARE_EXPIRED);
                }
            }
            if (Boolean.FALSE.equals(sharingFiles.getIsValid())) {
                throw new GeneralException(ResultEnum.SHARE_CANCELLED);
            }
            String uploader = sharingFiles.getUploader();
            List<String> fileHashList = sharingFiles.getFileHashList();
            if (CommonUtils.isNotEmpty(fileHashList)) {
                try {
                    Long uploaderId = Long.valueOf(uploader);
                    // 公开分享需要跨租户访问，使用 runWithoutIsolation 绕过租户隔离
                    return TenantContext.runWithoutIsolation(() -> {
                        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                                .eq(File::getUid, uploaderId)
                                .in(File::getFileHash, fileHashList);
                        List<File> files = fileMapper.selectList(wrapper);

                        // 查询分享者用户名并填充到文件对象
                        if (CommonUtils.isNotEmpty(files)) {
                            Account owner = accountMapper.selectById(uploaderId);
                            String ownerName = (owner != null) ? owner.getUsername() : null;
                            files.forEach(file -> file.setOwnerName(ownerName));
                        }
                        return files;
                    });
                } catch (NumberFormatException e) {
                    log.warn("分享文件的上传者ID格式不正确: {}", uploader);
                }
            }
        }
        return List.of();
    }

    @Override
    @Cacheable(cacheNames = "fileDecryptInfo", key = "#userId + ':' + #fileHash", unless = "#result == null")
    public FileDecryptInfoVO getFileDecryptInfo(Long userId, String fileHash) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash);

        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }

        File file = fileMapper.selectOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权限访问");
        }

        String fileParam = file.getFileParam();
        if (CommonUtils.isEmpty(fileParam)) {
            throw new GeneralException(ResultEnum.FAIL, "文件元数据不完整，缺少解密信息");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = JsonConverter.parse(fileParam, Map.class);

            String initialKey = (String) params.get("initialKey");
            if (CommonUtils.isEmpty(initialKey)) {
                throw new GeneralException(ResultEnum.FAIL, "文件解密密钥不存在");
            }

            String fileName = (String) params.get("fileName");
            Long fileSize = params.get("fileSize") instanceof Number
                    ? ((Number) params.get("fileSize")).longValue() : null;
            String contentType = (String) params.get("contentType");
            Integer chunkCount = params.get("chunkCount") instanceof Number
                    ? ((Number) params.get("chunkCount")).intValue() : null;

            return FileDecryptInfoVO.builder()
                    .initialKey(initialKey)
                    .fileName(fileName != null ? fileName : file.getFileName())
                    .fileSize(fileSize)
                    .contentType(contentType)
                    .chunkCount(chunkCount)
                    .fileHash(fileHash)
                    .build();

        } catch (GeneralException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析文件参数失败: fileHash={}, error={}", fileHash, e.getMessage());
            throw new GeneralException(ResultEnum.FAIL, "解析文件元数据失败");
        }
    }

    @Override
    public IPage<FileShareVO> getUserShares(Long userId, Page<?> page) {
        Long tenantId = TenantContext.getTenantId();

        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(tenantId != null, FileShare::getTenantId, tenantId)
                .eq(FileShare::getUserId, userId)
                .orderByDesc(FileShare::getCreateTime);

        Page<FileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        fileShareMapper.selectPage(sharePage, wrapper);

        List<FileShareVO> shareList = new ArrayList<>();
        for (FileShare share : sharePage.getRecords()) {
            FileShareVO vo = convertFileShareToVO(share);

            List<String> fileHashes = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isNotEmpty(fileHashes)) {
                LambdaQueryWrapper<File> fileWrapper = new LambdaQueryWrapper<File>()
                        .eq(File::getUid, userId)
                        .in(File::getFileHash, fileHashes);
                List<File> files = fileMapper.selectList(fileWrapper);
                vo.setFileNames(files.stream().map(File::getFileName).toList());
            } else {
                vo.setFileNames(List.of());
            }

            shareList.add(vo);
        }

        Page<FileShareVO> result = new Page<>(page.getCurrent(), page.getSize());
        result.setRecords(shareList);
        result.setTotal(sharePage.getTotal());
        return result;
    }

    /**
     * 统计用户文件数量、存储用量、分享数量与今日上传数。
     */
    @Override
    public UserFileStatsVO getUserFileStats(Long userId) {
        Long tenantId = TenantContext.getTenantId();
        
        // 查询文件总数
        Long totalFiles = fileMapper.countByUserId(userId, tenantId);
        
        // 查询存储用量：遍历未删除文件，累加 fileSize
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .eq(tenantId != null, File::getTenantId, tenantId)
                .eq(File::getDeleted, 0);
        List<File> files = fileMapper.selectList(wrapper);
        long totalStorage = 0L;
        for (File file : files) {
            Long fileSize = file.getFileSize();
            if (fileSize != null && fileSize > 0) {
                totalStorage += fileSize;
            }
        }
        
        // 查询分享数量
        LambdaQueryWrapper<FileShare> shareWrapper = new LambdaQueryWrapper<FileShare>()
                .eq(tenantId != null, FileShare::getTenantId, tenantId)
                .eq(FileShare::getUserId, userId)
                .eq(FileShare::getStatus, FileShare.STATUS_ACTIVE);
        Long sharedFiles = fileShareMapper.selectCount(shareWrapper);
        
        // 查询今日上传数（今日 00:00:00 开始）
        Date todayStart = Date.from(LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant());
        Long todayUploads = fileMapper.countTodayUploadsByUserId(userId, tenantId, todayStart);
        
        return UserFileStatsVO.builder()
                .totalFiles(totalFiles != null ? totalFiles : 0L)
                .totalStorage(totalStorage)
                .sharedFiles(sharedFiles != null ? sharedFiles : 0L)
                .todayUploads(todayUploads != null ? todayUploads : 0L)
                .build();
    }

    // ==================== 异步查询方法（Virtual Thread）====================

    @Override
    public CompletableFuture<List<File>> getUserFilesListAsync(Long userId) {
        return CompletableFuture.supplyAsync(
                () -> getUserFilesList(userId),
                virtualThreadExecutor
        );
    }

    @Override
    public CompletableFuture<List<String>> getFileAddressAsync(Long userId, String fileHash) {
        return CompletableFuture.supplyAsync(
                () -> getFileAddress(userId, fileHash),
                virtualThreadExecutor
        );
    }

    @Override
    public CompletableFuture<FileDecryptInfoVO> getFileDecryptInfoAsync(Long userId, String fileHash) {
        return CompletableFuture.supplyAsync(
                () -> getFileDecryptInfo(userId, fileHash),
                virtualThreadExecutor
        );
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 校验文件所有权：用户只能访问自己的文件，管理员可访问所有文件
     */
    private void validateFileOwnership(Long userId, String fileHash) {
        if (!SecurityUtils.isAdmin()) {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, userId)
                    .eq(File::getFileHash, fileHash);
            if (fileMapper.selectCount(wrapper) == 0) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
            }
        }
    }

    /**
     * 将 FileShare 实体转换为 FileShareVO
     */
    private FileShareVO convertFileShareToVO(FileShare fileShare) {
        FileShareVO vo = new FileShareVO();
        vo.setSharingCode(fileShare.getShareCode());
        vo.setFileHashes(parseFileHashes(fileShare.getFileHashes()));
        vo.setAccessCount(fileShare.getAccessCount() != null ? fileShare.getAccessCount() : 0);
        vo.setExpireTime(fileShare.getExpireTime());
        vo.setCreateTime(fileShare.getCreateTime());

        ShareType shareType = ShareType.fromCode(fileShare.getShareType());
        vo.setShareType(shareType.getCode());
        vo.setShareTypeDesc(shareType.getName());

        int status = fileShare.getStatus() != null ? fileShare.getStatus() : FileShare.STATUS_ACTIVE;
        if (status == FileShare.STATUS_ACTIVE
                && fileShare.getExpireTime() != null
                && fileShare.getExpireTime().before(new Date())) {
            status = FileShare.STATUS_EXPIRED;
        }

        vo.setStatus(status);
        vo.setIsValid(status == FileShare.STATUS_ACTIVE);

        switch (status) {
            case FileShare.STATUS_CANCELLED -> vo.setStatusDesc("已取消");
            case FileShare.STATUS_ACTIVE -> vo.setStatusDesc("有效");
            case FileShare.STATUS_EXPIRED -> vo.setStatusDesc("已过期");
            default -> vo.setStatusDesc("未知");
        }

        return vo;
    }

    /**
     * 解析文件哈希 JSON 数组
     */
    private List<String> parseFileHashes(String fileHashesJson) {
        if (CommonUtils.isEmpty(fileHashesJson)) {
            return List.of();
        }
        try {
            String[] hashes = JsonConverter.parse(fileHashesJson, String[].class);
            return hashes != null ? Arrays.asList(hashes) : List.of();
        } catch (Exception e) {
            log.warn("解析文件哈希列表失败: {}", fileHashesJson);
            return List.of();
        }
    }
}
