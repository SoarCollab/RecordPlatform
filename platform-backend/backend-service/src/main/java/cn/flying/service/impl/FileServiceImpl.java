package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.ShareType;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.dto.FileShare;
import cn.flying.dao.dto.FileSource;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.mapper.FileShareMapper;
import cn.flying.dao.mapper.FileSourceMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.service.saga.FileUploadCommand;
import cn.flying.service.saga.FileUploadResult;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @program: RecordPlatform
 * @description: 文件服务实现类
 * @author flyingcoding
 * @create: 2025-03-12 21:22
 */
@Slf4j
@Service
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Resource
    private FileSagaOrchestrator sagaOrchestrator;

    @Resource
    private FileShareMapper fileShareMapper;

    @Resource
    private FileSourceMapper fileSourceMapper;

    @Resource
    private ShareAuditService shareAuditService;

    @Resource
    private CacheManager cacheManager;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void prepareStoreFile(Long userId, String OriginFileName) {
        File file = new File()
                .setUid(userId)
                .setFileName(OriginFileName)
                .setStatus(FileUploadStatus.PREPARE.getCode());
        this.saveOrUpdate(file);
    }

    /**
     * 存储文件：执行 Saga 流程（S3 存储 + 区块链）
     * 注意：此方法不使用类级别事务，Saga 编排器内部管理自己的事务
     */
    @Override
    @CacheEvict(cacheNames = "userFiles", key = "#userId")
    public File storeFile(Long userId, String OriginFileName, List<java.io.File> fileList, List<String> fileHashList, String fileParam) {
        if (CommonUtils.isEmpty(fileList)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "File list cannot be empty");
        }

        LambdaQueryWrapper<File> fileQuery = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .eq(File::getFileName, OriginFileName)
                .last("LIMIT 1");
        File existingFile = this.getOne(fileQuery);
        if (existingFile == null) {
            throw new GeneralException(ResultEnum.FAIL, "File metadata not initialized for upload");
        }

        String requestId = UUID.randomUUID().toString();
        FileUploadCommand cmd = FileUploadCommand.builder()
                .requestId(requestId)
                .fileId(existingFile.getId())
                .userId(userId)
                .tenantId(existingFile.getTenantId())
                .fileName(OriginFileName)
                .fileParam(fileParam)
                .fileList(fileList)
                .fileHashList(fileHashList)
                .build();

        FileUploadResult result = sagaOrchestrator.executeUpload(cmd);

        if (!result.isSuccess()) {
            String errorMsg = result.getErrorMessage();
            throw new GeneralException(ResultEnum.FAIL, errorMsg != null ? errorMsg : "File upload failed");
        }

        // 使用文件ID精确匹配，避免误更新同名文件
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getId, existingFile.getId())
                .eq(File::getUid, userId)  // 安全校验：确保是该用户的文件
                .eq(File::getStatus, FileUploadStatus.PREPARE.getCode());  // 只更新 PREPARE 状态的记录

        File file = new File()
                .setUid(userId)
                .setFileName(OriginFileName)
                .setFileHash(result.getFileHash())
                .setTransactionHash(result.getTransactionHash())
                .setFileParam(fileParam)
                .setStatus(FileUploadStatus.SUCCESS.getCode());

        boolean updated = this.update(file, wrapper);
        if (!updated) {
            log.warn("文件状态更新失败，可能已被其他操作修改: fileId={}, userId={}, fileName={}",
                    existingFile.getId(), userId, OriginFileName);
            throw new GeneralException(ResultEnum.FAIL, "文件状态更新失败，请重试");
        }

        return file;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "userFiles", key = "#userId")
    public void changeFileStatusByName(Long userId, String fileName, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileName, fileName)
                .eq(File::getUid, userId);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "userFiles", key = "#userId")
    public void changeFileStatusByHash(Long userId, String fileHash, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, userId);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "userFiles", key = "#userId")
    public void deleteFiles(Long userId, List<String> identifiers) {
        if (CommonUtils.isEmpty(identifiers)) {
            return;
        }

        List<String> fileHashes = new ArrayList<>();
        List<Long> fileIds = new ArrayList<>();
        for (String identifier : identifiers) {
            if (CommonUtils.isEmpty(identifier)) {
                continue;
            }
            String normalized = identifier.trim();
            if (normalized.isEmpty()) {
                continue;
            }

            boolean allDigits = normalized.chars().allMatch(Character::isDigit);
            if (allDigits) {
                try {
                    fileIds.add(Long.parseLong(normalized));
                } catch (NumberFormatException ignored) {
                    fileHashes.add(normalized);
                }
            } else {
                fileHashes.add(normalized);
            }
        }

        if (fileHashes.isEmpty() && fileIds.isEmpty()) {
            return;
        }

        // 先查询要删除的文件，用于后续清除缓存
        LambdaQueryWrapper<File> queryWrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .and(w -> {
                    boolean hasCondition = false;
                    if (!fileHashes.isEmpty()) {
                        w.in(File::getFileHash, fileHashes);
                        hasCondition = true;
                    }
                    if (!fileIds.isEmpty()) {
                        if (hasCondition) {
                            w.or();
                        }
                        w.in(File::getId, fileIds);
                    }
                })
                .select(File::getFileHash);
        List<File> filesToDelete = this.list(queryWrapper);

        // 支持同时按 fileHash 或 ID 匹配删除
        // 这样前端传入 file.id 时，对于 fileHash 为 null 的失败文件也能正确删除
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, userId)
                .and(w -> {
                    boolean hasCondition = false;
                    if (!fileHashes.isEmpty()) {
                        w.in(File::getFileHash, fileHashes);
                        hasCondition = true;
                    }
                    if (!fileIds.isEmpty()) {
                        if (hasCondition) {
                            w.or();
                        }
                        w.in(File::getId, fileIds);
                    }
                });
        // Logical delete only - physical cleanup is handled by FileCleanupTask scheduled job
        this.remove(wrapper);

        // 清除 fileDecryptInfo 缓存
        evictFileDecryptInfoCache(userId, filesToDelete);
    }

    /**
     * 清除文件解密信息缓存
     * <p>
     * 缓存 key 格式为 userId:fileHash，仅清除当前用户的缓存条目。
     * 如果其他用户保存了相同 hash 的分享文件，他们的缓存不会被清除，
     * 这是正确的行为，因为分享文件有独立的元数据副本，原始文件删除不影响分享文件。
     * </p>
     */
    private void evictFileDecryptInfoCache(Long userId, List<File> files) {
        Cache cache = cacheManager.getCache("fileDecryptInfo");
        if (cache != null && CommonUtils.isNotEmpty(files)) {
            for (File file : files) {
                if (file.getFileHash() != null) {
                    cache.evict(userId + ":" + file.getFileHash());
                }
            }
        }
    }

    @Override
    @Cacheable(cacheNames = "userFiles", key = "#userId", unless = "#result == null || #result.isEmpty()")
    public List<File> getUserFilesList(Long userId) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        // 所有用户（包括管理员）只能查询自己的文件
        // 管理员查看所有文件请使用 FileAdminService.getAllFiles()
        wrapper.eq(File::getUid, userId);
        return this.list(wrapper);
    }

    @Override
    public void getUserFilesPage(Long userId, Page<File> page) {
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<>();
        // 所有用户（包括管理员）只能查询自己的文件
        // 管理员查看所有文件请使用 FileAdminService.getAllFiles()
        wrapper.eq(File::getUid, userId);
        this.page(page, wrapper);
    }

    @Override
    public List<String> getFileAddress(Long userId, String fileHash) {
        // 校验文件所有权：用户只能获取自己的文件地址，管理员可获取所有
        if (!SecurityUtils.isAdmin()) {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, userId)
                    .eq(File::getFileHash, fileHash);
            if (this.count(wrapper) == 0) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
            }
        }

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
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        if (fileContentMap == null || fileContentMap.isEmpty()) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
        }
        Result<List<String>> urlListResult = fileRemoteClient.getFileUrlListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(urlListResult);
    }

    @Override
    public TransactionVO getTransactionByHash(String transactionHash) {
        Result<TransactionVO> result = fileRemoteClient.getTransactionByHash(transactionHash);
        return ResultUtils.getData(result);
    }

    @Override
    public List<byte[]> getFile(Long userId, String fileHash) {
        // 校验文件所有权：用户只能获取自己的文件，管理员可获取所有
        if (!SecurityUtils.isAdmin()) {
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .eq(File::getUid, userId)
                    .eq(File::getFileHash, fileHash);
            if (this.count(wrapper) == 0) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
            }
        }

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
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        if (fileContentMap == null || fileContentMap.isEmpty()) {
            throw new GeneralException(ResultEnum.FAIL, "文件内容格式解析失败");
        }
        Result<List<byte[]>> fileListResult = fileRemoteClient.getFileListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(fileListResult);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String generateSharingCode(Long userId, List<String> fileHash, Integer expireMinutes, Integer shareType) {
        // 验证参数
        if (CommonUtils.isEmpty(fileHash)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "文件列表不能为空");
        }
        if (expireMinutes == null || expireMinutes <= 0) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "过期时间必须为正数");
        }

        // 去重后验证用户拥有所有要分享的文件（避免重复 hash 导致误报）
        List<String> distinctHashes = fileHash.stream().distinct().toList();
        LambdaQueryWrapper<File> ownershipWrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, userId)
                .in(File::getFileHash, distinctHashes);
        long ownedCount = this.count(ownershipWrapper);
        if (ownedCount != distinctHashes.size()) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权分享部分文件");
        }

        // 调用区块链生成分享码（核心能力：失败则直接返回错误，不做降级）
        Result<String> result = fileRemoteClient.shareFiles(ShareFilesRequest.builder()
                .uploader(String.valueOf(userId))
                .fileHashList(fileHash)
                .expireMinutes(expireMinutes)
                .build());
        String sharingCode = ResultUtils.getData(result);
        if (CommonUtils.isEmpty(sharingCode)) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "区块链返回的分享码为空");
        }

        // 同步写入数据库
        Long tenantId = TenantContext.getTenantId();
        Date expireTime = new Date(System.currentTimeMillis() + (long) expireMinutes * 60 * 1000L);

        // 分享码全局唯一（跨租户），提前检测避免唯一索引冲突导致 500
        boolean exists = TenantContext.runWithoutIsolation(() -> fileShareMapper.selectByShareCode(sharingCode) != null);
        if (exists) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "分享码冲突，请重试");
        }

        FileShare fileShare = new FileShare()
                .setTenantId(tenantId != null ? tenantId : 0L)
                .setUserId(userId)
                .setShareCode(sharingCode)
                .setShareType(shareType != null ? shareType : ShareType.PUBLIC.getCode())
                .setFileHashes(JsonConverter.toJson(fileHash))
                .setExpireTime(expireTime)
                .setAccessCount(0)
                .setStatus(FileShare.STATUS_ACTIVE)
                .setCreateTime(new Date());

        fileShareMapper.insert(fileShare);
        log.info("分享码已生成: userId={}, shareCode={}, shareType={}, fileCount={}",
                userId, sharingCode, ShareType.fromCode(fileShare.getShareType()).getName(), fileHash.size());

        return sharingCode;
    }

    /**
     * 根据分享码获取分享文件列表，并按过期时间判定取消/过期状态
     */
    @Override
    public List<File> getShareFile(String sharingCode) {
        Result<SharingVO> result = fileRemoteClient.getSharedFiles(sharingCode);
        if(ResultUtils.isSuccess(result)){
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
            if(CommonUtils.isNotEmpty(fileHashList)){
                try {
                    Long uploaderId = Long.valueOf(uploader);
                    LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                            .eq(File::getUid, uploaderId)
                            .in(File::getFileHash, fileHashList);
                    return this.list(wrapper);
                } catch (NumberFormatException e) {
                    log.warn("分享文件的上传者ID格式不正确: " + uploader);
                }
            }
        }
        return List.of();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void saveShareFile(List<String> sharingFileIdList, String shareCode, String clientIp) {
        if (CommonUtils.isEmpty(sharingFileIdList)) {
            return;
        }

        // 获取当前登录用户ID，未登录时抛出异常
        String userIdStr = MDC.get(Const.ATTR_USER_ID);
        if (CommonUtils.isEmpty(userIdStr)) {
            throw new GeneralException(ResultEnum.USER_NOT_LOGGED_IN, "用户未登录，无法保存分享文件");
        }

        Long userId;
        try {
            userId = Long.valueOf(userIdStr);
        } catch (NumberFormatException e) {
            log.error("MDC 中的用户ID格式非法: {}", userIdStr);
            throw new GeneralException(ResultEnum.PARAM_ERROR, "用户ID格式非法");
        }

        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .in(File::getId, sharingFileIdList);
        List<File> fileList = this.list(wrapper);

        if (CommonUtils.isEmpty(fileList)) {
            log.warn("未找到指定的分享文件: ids={}", sharingFileIdList);
            return;
        }

        // 过滤掉自己的文件，只保存其他用户分享的文件
        final Long currentUserId = userId;
        fileList = fileList.stream()
                .filter(file -> !currentUserId.equals(file.getUid()))
                .toList();

        if (CommonUtils.isEmpty(fileList)) {
            throw new GeneralException(ResultEnum.PARAM_ERROR, "不能保存自己的文件");
        }

        // 构建需要插入的 FileSource 记录列表
        List<FileSource> fileSources = new ArrayList<>();

        // 拷贝其它用户分享文件对应的信息，修改文件所有人并增加文件来源
        for (File file : fileList) {
            Long sourceFileId = file.getId();
            Long sourceUserId = file.getUid();
            Long originFileId = file.getOrigin();

            // 如果源文件已经有来源，则保留最初的文件所有人；否则源文件就是原始文件
            if (originFileId == null) {
                originFileId = sourceFileId;
            }

            // 计算链路深度：查询源文件的深度并加1
            int depth = 1;
            FileSource sourceFileSource = fileSourceMapper.selectByFileId(sourceFileId, file.getTenantId());
            if (sourceFileSource != null) {
                depth = sourceFileSource.getDepth() + 1;
            }

            // 设置文件属性
            file.setOrigin(originFileId)
                .setSharedFromUserId(sourceUserId)
                .setUid(userId)
                .setId(null)
                .setCreateTime(null);

            // 先保存文件以获取新ID
            this.save(file);

            // 创建 FileSource 记录
            FileSource fileSource = new FileSource()
                    .setFileId(file.getId())
                    .setOriginFileId(originFileId)
                    .setSourceFileId(sourceFileId)
                    .setSourceUserId(sourceUserId)
                    .setShareCode(shareCode)
                    .setDepth(depth);
            fileSources.add(fileSource);

            // 记录审计日志
            if (CommonUtils.isNotEmpty(shareCode)) {
                shareAuditService.logShareSave(shareCode, userId, file.getFileHash(), file.getFileName(), clientIp);
            }
        }

        // 批量保存 FileSource 记录
        if (CommonUtils.isNotEmpty(fileSources)) {
            for (FileSource fs : fileSources) {
                fileSourceMapper.insert(fs);
            }
        }

        log.info("成功保存分享文件: userId={}, 文件数量={}, shareCode={}", userId, fileList.size(), shareCode);
    }

    @Override
    public FileDecryptInfoVO getFileDecryptInfo(Long userId, String fileHash) {
        // 校验文件所有权：用户只能获取自己的文件解密信息，管理员可获取所有
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getFileHash, fileHash);

        if (!SecurityUtils.isAdmin()) {
            wrapper.eq(File::getUid, userId);
        }

        File file = this.getOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "文件不存在或无权限访问");
        }

        // 解析 fileParam JSON 获取解密信息
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

        // 从数据库查询用户分享记录
        LambdaQueryWrapper<FileShare> wrapper = new LambdaQueryWrapper<FileShare>()
                .eq(tenantId != null, FileShare::getTenantId, tenantId)
                .eq(FileShare::getUserId, userId)
                .orderByDesc(FileShare::getCreateTime);

        Page<FileShare> sharePage = new Page<>(page.getCurrent(), page.getSize());
        fileShareMapper.selectPage(sharePage, wrapper);

        // 转换为 FileShareVO
        List<FileShareVO> shareVOList = new ArrayList<>();
        for (FileShare share : sharePage.getRecords()) {
            FileShareVO vo = convertFileShareToVO(share);

            // 查询文件名列表
            List<String> fileHashes = parseFileHashes(share.getFileHashes());
            if (CommonUtils.isNotEmpty(fileHashes)) {
                LambdaQueryWrapper<File> fileWrapper = new LambdaQueryWrapper<File>()
                        .eq(File::getUid, userId)
                        .in(File::getFileHash, fileHashes);
                List<File> files = this.list(fileWrapper);
                vo.setFileNames(files.stream().map(File::getFileName).toList());
            }

            shareVOList.add(vo);
        }

        Page<FileShareVO> result = new Page<>(page.getCurrent(), page.getSize());
        result.setRecords(shareVOList);
        result.setTotal(sharePage.getTotal());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = "sharedFiles", key = "#shareCode")
    public void cancelShare(Long userId, String shareCode) {
        // 先验证分享是否属于该用户（从数据库查询）
        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享记录不存在");
        }

        // 权限校验
        if (!userId.equals(fileShare.getUserId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权操作此分享");
        }

        // 检查是否已取消
        if (fileShare.getStatus() == FileShare.STATUS_CANCELLED) {
            throw new GeneralException(ResultEnum.FAIL, "分享已被取消");
        }

        // 调用区块链取消分享（核心能力：失败则直接返回错误，不做降级）
        Result<Boolean> result = fileRemoteClient.cancelShare(
                CancelShareRequest.builder()
                        .shareCode(shareCode)
                        .uploader(String.valueOf(userId))
                        .build());
        if (!ResultUtils.isSuccess(result) || !Boolean.TRUE.equals(ResultUtils.getData(result))) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "取消分享失败");
        }

        // 同步更新数据库状态
        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareCode, shareCode)
                .set(FileShare::getStatus, FileShare.STATUS_CANCELLED);
        fileShareMapper.update(null, wrapper);

        log.info("分享已取消: userId={}, shareCode={}", userId, shareCode);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateShare(Long userId, UpdateShareVO updateVO) {
        FileShare fileShare = fileShareMapper.selectByShareCode(updateVO.getShareCode());
        if (fileShare == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享记录不存在");
        }

        // 权限校验
        if (!userId.equals(fileShare.getUserId())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权操作此分享");
        }

        // 检查是否已取消
        if (fileShare.getStatus() == FileShare.STATUS_CANCELLED) {
            throw new GeneralException(ResultEnum.FAIL, "分享已被取消，无法修改");
        }

        // 构建更新条件
        LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<FileShare>()
                .eq(FileShare::getShareCode, updateVO.getShareCode());

        // 更新分享类型
        if (updateVO.getShareType() != null) {
            wrapper.set(FileShare::getShareType, updateVO.getShareType());
        }

        // 延长有效期（从当前时间开始计算）
        if (updateVO.getExtendMinutes() != null && updateVO.getExtendMinutes() > 0) {
            Date newExpireTime = new Date(System.currentTimeMillis() + (long) updateVO.getExtendMinutes() * 60 * 1000L);
            wrapper.set(FileShare::getExpireTime, newExpireTime);
            // 如果已过期，重新激活
            if (fileShare.getStatus() == FileShare.STATUS_EXPIRED) {
                wrapper.set(FileShare::getStatus, FileShare.STATUS_ACTIVE);
            }
        }

        fileShareMapper.update(null, wrapper);
        log.info("分享设置已更新: userId={}, shareCode={}", userId, updateVO.getShareCode());
    }

    @Override
    public FileShare getShareByCode(String shareCode) {
        // 原子操作：先尝试将过期分享标记为过期状态，返回更新条数
        int expiredCount = fileShareMapper.markAsExpiredIfNecessary(shareCode);

        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare != null) {
            // 如果刚刚被标记为过期，同步更新内存对象状态
            if (expiredCount > 0) {
                fileShare.setStatus(FileShare.STATUS_EXPIRED);
            }
            // 原子操作：仅当分享处于活跃状态时增加访问计数
            // 避免 TOCTOU 竞态条件
            if (fileShare.getStatus() == FileShare.STATUS_ACTIVE) {
                fileShareMapper.incrementAccessCountIfActive(shareCode);
            }
        }
        return fileShare;
    }

    @Override
    public List<byte[]> getPublicFile(String shareCode, String fileHash) {
        // 验证分享有效性（公开分享）
        ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, ShareType.PUBLIC);

        // 使用 owner 的身份获取文件
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(String.valueOf(accessContext.ownerId()), fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情");
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
                fileContentMap.keySet().stream().toList());
        List<byte[]> files = ResultUtils.getData(fileListResult);
        incrementShareAccessCount(accessContext);
        return files;
    }

    @Override
    public FileDecryptInfoVO getPublicFileDecryptInfo(String shareCode, String fileHash) {
        // 验证分享有效性（公开分享）
        ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, ShareType.PUBLIC);

        // 查询文件元数据
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, accessContext.ownerId())
                .eq(File::getFileHash, fileHash);
        File file = this.getOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.FAIL, "文件不存在");
        }

        // 解析 fileParam JSON 获取解密信息
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

    /**
     * 登录用户通过分享码下载文件（支持私密/公开分享）
     */
    @Override
    public List<byte[]> getSharedFileContent(Long userId, String shareCode, String fileHash) {
        // 验证分享有效性（允许公开/私密）
        ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, null);

        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(String.valueOf(accessContext.ownerId()), fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        if (detailVO == null) {
            throw new GeneralException(ResultEnum.FAIL, "无法获取文件详情");
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
                fileContentMap.keySet().stream().toList());
        List<byte[]> files = ResultUtils.getData(fileListResult);
        incrementShareAccessCount(accessContext);
        return files;
    }

    /**
     * 登录用户通过分享码获取解密信息（支持私密/公开分享）
     */
    @Override
    public FileDecryptInfoVO getSharedFileDecryptInfo(Long userId, String shareCode, String fileHash) {
        // 验证分享有效性（允许公开/私密）
        ShareAccessContext accessContext = resolveShareAccess(shareCode, fileHash, null);

        // 查询文件元数据
        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                .eq(File::getUid, accessContext.ownerId())
                .eq(File::getFileHash, fileHash);
        File file = this.getOne(wrapper);
        if (file == null) {
            throw new GeneralException(ResultEnum.FAIL, "文件不存在");
        }

        // 解析 fileParam JSON 获取解密信息
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

    /**
     * 验证分享访问权限并解析分享上下文（支持数据库与区块链回退）
     */
    private ShareAccessContext resolveShareAccess(String shareCode, String fileHash, ShareType requiredType) {
        FileShare fileShare = fileShareMapper.selectByShareCode(shareCode);
        if (fileShare != null) {
            ShareType actualType = ShareType.fromCode(fileShare.getShareType());
            if (requiredType != null && actualType != requiredType) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "此分享需要登录后才能访问");
            }

            if (fileShare.getStatus() == FileShare.STATUS_CANCELLED) {
                throw new GeneralException(ResultEnum.FAIL, "分享已被取消");
            }

            Date now = new Date();
            if (fileShare.getExpireTime() != null && fileShare.getExpireTime().before(now)) {
                if (fileShare.getStatus() != FileShare.STATUS_EXPIRED) {
                    LambdaUpdateWrapper<FileShare> wrapper = new LambdaUpdateWrapper<FileShare>()
                            .eq(FileShare::getShareCode, shareCode)
                            .set(FileShare::getStatus, FileShare.STATUS_EXPIRED);
                    fileShareMapper.update(null, wrapper);
                }
                throw new GeneralException(ResultEnum.SHARE_EXPIRED);
            }

            List<String> fileHashes = parseFileHashes(fileShare.getFileHashes());
            if (!fileHashes.contains(fileHash)) {
                throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "该文件不在此分享中");
            }

            return new ShareAccessContext(fileShare.getUserId(), fileShare);
        }

        // 数据库未命中时回退到区块链分享信息（兼容历史分享）
        Result<SharingVO> shareInfoResult = fileRemoteClient.getShareInfo(shareCode);
        SharingVO shareInfo = ResultUtils.getData(shareInfoResult);
        if (shareInfo == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享不存在");
        }

        if (shareInfo.getIsValid() != null && !shareInfo.getIsValid()) {
            throw new GeneralException(ResultEnum.SHARE_CANCELLED);
        }

        if (shareInfo.getExpirationTime() != null && shareInfo.getExpirationTime() < System.currentTimeMillis()) {
            throw new GeneralException(ResultEnum.SHARE_EXPIRED);
        }

        List<String> fileHashes = shareInfo.getFileHashList() != null ? shareInfo.getFileHashList() : List.of();
        if (!fileHashes.contains(fileHash)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "该文件不在此分享中");
        }

        Long ownerId;
        try {
            ownerId = Long.valueOf(shareInfo.getUploader());
        } catch (NumberFormatException e) {
            log.warn("分享上传者ID格式不正确: {}", shareInfo.getUploader());
            throw new GeneralException(ResultEnum.FAIL, "分享数据异常");
        }

        return new ShareAccessContext(ownerId, null);
    }

    /**
     * 增加分享访问次数（仅数据库记录存在时）
     */
    private void incrementShareAccessCount(ShareAccessContext accessContext) {
        if (accessContext.fileShare() != null) {
            fileShareMapper.incrementAccessCount(accessContext.fileShare().getShareCode());
        }
    }

    /**
     * 将 FileShare 实体转换为 FileShareVO
     */
    private FileShareVO convertFileShareToVO(FileShare fileShare) {
        FileShareVO vo = new FileShareVO();
        vo.setSharingCode(fileShare.getShareCode());
        vo.setFileHashes(parseFileHashes(fileShare.getFileHashes()));
        vo.setAccessCount(fileShare.getAccessCount());
        vo.setExpireTime(fileShare.getExpireTime());
        vo.setCreateTime(fileShare.getCreateTime());
        vo.setShareType(fileShare.getShareType());
        vo.setShareTypeDesc(ShareType.fromCode(fileShare.getShareType()).getName());

        // 设置状态
        int status = fileShare.getStatus();
        // 检查是否已过期
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

    /**
     * 分享访问上下文（包含分享拥有者与可选的数据库记录）
     */
    private record ShareAccessContext(Long ownerId, FileShare fileShare) {
    }
}
