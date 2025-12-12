package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 文件操作相关接口
 * <p>
 * 采用 CQRS 模式：
 * <ul>
 *   <li><b>Query 操作</b>（读）：使用 FileQueryService，支持 Virtual Thread 高并发</li>
 *   <li><b>Command 操作</b>（写）：使用 FileService，确保事务一致性</li>
 * </ul>
 * </p>
 *
 * @author flyingcoding
 * @since 2025-04-26
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "文件操作相关接口", description = "包括获取文件列表、删除文件、获取文件地址等操作。")
public class FileController {

    // ==================== CQRS: Query Service（读操作）====================
    @Resource
    private FileQueryService fileQueryService;

    // ==================== CQRS: Command Service（写操作）====================
    @Resource
    private FileService fileService;

    // ==================== Query 端点（读操作）====================

    /**
     * 获取用户文件列表
     * @param userId 用户ID
     * @return 用户文件列表
     */
    @GetMapping("/list")
    @Operation(summary = "获取用户文件列表（只包含文件元信息，没有实际的文件数据内容）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件列表")
    public Result<List<File>> getUserFiles(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        List<File> files = fileQueryService.getUserFilesList(userId);
        return Result.success(files);
    }

    /**
     * 获取用户文件列表
     * @param userId 用户ID
     * @return 用户文件列表
     */
    @GetMapping("/page")
    @Operation(summary = "获取用户文件分页（只包含文件元信息，没有实际的文件数据内容）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件分页")
    public Result<Page<File>> getUserFiles(@RequestAttribute(Const.ATTR_USER_ID) Long userId,
                                          @Schema(description = "当前分页") Integer pageNum,
                                          @Schema(description = "分页大小") Integer pageSize) {
        Page<File> page = new Page<>(pageNum, pageSize);
        fileQueryService.getUserFilesPage(userId, page);
        return Result.success(page);
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
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "待下载文件哈希") @RequestParam("fileHash") String fileHash) {
        List<String> addresses = fileQueryService.getFileAddress(userId, fileHash);
        return Result.success(addresses);
    }

    @GetMapping("/getTransaction")
    @Operation(summary = "获取根据交易hash获取对应的文件交易记录（存证记录）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取根据交易hash获取对应的文件交易记录")
    public Result<TransactionVO> getTransaction(@Schema(description = "交易hash") @RequestParam("transactionHash") String transactionHash){
        TransactionVO transaction = fileQueryService.getTransactionByHash(transactionHash);
        return Result.success(transaction);
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
    public Result<List<byte[]>> getFile(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
           @Schema(description = "待下载文件哈希") @RequestParam("fileHash") String fileHash) {
        List<byte[]> files = fileQueryService.getFile(userId, fileHash);
        return Result.success(files);
    }

    /**
     * 获取文件解密信息
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 解密信息（包含初始密钥和分片数量）
     */
    @GetMapping("/decryptInfo")
    @Operation(summary = "获取文件解密信息（包含初始密钥）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取文件解密信息")
    public Result<FileDecryptInfoVO> getDecryptInfo(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件哈希") @RequestParam("fileHash") String fileHash) {
        FileDecryptInfoVO decryptInfo = fileQueryService.getFileDecryptInfo(userId, fileHash);
        return Result.success(decryptInfo);
    }

    @GetMapping("/getSharingFiles")
    @Operation(summary = "获取分享文件列表")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享文件")
    public Result<List<File>> getShareFile(
           @Schema(description = "文件分享码")  @RequestParam("sharingCode") String sharingCode) {
        List<File> files = fileQueryService.getShareFile(sharingCode);
        return Result.success(files);
    }

    // ==================== Command 端点（写操作）====================

    /**
     * 删除文件
     * @param userId 用户ID
     * @param fileHashList 文件哈希列表
     * @return 删除结果
     */
    @DeleteMapping("/deleteByHash")
    @Operation(summary = "根据文件hash列表批量删除文件")
    @OperationLog(module = "文件操作", operationType = "删除", description = "删除文件")
    public Result<String> deleteFile(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "待删除文件hash列表") @RequestParam("hashList") List<String> fileHashList) {
        fileService.deleteFile(userId, fileHashList);
        return Result.success("文件删除成功");
    }

    /**
     * 删除文件
     * @param fileIdList 文件id列表
     * @return 删除结果
     */
    @DeleteMapping("/deleteById")
    @Operation(summary = "根据文件id列表批量删除文件（管理员专用）")
    @PreAuthorize("hasPerm('file:admin')") // 需要文件管理权限
    @OperationLog(module = "文件操作", operationType = "删除", description = "删除文件")
    public Result<String> deleteFileById(
            @Schema(description = "待删除文件Id列表") @RequestParam("idList") List<String> fileIdList) {
        fileService.removeByIds(fileIdList);
        return Result.success("文件删除成功");
    }

    @PostMapping("/share")
    @Operation(summary = "生成文件分享码")
    @OperationLog(module = "文件操作", operationType = "分享", description = "生成文件分享码")
    public Result<String> generateSharingCode(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId, FileSharingVO fileSharingVO) {
        String sharingCode = fileService.generateSharingCode(userId,fileSharingVO.getFileHash(),fileSharingVO.getMaxAccesses());
        return Result.success(sharingCode);
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
