package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
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
@RequestMapping("/api/file/uploader")
@Tag(name = "文件分片上传相关接口", description = "包括文件开始上传、检查上传状态、暂停上传、取消上传等操作。")
public class FileUploadController {

    @Resource
    private FileUploadService fileUploadService;

    @PostMapping("/upload/start")
    @Operation(summary = "开始上传")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "开始上传文件")
    public Result<StartUploadVO> startUpload(
            @Schema(description = "文件名") @RequestParam("fileName") String fileName,
            @Schema(description = "文件大小") @RequestParam("fileSize") long fileSize,
            @Schema(description = "文件类型") @RequestParam(value = "contentType", required = false) String contentType,
            @Schema(description = "客户端ID") @RequestParam(value = "clientId", required = false) String providedClientId,
            @Schema(description = "分片大小") @RequestParam(value = "chunkSize") int chunkSize,
            @Schema(description = "分片总数") @RequestParam(value = "totalChunks") int totalChunks) {

        StartUploadVO uploadVO = fileUploadService.startUpload(fileName, fileSize, contentType, providedClientId,chunkSize,totalChunks);
        return Result.success(uploadVO);
    }

    @PostMapping("/upload/chunk")
    @Operation(summary = "上传分片文件")
    public Result<String> uploadChunk(
            @Schema(description = "上传的文件") @RequestParam("file") MultipartFile file,
            @Schema(description = "会话ID") @RequestParam("sessionId") String sessionId,
            @Schema(description = "分片序号") @RequestParam("chunkNumber") int chunkNumber) {

        fileUploadService.uploadChunk(sessionId, chunkNumber, file);
        return Result.success("分片上传成功");
        
    }

    @PostMapping("/upload/complete")
    @Operation(summary = "完成文件上传处理")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "完成文件上传处理")
    public Result<String> completeUpload(@Schema(description = "会话ID") @RequestParam("sessionId") String sessionId) {

        fileUploadService.completeUpload(sessionId);
        return Result.success("文件处理完成");

    }

    @PostMapping("/upload/pause")
    @Operation(summary = "暂停上传")
    public Result<String> pauseUpload(@Schema(description = "会话ID") @RequestParam("sessionId") String sessionId) {
    
        fileUploadService.pauseUpload(sessionId);
        return Result.success("上传已暂停");
    
    }

    @PostMapping("/upload/resume")
    @Operation(summary = "恢复上传")
    public Result<ResumeUploadVO> resumeUpload(@Schema(description = "会话ID") @RequestParam("sessionId") String sessionId) {
        
        ResumeUploadVO response = fileUploadService.resumeUpload(sessionId);
        return Result.success(response);
        
    }

    @PostMapping("/upload/cancel")
    @Operation(summary = "取消上传")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "取消文件上传")
    public Result<String> cancelUpload( @Schema(description = "会话ID") @RequestParam("sessionId") String sessionId) {
        boolean cancelled = fileUploadService.cancelUpload(sessionId);
        if (cancelled) {
            log.info("上传已取消并清理: 会话ID={}", sessionId);
            return Result.success("上传已取消");
        }
        return Result.error("上传文件未找到");
    }

    @GetMapping("/upload/check")
    @Operation(summary = "检查上传状态")
    public Result<FileUploadStatusVO> checkFileStatus(@Schema(description = "会话ID") @RequestParam("sessionId") String sessionId) {

        FileUploadStatusVO statusVO = fileUploadService.checkFileStatus(sessionId);
        return Result.success(statusVO);

    }

    @GetMapping("/upload/progress")
    @Operation(summary = "获取上传进度")
    public Result<ProgressVO> getUploadProgress(@Schema(description = "会话ID") @RequestParam("sessionId") String sessionId) {

        ProgressVO progressVO = fileUploadService.getUploadProgress(sessionId);
        return Result.success(progressVO);

    }
}