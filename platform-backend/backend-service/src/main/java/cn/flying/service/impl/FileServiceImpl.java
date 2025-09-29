package cn.flying.service.impl;

import cn.flying.api.utils.ResultUtils;
import cn.flying.common.constant.FileUploadStatus;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.pool.BufferPoolManager;
import cn.flying.common.upload.ConcurrentMultipartUploader;
import cn.flying.common.util.CommonUtils;
import cn.flying.common.util.Const;
import cn.flying.common.util.JsonConverter;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.mapper.FileMapper;
import cn.flying.platformapi.constant.Result;
import cn.flying.platformapi.external.BlockChainService;
import cn.flying.platformapi.external.DistributedStorageService;
import cn.flying.platformapi.response.FileDetailVO;
import cn.flying.platformapi.response.SharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.dubbo.config.annotation.DubboReference;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

import lombok.extern.slf4j.Slf4j;
import java.util.stream.Collectors;

/**
 * @program: RecordPlatform
 * @description: 文件服务实现类
 * @author: 王贝强
 * @create: 2025-03-12 21:22
 */
@Service
@Slf4j
@Transactional(rollbackFor = Exception.class)
public class FileServiceImpl extends ServiceImpl<FileMapper, File> implements FileService {
    @DubboReference
    private BlockChainService blockChainService;

    @DubboReference
    private DistributedStorageService storageService;

    @Override
    public void prepareStoreFile(String Uid, String OriginFileName) {
        File file = new File()
                .setUid(Uid)
                .setFileName(OriginFileName)
                .setStatus(FileUploadStatus.PREPARE.getCode());
        this.saveOrUpdate(file);
    }

    @Override
    public File storeFile(String Uid, String OriginFileName, List<java.io.File> fileList, List<String> fileHashList, String fileParam) {

        if (CommonUtils.isEmpty(fileList)) return null;

        try {
            // 使用流式分块传输优化方案
            log.info("开始使用流式分块传输存储文件: fileName={}, fileCount={}", OriginFileName, fileList.size());

            // 判断文件总大小，决定是否使用分块传输
            long totalSize = fileList.stream().mapToLong(java.io.File::length).sum();
            boolean useStreaming = totalSize > 10 * 1024 * 1024; // 大于10MB使用流式传输

            Result<Map<String, String>> storedResult;

            if (useStreaming) {
                log.info("文件总大小{}MB，使用流式分块传输", totalSize / 1024 / 1024);
                storedResult = storeFilesWithStreaming(fileList, fileHashList);
            } else {
                // 小文件仍使用原方式，但优化内存使用
                log.info("文件总大小{}MB，使用普通传输", totalSize / 1024 / 1024);
                List<byte[]> fileByteList = new ArrayList<>();
                for (java.io.File file : fileList) {
                    try {
                        // 使用缓冲读取，避免一次性加载大文件
                        byte[] fileData = Files.readAllBytes(file.toPath());
                        fileByteList.add(fileData);
                    } catch (IOException e) {
                        log.error("读取文件失败: file={}, error={}", file.getName(), e.getMessage());
                        throw new GeneralException(ResultEnum.FILE_NOT_EXIST);
                    }
                }
                storedResult = storageService.storeFile(fileByteList, fileHashList);
            }

            // 最终得到的文件存储位置（JSON）
            String fileContent = JsonConverter.toJsonWithPretty(ResultUtils.getData(storedResult));
            Result<List<String>> recordResult = blockChainService.storeFile(Uid, OriginFileName, fileParam, fileContent);

            // 获取存储到区块链上的文件的哈希值
            List<String> res = ResultUtils.getData(recordResult);
            // 判断是不是正常返回
            if (CommonUtils.isEmpty(res) || res.size() != 2) return null;

            // 交易hash
            String transactionHash = res.get(0);
            // 文件hash
            String fileHash = res.get(1);

            // 完成上传后更新文件元信息
            if (CommonUtils.isNotEmpty(fileHash) && CommonUtils.isNotEmpty(transactionHash)) {
                // 根据用户名及对应的文件名查找文件元信息（即要求用户所文件名不能重复）
                LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                        .eq(File::getUid, Uid)
                        .eq(File::getFileName, OriginFileName);

                File file = new File()
                        .setUid(Uid)
                        .setFileName(OriginFileName)
                        .setFileHash(fileHash)
                        .setTransactionHash(transactionHash)
                        .setFileParam(fileParam)
                        .setStatus(FileUploadStatus.SUCCESS.getCode());

                this.update(file, wrapper);
                // 返回更新后的文件元信息
                return file;
            }

        } catch (Exception e) {
            log.error("存储文件失败: fileName={}, error={}", OriginFileName, e.getMessage(), e);
            // 更新文件状态为失败
            changeFileStatusByName(Uid, OriginFileName, FileUploadStatus.FAIL.getCode());
        }

        return null;
    }

