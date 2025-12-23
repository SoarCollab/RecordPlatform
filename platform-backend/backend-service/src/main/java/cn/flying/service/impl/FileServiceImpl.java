package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.request.CancelShareRequest;
import cn.flying.platformapi.request.ShareFilesRequest;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileService;
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
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
     * 存储文件：执行 Saga 流程（MinIO + 区块链）
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
        if(CommonUtils.isEmpty(identifiers)) return;
        
        // 支持同时按 fileHash 或 ID 匹配删除
        // 这样前端传入 file.id 时，对于 fileHash 为 null 的失败文件也能正确删除
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, userId)
                .and(w -> w
                        .in(File::getFileHash, identifiers)
                        .or()
                        .in(File::getId, identifiers)
                );
        // Logical delete only - physical cleanup is handled by FileCleanupTask scheduled job
        this.remove(wrapper);
    }

    @Override
    @Cacheable(cacheNames = "userFiles", key = "#userId", unless = "#result == null || #result.isEmpty()")
    public List<File> getUserFilesList(Long userId) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<>();
        //超管账号可查看所有文件
        if(!SecurityUtils.isAdmin()){
            wrapper.eq(File::getUid, userId);
        }

        return this.list(wrapper);
    }

    @Override
    public void getUserFilesPage(Long userId, Page<File> page) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<>();
        //超管账号可查看所有文件
        if(!SecurityUtils.isAdmin()){
            wrapper.eq(File::getUid, userId);
        }
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
    public String generateSharingCode(Long userId, List<String> fileHash, Integer expireMinutes) {
        // 调用区块链生成分享码
        Result<String> result = fileRemoteClient.shareFiles(ShareFilesRequest.builder()
                .uploader(String.valueOf(userId))
                .fileHashList(fileHash)
                .expireMinutes(expireMinutes)
                .build());
        String sharingCode = ResultUtils.getData(result);

        if (CommonUtils.isNotEmpty(sharingCode)) {
            log.info("分享码已生成（链上）: userId={}, sharingCode={}, fileCount={}", userId, sharingCode, fileHash.size());
        }

        return sharingCode;
    }

    @Override
    public List<File> getShareFile(String sharingCode) {
        Result<SharingVO> result = fileRemoteClient.getSharedFiles(sharingCode);
        if(ResultUtils.isSuccess(result)){
            SharingVO sharingFiles = ResultUtils.getData(result);
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
    public void saveShareFile(List<String> sharingFileIdList) {
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

        // 拷贝其它用户分享文件对应的信息，修改文件所有人并增加文件来源
        fileList.forEach(file -> {
            // 如果源文件已经有来源，则保留最初的文件所有人
            if (CommonUtils.isEmpty(file.getOrigin())) {
                file.setOrigin(file.getId());
            }
            // 重置文件ID和创建时间
            file.setUid(userId)
                .setId(null)
                .setCreateTime(null);
        });

        // 批量保存文件信息
        this.saveBatch(fileList);
        log.info("成功保存分享文件: userId={}, 文件数量={}", userId, fileList.size());
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
        // 从区块链获取用户分享码列表
        String uploader = String.valueOf(userId);
        Result<List<String>> shareCodesResult = fileRemoteClient.getUserShareCodes(uploader);
        List<String> shareCodes = ResultUtils.getData(shareCodesResult);

        if (CommonUtils.isEmpty(shareCodes)) {
            return new Page<>(page.getCurrent(), page.getSize());
        }

        // 获取每个分享码的详细信息
        List<FileShareVO> shareList = new ArrayList<>();
        for (String shareCode : shareCodes) {
            try {
                Result<SharingVO> shareInfoResult = fileRemoteClient.getShareInfo(shareCode);
                SharingVO shareInfo = ResultUtils.getData(shareInfoResult);
                if (shareInfo != null) {
                    FileShareVO vo = convertSharingVOToFileShareVO(shareInfo, shareCode);
                    // 查询文件名列表
                    if (CommonUtils.isNotEmpty(shareInfo.getFileHashList())) {
                        LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                                .eq(File::getUid, userId)
                                .in(File::getFileHash, shareInfo.getFileHashList());
                        List<File> files = this.list(wrapper);
                        vo.setFileNames(files.stream().map(File::getFileName).toList());
                    }
                    shareList.add(vo);
                }
            } catch (Exception e) {
                log.warn("获取分享详情失败: shareCode={}, error={}", shareCode, e.getMessage());
            }
        }

        // 手动分页
        int start = (int) ((page.getCurrent() - 1) * page.getSize());
        int end = Math.min(start + (int) page.getSize(), shareList.size());
        List<FileShareVO> pagedList = start < shareList.size() ? shareList.subList(start, end) : List.of();

        Page<FileShareVO> result = new Page<>(page.getCurrent(), page.getSize());
        result.setRecords(pagedList);
        result.setTotal(shareList.size());
        return result;
    }

    @Override
    public void cancelShare(Long userId, String shareCode) {
        // 先验证分享是否属于该用户
        Result<SharingVO> shareInfoResult = fileRemoteClient.getShareInfo(shareCode);
        SharingVO shareInfo = ResultUtils.getData(shareInfoResult);

        if (shareInfo == null) {
            throw new GeneralException(ResultEnum.FAIL, "分享记录不存在");
        }

        // 权限校验：验证分享是否属于该用户
        if (!String.valueOf(userId).equals(shareInfo.getUploader())) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED, "无权操作此分享");
        }

        // 检查是否已取消
        if (shareInfo.getIsValid() != null && !shareInfo.getIsValid()) {
            throw new GeneralException(ResultEnum.FAIL, "分享已被取消");
        }

        // 调用区块链取消分享
        Result<Boolean> result = fileRemoteClient.cancelShare(
                CancelShareRequest.builder()
                        .shareCode(shareCode)
                        .uploader(String.valueOf(userId))
                        .build());

        if (!ResultUtils.isSuccess(result) || !Boolean.TRUE.equals(ResultUtils.getData(result))) {
            throw new GeneralException(ResultEnum.BLOCKCHAIN_ERROR, "取消分享失败");
        }

        log.info("分享已取消（链上）: userId={}, shareCode={}", userId, shareCode);
    }

    /**
     * 将区块链 SharingVO 转换为 FileShareVO
     */
    private FileShareVO convertSharingVOToFileShareVO(SharingVO sharingVO, String shareCode) {
        FileShareVO vo = new FileShareVO();
        vo.setSharingCode(shareCode);
        vo.setFileHashes(sharingVO.getFileHashList());
        vo.setMaxAccesses(sharingVO.getMaxAccesses());
        vo.setIsValid(sharingVO.getIsValid());

        // 根据 isValid 设置状态
        if (sharingVO.getIsValid() != null && sharingVO.getIsValid()) {
            // 检查是否过期
            if (sharingVO.getExpirationTime() != null && sharingVO.getExpirationTime() < System.currentTimeMillis()) {
                vo.setStatus(2); // 已过期
                vo.setStatusDesc("已过期");
            } else {
                vo.setStatus(1); // 有效
                vo.setStatusDesc("有效");
            }
        } else {
            vo.setStatus(0); // 已取消
            vo.setStatusDesc("已取消");
        }

        // 设置过期时间
        if (sharingVO.getExpirationTime() != null) {
            vo.setExpireTime(new Date(sharingVO.getExpirationTime()));
        }

        return vo;
    }
}
