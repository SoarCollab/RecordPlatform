package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
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
     * 根据文件ID获取文件详情
     * @param userId 用户ID
     * @param id 文件外部ID
     * @return 文件详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "根据文件ID获取文件详情")
    @OperationLog(module = "文件操作", operationType = "查询", description = "根据ID获取文件详情")
    public Result<File> getFileById(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "文件ID") @PathVariable String id) {
        Long fileId = IdUtils.fromExternalId(id);
        File file = fileQueryService.getFileById(userId, fileId);
        return Result.success(file);
    }

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

    /**
     * 获取我的分享列表
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分享记录分页
     */
    @GetMapping("/shares")
    @Operation(summary = "获取我的分享列表")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取我的分享列表")
    public Result<IPage<FileShareVO>> getMyShares(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<FileShareVO> shares = fileQueryService.getUserShares(userId, page);
        return Result.success(shares);
    }

    // ==================== Command 端点（写操作）====================

    /**
     * 批量删除文件
     * 支持通过文件哈希或文件ID进行删除
     * @param userId 用户ID
     * @param identifiers 文件哈希或文件ID列表
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    @Operation(summary = "批量删除文件（支持通过文件哈希或文件ID）")
    @OperationLog(module = "文件操作", operationType = "删除", description = "批量删除文件")
    public Result<String> deleteFiles(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Schema(description = "待删除文件标识列表（支持文件哈希或文件ID）") @RequestParam("identifiers") List<String> identifiers) {
        fileService.deleteFiles(userId, identifiers);
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
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestBody @Valid FileSharingVO fileSharingVO) {
        String sharingCode = fileService.generateSharingCode(
                userId,
                fileSharingVO.getFileHash(),
                fileSharingVO.getExpireMinutes(),
                fileSharingVO.getShareType());
        return Result.success(sharingCode);
    }

    @PostMapping("/saveShareFile")
    @Operation(summary = "保存分享文件")
    @OperationLog(module = "文件操作", operationType = "保存", description = "保存分享文件")
    public Result<String> saveShareFile(
            @RequestBody @Valid SaveSharingFile sharingFile) {
        fileService.saveShareFile(sharingFile.getSharingFileIdList());
        return Result.success("保存成功");
    }

    /**
     * 取消分享（调用区块链）
     * @param userId 用户ID
     * @param shareCode 分享码
     * @return 操作结果
     */
    @DeleteMapping("/share/{shareCode}")
    @Operation(summary = "取消分享")
    @OperationLog(module = "文件操作", operationType = "删除", description = "取消分享")
    public Result<String> cancelShare(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享码") @PathVariable String shareCode) {
        fileService.cancelShare(userId, shareCode);
        return Result.success("分享已取消");
    }

    /**
     * 更新分享设置（类型/有效期）
     * @param userId 用户ID
     * @param updateVO 更新参数
     * @return 操作结果
     */
    @PutMapping("/share")
    @Operation(summary = "更新分享设置")
    @OperationLog(module = "文件操作", operationType = "更新", description = "更新分享设置")
    public Result<String> updateShare(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @RequestBody @Valid UpdateShareVO updateVO) {
        fileService.updateShare(userId, updateVO);
        return Result.success("分享设置已更新");
    }

    /**
     * 登录用户通过分享码下载文件（支持私密/公开分享）
     * @param userId 用户ID
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 文件分片数据
     */
    @GetMapping("/share/download")
    @Operation(summary = "分享下载文件（需要登录）")
    @OperationLog(module = "文件操作", operationType = "下载", description = "分享下载文件")
    public Result<List<byte[]>> shareDownload(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享码") @RequestParam("shareCode") String shareCode,
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash) {
        List<byte[]> files = fileService.getSharedFileContent(userId, shareCode, fileHash);
        return Result.success(files);
    }

    /**
     * 登录用户通过分享码获取解密信息（支持私密/公开分享）
     * @param userId 用户ID
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 解密信息
     */
    @GetMapping("/share/decryptInfo")
    @Operation(summary = "分享获取解密信息（需要登录）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "分享获取解密信息")
    public Result<FileDecryptInfoVO> shareDecryptInfo(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "分享码") @RequestParam("shareCode") String shareCode,
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash) {
        FileDecryptInfoVO decryptInfo = fileService.getSharedFileDecryptInfo(userId, shareCode, fileHash);
        return Result.success(decryptInfo);
    }

    // ==================== 公开端点（无需认证）====================

    /**
     * 公开分享下载文件（无需登录）
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 文件分片数据
     */
    @GetMapping("/public/download")
    @Operation(summary = "公开分享下载文件（无需登录）")
    public Result<List<byte[]>> publicDownload(
            @Parameter(description = "分享码") @RequestParam("shareCode") String shareCode,
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash) {
        List<byte[]> files = fileService.getPublicFile(shareCode, fileHash);
        return Result.success(files);
    }

    /**
     * 公开分享获取解密信息（无需登录）
     * @param shareCode 分享码
     * @param fileHash 文件哈希
     * @return 解密信息
     */
    @GetMapping("/public/decryptInfo")
    @Operation(summary = "公开分享获取解密信息（无需登录）")
    public Result<FileDecryptInfoVO> publicDecryptInfo(
            @Parameter(description = "分享码") @RequestParam("shareCode") String shareCode,
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash) {
        FileDecryptInfoVO decryptInfo = fileService.getPublicFileDecryptInfo(shareCode, fileHash);
        return Result.success(decryptInfo);
    }
}