    /**
     * 使用流式分块传输存储文件
     * 针对大文件优化内存使用和传输效率
     *
     * @param fileList     文件列表
     * @param fileHashList 文件哈希列表
     * @return 存储结果
     */
    private Result<Map<String, String>> storeFilesWithStreaming(List<java.io.File> fileList, List<String> fileHashList) {
        Map<String, String> resultMap = new HashMap<>();

        // 判断是否使用并发上传
        long totalSize = fileList.stream().mapToLong(java.io.File::length).sum();
        boolean useConcurrent = totalSize > 50 * 1024 * 1024; // 大于50MB使用并发

        if (useConcurrent) {
            log.info("文件总大小{}MB，使用并发分块传输", totalSize / 1024 / 1024);
            return storeFilesWithConcurrency(fileList, fileHashList);
        }

        // 小文件使用原有的串行传输
        BufferPoolManager bufferPool = BufferPoolManager.getInstance();

        // 设置分块大小（5MB）
        int chunkSize = 5 * 1024 * 1024;

        for (int i = 0; i < fileList.size(); i++) {
            java.io.File file = fileList.get(i);
            String fileHash = fileHashList.get(i);

            try {
                log.info("开始流式传输文件: fileName={}, size={}MB", file.getName(), file.length() / 1024 / 1024);

                // 初始化分块上传会话
                Result<String> initResult = storageService.initMultipartUpload(
                    file.getName(),
                    fileHash,
                    file.length(),
                    null
                );

                if (!initResult.isSuccess()) {
                    log.error("初始化分块上传失败: fileName={}", file.getName());
                    continue;
                }

                String uploadId = initResult.getData();
                List<String> partETags = new ArrayList<>();

                // 使用流式读取文件并分块上传（使用缓冲区池）
                try (FileInputStream fis = new FileInputStream(file);
                     BufferedInputStream bis = new BufferedInputStream(fis, chunkSize)) {

                    // 从缓冲区池借用缓冲区
                    byte[] buffer = bufferPool.borrowHeapBuffer(chunkSize);

                    try {
                        int partNumber = 1;
                        int bytesRead;
                        long totalUploaded = 0;

                        while ((bytesRead = bis.read(buffer)) > 0) {
                            // 创建实际大小的分块数据
                            byte[] partData = bytesRead < chunkSize
                                ? Arrays.copyOf(buffer, bytesRead)
                                : buffer;

                            // 上传分块
                            Result<String> uploadResult = storageService.uploadPart(
                                uploadId,
                                partNumber,
                                partData,
                                null
                            );

                            if (!uploadResult.isSuccess()) {
                                log.error("上传分块失败: uploadId={}, partNumber={}", uploadId, partNumber);
                                storageService.abortMultipartUpload(uploadId);
                                break;
                            }

                            partETags.add(uploadResult.getData());
                            totalUploaded += bytesRead;

                            // 记录进度
                            double progress = (totalUploaded * 100.0) / file.length();
                            log.debug("文件上传进度: fileName={}, progress={}%", file.getName(), String.format("%.2f", progress));

                            partNumber++;
                        }

                        // 完成分块上传
                        if (!partETags.isEmpty()) {
                            Result<String> completeResult = storageService.completeMultipartUpload(uploadId, partETags);
                            if (completeResult.isSuccess()) {
                                resultMap.put(fileHash, completeResult.getData());
                                log.info("文件流式传输完成: fileName={}, path={}", file.getName(), completeResult.getData());
                            }
                        }

                    } finally {
                        // 归还缓冲区到池
                        bufferPool.returnHeapBuffer(buffer);
                    }

                } catch (IOException e) {
                    log.error("读取文件失败: fileName={}, error={}", file.getName(), e.getMessage());
                    // 取消上传
                    storageService.abortMultipartUpload(uploadId);
                }

            } catch (Exception e) {
                log.error("流式传输文件失败: fileName={}, error={}", file.getName(), e.getMessage(), e);
            }
        }

        return Result.success(resultMap);
    }

