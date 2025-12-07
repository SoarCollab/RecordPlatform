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
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.service.remote.FileRemoteClient;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileService;
import cn.flying.service.saga.FileSagaOrchestrator;
import cn.flying.service.saga.FileUploadCommand;
import cn.flying.service.saga.FileUploadResult;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @program: RecordPlatform
 * @description: 文件服务实现类
 * @author: flyingcoding
 * @create: 2025-03-12 21:22
 */
@Slf4j
@Service
@Transactional(rollbackFor = Exception.class)
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {

    @Resource
    private FileRemoteClient fileRemoteClient;

    @Resource
    private FileSagaOrchestrator sagaOrchestrator;

    @Override
    public void prepareStoreFile(Long userId, String OriginFileName) {
        File file = new File()
                .setUid(userId)
                .setFileName(OriginFileName)
                .setStatus(FileUploadStatus.PREPARE.getCode());
        this.saveOrUpdate(file);
    }

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

        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, userId)
                .eq(File::getFileName, OriginFileName);

        File file = new File()
                .setUid(userId)
                .setFileName(OriginFileName)
                .setFileHash(result.getFileHash())
                .setTransactionHash(result.getTransactionHash())
                .setFileParam(fileParam)
                .setStatus(FileUploadStatus.SUCCESS.getCode());

        this.update(file, wrapper);
        return file;
    }

    @Override
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
    @CacheEvict(cacheNames = "userFiles", key = "#userId")
    public void deleteFile(Long userId, List<String> fileHashList) {
        if(CommonUtils.isEmpty(fileHashList)) return;
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, userId)
                .in(File::getFileHash, fileHashList);
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
        String userIdStr = String.valueOf(userId);
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(userIdStr, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
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
        String userIdStr = String.valueOf(userId);
        Result<FileDetailVO> filePointer = fileRemoteClient.getFile(userIdStr, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<byte[]>> fileListResult = fileRemoteClient.getFileListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(fileListResult);
    }

    @Override
    public String generateSharingCode(Long userId, List<String> fileHash, Integer maxAccesses) {
        Result<String> result = fileRemoteClient.shareFiles(String.valueOf(userId), fileHash, maxAccesses);
        return ResultUtils.getData(result);
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
    public void saveShareFile(List<String> sharingFileIdList) {
        if (CommonUtils.isEmpty(sharingFileIdList)) {
            return;
        }

        // 获取当前登录用户ID，未登录时抛出异常
        String userIdStr = MDC.get(Const.ATTR_USER_ID);
        if (CommonUtils.isEmpty(userIdStr)) {
            throw new GeneralException(ResultEnum.USER_NOT_LOGIN, "用户未登录，无法保存分享文件");
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
}
