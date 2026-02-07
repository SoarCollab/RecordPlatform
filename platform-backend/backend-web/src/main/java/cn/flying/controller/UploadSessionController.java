package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 上传会话 REST 控制器。
 */
@RestController
@Validated
@RequestMapping("/api/v1/upload-sessions")
@Tag(name = "上传会话（REST）", description = "分片上传 REST 新路径")
public class UploadSessionController {

    @Resource
    private FileUploadService fileUploadService;

    /**
     * 创建上传会话（REST 新路径）。
     *
     * @param userId           用户 ID
     * @param fileName         文件名
     * @param fileSize         文件大小
     * @param contentType      文件类型
     * @param providedClientId 客户端会话 ID
     * @param chunkSize        分片大小
     * @param totalChunks      分片总数
     * @return 上传会话信息
     */
    @PostMapping("")
    @Operation(summary = "创建上传会话（REST）")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "创建上传会话（REST）")
    public Result<StartUploadVO> createUploadSession(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件名") @RequestParam("fileName") @NotBlank @Size(max = 255)
            @Pattern(regexp = "^[\\p{IsHan}a-zA-Z0-9\\u4e00-\\u9fa5._\\-\\s,;!@#$%&()+=]+$")
            String fileName,
            @Schema(description = "文件大小") @RequestParam("fileSize") @Min(1) @Max(4294967296L) long fileSize,
            @Schema(description = "文件类型") @RequestParam(value = "contentType") @NotBlank String contentType,
            @Schema(description = "客户端ID") @RequestParam(value = "clientId", required = false) String providedClientId,
            @Schema(description = "分片大小") @RequestParam(value = "chunkSize") @Min(1) @Max(83886080) int chunkSize,
            @Schema(description = "分片总数") @RequestParam(value = "totalChunks") @Min(1) @Max(10000) int totalChunks) {
        StartUploadVO uploadVO = fileUploadService.startUpload(
                userId, fileName, fileSize, contentType, providedClientId, chunkSize, totalChunks
        );
        return Result.success(uploadVO);
    }

    /**
     * 上传分片（REST 新路径）。
     *
     * @param userId      用户 ID
     * @param clientId    客户端会话 ID
     * @param chunkNumber 分片序号
     * @param file        分片文件
     * @return 处理结果
     */
    @PutMapping("/{clientId}/chunks/{chunkNumber}")
    @Operation(summary = "上传分片（REST）")
    public Result<String> uploadChunk(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                      @PathVariable String clientId,
                                      @PathVariable int chunkNumber,
                                      @RequestParam("file") MultipartFile file) {
        fileUploadService.uploadChunk(userId, clientId, chunkNumber, file);
        return Result.success("分片上传成功");
    }

    /**
     * 完成上传（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param clientId 客户端会话 ID
     * @return 处理结果
     */
    @PostMapping("/{clientId}/complete")
    @Operation(summary = "完成上传（REST）")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "完成上传（REST）")
    public Result<String> completeUpload(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                         @PathVariable String clientId) {
        fileUploadService.completeUpload(userId, clientId);
        return Result.success("文件处理完成");
    }

    /**
     * 暂停上传（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param clientId 客户端会话 ID
     * @return 处理结果
     */
    @PostMapping("/{clientId}/pause")
    @Operation(summary = "暂停上传（REST）")
    public Result<String> pauseUpload(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                      @PathVariable String clientId) {
        fileUploadService.pauseUpload(userId, clientId);
        return Result.success("上传已暂停");
    }

    /**
     * 恢复上传（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param clientId 客户端会话 ID
     * @return 恢复结果
     */
    @PostMapping("/{clientId}/resume")
    @Operation(summary = "恢复上传（REST）")
    public Result<ResumeUploadVO> resumeUpload(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                               @PathVariable String clientId) {
        return Result.success(fileUploadService.resumeUpload(userId, clientId));
    }

    /**
     * 取消上传（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param clientId 客户端会话 ID
     * @return 处理结果
     */
    @DeleteMapping("/{clientId}")
    @Operation(summary = "取消上传（REST）")
    @OperationLog(module = "文件分片上传模块", operationType = "上传", description = "取消上传（REST）")
    public Result<String> cancelUpload(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                       @PathVariable String clientId) {
        boolean cancelled = fileUploadService.cancelUpload(userId, clientId);
        if (!cancelled) {
            return Result.error(ResultEnum.RESULT_DATA_NONE);
        }
        return Result.success("上传已取消");
    }

    /**
     * 查询上传会话状态（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param clientId 客户端会话 ID
     * @return 会话状态
     */
    @GetMapping("/{clientId}")
    @Operation(summary = "查询上传会话状态（REST）")
    public Result<FileUploadStatusVO> getUploadSession(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                       @PathVariable String clientId) {
        return Result.success(fileUploadService.checkFileStatus(userId, clientId));
    }

    /**
     * 查询上传进度（REST 新路径）。
     *
     * @param userId   用户 ID
     * @param clientId 客户端会话 ID
     * @return 上传进度
     */
    @GetMapping("/{clientId}/progress")
    @Operation(summary = "查询上传进度（REST）")
    public Result<ProgressVO> getUploadProgress(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                                @PathVariable String clientId) {
        return Result.success(fileUploadService.getUploadProgress(userId, clientId));
    }
}