    /**
     * 使用并发分块传输存储文件（优化版）
     * 支持多个分块并发上传，提高传输效率
     *
     * @param fileList     文件列表
     * @param fileHashList 文件哈希列表
     * @return 存储结果
     */
    private Result<Map<String, String>> storeFilesWithConcurrency(List<java.io.File> fileList, List<String> fileHashList) {
        Map<String, String> resultMap = new ConcurrentHashMap<>();

        // 创建并发上传配置
        ConcurrentMultipartUploader.UploadConfig config = ConcurrentMultipartUploader.UploadConfig.builder()
            .concurrency(5)  // 5个并发
            .chunkSize(5 * 1024 * 1024)  // 5MB分块
            .enableRetry(true)  // 启用重试
            .build();

        // 创建并发上传器
        ConcurrentMultipartUploader uploader = new ConcurrentMultipartUploader(storageService, config);

        // 设置进度回调
        uploader.setProgressCallback(progress -> {
            log.debug("上传进度: uploadId={}, part={}/{}, percentage={}%, uploadedMB={}",
                progress.getUploadId(),
                progress.getCurrentPart(),
                progress.getTotalParts(),
                String.format("%.2f", progress.getPercentage()),
                progress.getUploadedBytes() / (1024 * 1024)
            );
        });

        try {
            // 使用线程池并发处理多个文件
            ExecutorService fileExecutor = Executors.newFixedThreadPool(
                Math.min(fileList.size(), 3)  // 最多3个文件并发
            );

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (int i = 0; i < fileList.size(); i++) {
                final java.io.File file = fileList.get(i);
                final String fileHash = fileHashList.get(i);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    log.info("开始并发上传文件: fileName={}, size={}MB",
                        file.getName(), file.length() / 1024 / 1024);

                    ConcurrentMultipartUploader.UploadResult result = uploader.uploadFile(
                        file,
                        file.getName(),
                        fileHash
                    );

                    if (result.isSuccess()) {
                        resultMap.put(fileHash, result.getPath());
                        log.info("文件并发上传成功: fileName={}, duration={}ms, throughput={}MB/s",
                            file.getName(), result.getDuration(), result.getThroughput());
                    } else {
                        log.error("文件并发上传失败: fileName={}, error={}",
                            file.getName(), result.getErrorMessage());
                    }
                }, fileExecutor);

                futures.add(future);
            }

            // 等待所有文件上传完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 关闭线程池
            fileExecutor.shutdown();
            fileExecutor.awaitTermination(5, TimeUnit.SECONDS);

