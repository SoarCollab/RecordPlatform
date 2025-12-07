package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.vo.file.FileUploadStatusVO;
import cn.flying.dao.vo.file.ProgressVO;
import cn.flying.dao.vo.file.ResumeUploadVO;
import cn.flying.dao.vo.file.StartUploadVO;
import cn.flying.service.FileUploadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


/**
 * @program: RecordPlatform
 * @description: 文件分片上传、断点续传控制器
 * @author: flyingcoding
 * @create: 2025-03-31 11:22 (Refactored: YYYY-MM-DD)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files/upload")
@Tag(name = "文件分片上传相关接口", description = "包括文件开始上传、检查上传状态、暂停上传、取消上传等操作。")
public class FileUploadController {

    @Resource
    private FileUploadService fileUploadService;

    @PostMapping("/upload/start")
    @Operation(summary = "开始上传")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "开始上传文件")
    public Result<StartUploadVO> startUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件名") @RequestParam("fileName") String fileName,
            @Schema(description = "文件大小") @RequestParam("fileSize") long fileSize,
            @Schema(description = "文件类型") @RequestParam(value = "contentType") String contentType,
            @Schema(description = "客户端ID") @RequestParam(value = "clientId", required = false) String providedClientId,
            @Schema(description = "分片大小") @RequestParam(value = "chunkSize") int chunkSize,
            @Schema(description = "分片总数") @RequestParam(value = "totalChunks") int totalChunks) {

        StartUploadVO uploadVO = fileUploadService.startUpload(userId,fileName, fileSize, contentType, providedClientId,chunkSize,totalChunks);
        return Result.success(uploadVO);
    }

    @PostMapping("/upload/chunk")
    @Operation(summary = "上传分片文件")
    public Result<String> uploadChunk(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "上传的文件") @RequestParam("file") MultipartFile file,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId,
            @Schema(description = "分片序号") @RequestParam("chunkNumber") int chunkNumber) {

        fileUploadService.uploadChunk(userId, clientId, chunkNumber, file);
        return Result.success("分片上传成功");
        
    }

    @PostMapping("/upload/complete")
    @Operation(summary = "完成文件上传处理")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "完成文件上传处理")
    public Result<String> completeUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        fileUploadService.completeUpload(userId,clientId);
        return Result.success("文件处理完成");

    }

    @PostMapping("/upload/pause")
    @Operation(summary = "暂停上传")
    public Result<String> pauseUpload(@Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {
    
        fileUploadService.pauseUpload(clientId);
        return Result.success("上传已暂停");
    
    }

    @PostMapping("/upload/resume")
    @Operation(summary = "恢复上传")
    public Result<ResumeUploadVO> resumeUpload(@Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {
        
        ResumeUploadVO response = fileUploadService.resumeUpload(clientId);
        return Result.success(response);
        
    }

    @PostMapping("/upload/cancel")
    @Operation(summary = "取消上传")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "取消文件上传")
    public Result<String> cancelUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {
        boolean cancelled = fileUploadService.cancelUpload(userId,clientId);
        if (cancelled) {
            log.info("上传已取消并清理: 客户端ID={}", clientId);
            return Result.success("上传已取消");
        }
        return Result.error("上传文件未找到");
    }

    @GetMapping("/upload/check")
    @Operation(summary = "检查上传状态")
    public Result<FileUploadStatusVO> checkFileStatus(@Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        FileUploadStatusVO statusVO = fileUploadService.checkFileStatus(clientId);
        return Result.success(statusVO);

    }

    @GetMapping("/upload/progress")
    @Operation(summary = "获取上传进度")
    public Result<ProgressVO> getUploadProgress(@Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        ProgressVO progressVO = fileUploadService.getUploadProgress(clientId);
        return Result.success(progressVO);

    }
}
