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
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


/**
 * @program: RecordPlatform
 * @description: 文件分片上传、断点续传控制器
 * @author flyingcoding
 * @create: 2025-03-31 11:22 (Refactored: YYYY-MM-DD)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files/upload")
@Tag(name = "文件分片上传相关接口", description = "包括文件开始上传、检查上传状态、暂停上传、取消上传等操作。")
@Validated
public class FileUploadController {

    @Resource
    private FileUploadService fileUploadService;

    @PostMapping("/start")
    @Operation(summary = "开始上传")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "开始上传文件")
    public Result<StartUploadVO> startUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件名") @RequestParam("fileName") @NotBlank @Size(max = 255)
            @Pattern(regexp = "^[\\p{IsHan}a-zA-Z0-9\\u4e00-\\u9fa5._\\-\\s,;!@#$%&()+=]+$")
            String fileName,
            @Schema(description = "文件大小") @RequestParam("fileSize") @Min(1) @Max(4294967296L) long fileSize,
            @Schema(description = "文件类型") @RequestParam(value = "contentType") @NotBlank String contentType,
            @Schema(description = "客户端ID") @RequestParam(value = "clientId", required = false) String providedClientId,
            @Schema(description = "分片大小") @RequestParam(value = "chunkSize") @Min(1) @Max(83886080) int chunkSize,
            @Schema(description = "分片总数") @RequestParam(value = "totalChunks") @Min(1) @Max(10000) int totalChunks) {

        StartUploadVO uploadVO = fileUploadService.startUpload(userId,fileName, fileSize, contentType, providedClientId,chunkSize,totalChunks);
        return Result.success(uploadVO);
    }

    @PostMapping("/chunk")
    @Operation(summary = "上传分片文件")
    public Result<String> uploadChunk(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "上传的文件") @RequestParam("file") MultipartFile file,
            @Schema(description = "客户端ID") @RequestParam("clientId") @NotBlank String clientId,
            @Schema(description = "分片序号") @RequestParam("chunkNumber") @Min(0) int chunkNumber) {

        fileUploadService.uploadChunk(userId, clientId, chunkNumber, file);
        return Result.success("分片上传成功");
        
    }

    @PostMapping("/complete")
    @Operation(summary = "完成文件上传处理")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "完成文件上传处理")
    public Result<String> completeUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        fileUploadService.completeUpload(userId,clientId);
        return Result.success("文件处理完成");

    }

    @PostMapping("/pause")
    @Operation(summary = "暂停上传")
    public Result<String> pauseUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        fileUploadService.pauseUpload(userId, clientId);
        return Result.success("上传已暂停");

    }

    @PostMapping("/resume")
    @Operation(summary = "恢复上传")
    public Result<ResumeUploadVO> resumeUpload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        ResumeUploadVO response = fileUploadService.resumeUpload(userId, clientId);
        return Result.success(response);

    }

    @PostMapping("/cancel")
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

    @GetMapping("/check")
    @Operation(summary = "检查上传状态")
    public Result<FileUploadStatusVO> checkFileStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        FileUploadStatusVO statusVO = fileUploadService.checkFileStatus(userId, clientId);
        return Result.success(statusVO);

    }

    @GetMapping("/progress")
    @Operation(summary = "获取上传进度")
    public Result<ProgressVO> getUploadProgress(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "客户端ID") @RequestParam("clientId") String clientId) {

        ProgressVO progressVO = fileUploadService.getUploadProgress(userId, clientId);
        return Result.success(progressVO);

    }
}
