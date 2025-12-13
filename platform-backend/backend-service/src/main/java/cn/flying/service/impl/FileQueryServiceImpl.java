package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileShareVO;
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
 * @author flying
 * @since 1.0.0
 */
@Slf4j
@Service
public class FileQueryServiceImpl implements FileQueryService {

    @Resource
    private FileMapper fileMapper;

    @Resource
    private FileRemoteClient fileRemoteClient;

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

    @Override
    @Cacheable(cacheNames = "sharedFiles", key = "#sharingCode", unless = "#result == null || #result.isEmpty()")
    public List<File> getShareFile(String sharingCode) {
        Result<SharingVO> result = fileRemoteClient.getSharedFiles(sharingCode);
        if (ResultUtils.isSuccess(result)) {
            SharingVO sharingFiles = ResultUtils.getData(result);
            String uploader = sharingFiles.getUploader();
            List<String> fileHashList = sharingFiles.getFileHashList();
            if (CommonUtils.isNotEmpty(fileHashList)) {
                try {
                    Long uploaderId = Long.valueOf(uploader);
                    LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                            .eq(File::getUid, uploaderId)
                            .in(File::getFileHash, fileHashList);
                    return fileMapper.selectList(wrapper);
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
                        List<File> files = fileMapper.selectList(wrapper);
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