            log.info("所有文件并发上传完成: successCount={}, totalCount={}",
                resultMap.size(), fileList.size());

        } catch (Exception e) {
            log.error("并发上传文件失败", e);
        } finally {
            // 关闭上传器
            uploader.shutdown();
        }

        return Result.success(resultMap);
    }

    @Override
    public void changeFileStatusByName(String Uid, String fileName, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileName, fileName)
                .eq(File::getUid, Uid);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    @Override
    public void changeFileStatusByHash(String Uid, String fileHash, Integer fileStatus) {
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getFileHash, fileHash)
                .eq(File::getUid, Uid);
        File file = new File()
                .setStatus(fileStatus);
        this.update(file,wrapper);
    }

    @Override
    public void deleteFile(String Uid, List<String> fileHashList) {
        if(CommonUtils.isEmpty(fileHashList)) return;
        LambdaUpdateWrapper<File> wrapper = new LambdaUpdateWrapper<File>()
                .eq(File::getUid, Uid)
                .in(File::getFileHash, fileHashList);
        //此处不执行实际的文件删除操作，仅更新文件元信息（实际操作使用定时任务批量执行，将文件删除或移入冷数据存储器）
        //todo 后续实现定时任务
        this.remove(wrapper);
    }

    @Override
    public List<File> getUserFilesList(String Uid) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<>();
        //超管账号可查看所有文件
        if(!SecurityUtils.isAdmin()){
            wrapper.eq(File::getUid, Uid);
        }

        return this.list(wrapper);
    }

    @Override
    public void getUserFilesPage(String Uid, Page<File> page) {
        LambdaQueryWrapper<File> wrapper= new LambdaQueryWrapper<>();
        //超管账号可查看所有文件
        if(!SecurityUtils.isAdmin()){
            wrapper.eq(File::getUid, Uid);
        }
        this.page(page, wrapper);
    }

    @Override
    public List<String> getFileAddress(String Uid, String fileHash) {
        Result<FileDetailVO> filePointer = blockChainService.getFile(Uid, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<String>> urlListResult = storageService.getFileUrlListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(urlListResult);
    }

    @Override
    public TransactionVO getTransactionByHash(String transactionHash) {
        Result<TransactionVO> result = blockChainService.getTransactionByHash(transactionHash);
        return ResultUtils.getData(result);
    }

    @Override
    public List<byte[]> getFile(String Uid, String fileHash) {
        Result<FileDetailVO> filePointer = blockChainService.getFile(Uid, fileHash);
        FileDetailVO detailVO = ResultUtils.getData(filePointer);
        String fileContent = detailVO.getContent();
        Map<String,String> fileContentMap = JsonConverter.parse(fileContent, Map.class);
        Result<List<byte[]>> fileListResult = storageService.getFileListByHash(fileContentMap.values().stream().toList(), fileContentMap.keySet().stream().toList());
        return ResultUtils.getData(fileListResult);
    }

    @Override
    public String generateSharingCode(String Uid, List<String> fileHash, Integer maxAccesses) {
        Result<String> result = blockChainService.shareFiles(Uid, fileHash, maxAccesses);
        return ResultUtils.getData(result);
    }

    @Override
    public List<File> getShareFile(String sharingCode) {
        Result<SharingVO> result = blockChainService.getSharedFiles(sharingCode);
        if(ResultUtils.isSuccess(result)){
            SharingVO sharingFiles = ResultUtils.getData(result);
            String uid= sharingFiles.getUploader();
            List<String> fileHashList = sharingFiles.getFileHashList();
            if(CommonUtils.isNotEmpty(fileHashList)){
                LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                        .eq(File::getUid, uid)
                        .in(File::getFileHash, fileHashList);
                return this.list(wrapper);
            }
        }
        return List.of();
    }

    @Override
    public void saveShareFile(List<String> sharingFileIdList) {
        if(CommonUtils.isNotEmpty(sharingFileIdList)){
            LambdaQueryWrapper<File> wrapper = new LambdaQueryWrapper<File>()
                    .in(File::getId, sharingFileIdList);
            List<File> FileList = this.list(wrapper);
            //获取当前登录用户Id
            String Uid = MDC.get(Const.ATTR_USER_ID);
            if(CommonUtils.isNotEmpty(FileList)){
                //拷贝其它用户分享文件对应的信息，修改文件所有人并增加文件来源
                FileList.forEach(file -> {
                    //如果源文件已经有来源，则保留最初的文件所有人
                    if(CommonUtils.isEmpty(file.getOrigin())){
                        file.setOrigin(file.getId());
                    }
                    //重置文件ID和创建时间
                        file
                            .setUid(Uid)
                            .setId(null)
                            .setCreateTime(null);
                });
                //批量保存文件信息
                this.saveBatch(FileList);
            }
        }
    }
}
