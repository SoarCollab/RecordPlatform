package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.fileSharingVO;
import cn.flying.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @program: RecordPlatform
 * @description: 文件操作相关接口
 * @author: flyingcoding
 * @create: 2025-04-26 23:30
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@Tag(name = "文件操作相关接口", description = "包括获取文件列表、删除文件、获取文件地址等操作。")
public class FileController {

    @Resource
    private FileService fileService;

    /**
     * 获取用户文件列表
     * @param userId 用户ID
     * @return 用户文件列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取用户文件列表（只包含文件元信息，没有实际的文件数据内容）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件列表")
    public Result<List<File>> getUserFiles(@RequestAttribute(Const.ATTR_USER_ID) String userId) {
        List<File> files = fileService.getUserFiles(userId);
        return Result.success(files);
    }

    /**
     * 删除文件
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    @Operation(summary = "删除文件")
    @OperationLog(module = "文件操作", operationType = "删除", description = "删除文件")
    public Result<String> deleteFile(
            @RequestAttribute(Const.ATTR_USER_ID) String userId,
            @Schema(description = "待删除文件哈希") @RequestParam("fileHash") String fileHash) {
        fileService.deleteFile(userId, fileHash);
        return Result.success("文件删除成功");
    }

    /**
     * 获取文件访问地址
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 文件访问地址列表（Minio预签名下载地址【包含多个文件加密分片】）
     */
    @GetMapping("/address")
    @Operation(summary = "获取文件下载地址（Minio预签名下载地址【包含多个文件加密分片】）")
    @OperationLog(module = "文件操作", operationType = "下载", description = "获取文件下载地址")
    public Result<List<String>> getFileAddress(
            @RequestAttribute(Const.ATTR_USER_ID) String userId,
            @Schema(description = "待下载文件哈希") @RequestParam("fileHash") String fileHash) {
        List<String> addresses = fileService.getFileAddress(userId, fileHash);
        return Result.success(addresses);
    }

    /**
     * 获取文件
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 文件列表（包含多个文件加密分片）
     */
    @GetMapping("/download")
    @Operation(summary = "下载文件（包含多个文件加密分片）")
    @OperationLog(module = "文件操作", operationType = "下载", description = "下载文件内容")
    public Result<List<java.io.File>> getFile(
            @RequestAttribute(Const.ATTR_USER_ID) String userId,
           @Schema(description = "待下载文件哈希") @RequestParam("fileHash") String fileHash) {
        List<java.io.File> files = fileService.getFile(userId, fileHash);
        return Result.success(files);
    }

    @PostMapping("/share")
    @Operation(summary = "生成文件分享码")
    @OperationLog(module = "文件操作", operationType = "分享", description = "生成文件分享码")
    public Result<String> generateSharingCode(
            @RequestAttribute(Const.ATTR_USER_ID) String userId, fileSharingVO fileSharingVO) {
        String sharingCode = fileService.generateSharingCode(userId,fileSharingVO.getFileHash(),fileSharingVO.getMaxAccesses());
        return Result.success(sharingCode);
    }

    @GetMapping("/getSharingFiles")
    @Operation(summary = "获取分享文件列表")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享文件")
    public Result<List<File>> getShareFile(
           @Schema(description = "文件分享码")  @RequestParam("sharingCode") String sharingCode) {
        List<File> files = fileService.getShareFile(sharingCode);
        return Result.success(files);
    }

    @PostMapping("/saveShareFile")
    @Operation(summary = "保存分享文件")
    @OperationLog(module = "文件操作", operationType = "保存", description = "保存分享文件")
    public Result<String> saveShareFile(
            @RequestBody SaveSharingFile sharingFile) {
        fileService.saveShareFile(sharingFile.getSharingFileIdList());
        return Result.success("保存成功");
    }
}