package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.annotation.RateLimit;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileDecryptInfoVO;
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.SaveSharingFile;
import cn.flying.dao.vo.file.FileSharingVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.dao.vo.file.UpdateShareVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
import cn.flying.platformapi.response.TransactionVO;
import cn.flying.service.FileQueryService;
import cn.flying.service.FileService;
import cn.flying.service.ShareAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

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

    // ==================== 审计服务 ====================
    @Resource
    private ShareAuditService shareAuditService;

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
     * 通过文件哈希获取文件详情（支持好友分享访问）
     *
     * <p>用于前端文件详情页直接按 hash 查询，避免通过分页列表过滤导致的“文件不存在”。</p>
     *
     * @param userId   当前用户ID
     * @param fileHash 文件哈希（推荐参数名）
     * @param hash     文件哈希（兼容参数名）
     * @return 文件详情
     */
    @GetMapping("/byHash")
    @Operation(summary = "通过文件哈希获取文件详情", description = "支持访问自己的文件与好友分享给自己的文件")
    @OperationLog(module = "文件操作", operationType = "查询", description = "通过哈希获取文件详情")
    public Result<File> getFileByHash(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "文件哈希") @RequestParam(value = "fileHash", required = false) String fileHash,
            @Parameter(description = "文件哈希(兼容参数名)") @RequestParam(value = "hash", required = false) String hash) {
        String finalHash = StringUtils.hasText(fileHash) ? fileHash : hash;
        if (!StringUtils.hasText(finalHash)) {
            throw new GeneralException(ResultEnum.PARAM_NOT_COMPLETE, "缺少参数: fileHash");
        }
        File file = fileQueryService.getFileByHash(userId, finalHash);
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
     * 获取用户文件分页列表
     * @param userId 用户ID
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param keyword 搜索关键词（可选，匹配文件名或文件哈希）
     * @param status 文件状态过滤（可选）
     * @return 用户文件分页列表
     */
    @GetMapping("/page")
    @Operation(summary = "获取用户文件分页（只包含文件元信息，没有实际的文件数据内容）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件分页")
    public Result<Page<File>> getUserFiles(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "搜索关键词") @RequestParam(required = false) String keyword,
            @Parameter(description = "文件状态") @RequestParam(required = false) Integer status) {
        Page<File> page = new Page<>(pageNum, pageSize);
        fileQueryService.getUserFilesPage(userId, page, keyword, status);
        return Result.success(page);
    }

    /**
     * 获取用户文件统计信息（用于 Dashboard）
     * @param userId 用户ID
     * @return 文件统计信息（文件数、存储用量等）
     */
    @GetMapping("/stats")
    @Operation(summary = "获取用户文件统计信息（用于 Dashboard）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件统计")
    public Result<UserFileStatsVO> getUserFileStats(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        UserFileStatsVO stats = fileQueryService.getUserFileStats(userId);
        return Result.success(stats);
    }

    /**
     * 获取文件访问地址
     * @param userId 用户ID
     * @param fileHash 文件哈希
     * @return 文件访问地址列表（S3 预签名下载地址【包含多个文件加密分片】）
     */
    @GetMapping("/address")
    @Operation(summary = "获取文件下载地址（S3 预签名下载地址【包含多个文件加密分片】）")
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
           @Schema(description = "文件分享码")  @RequestParam("sharingCode") String sharingCode,
           @RequestAttribute(value = Const.ATTR_USER_ID, required = false) Long userId,
           HttpServletRequest request) {
        List<File> files = fileQueryService.getShareFile(sharingCode);
        // 异步记录分享查看审计日志
        shareAuditService.logShareView(sharingCode, userId, getClientIp(request), request.getHeader("User-Agent"));
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
            @RequestBody @Valid SaveSharingFile sharingFile,
            HttpServletRequest request) {
        String clientIp = getClientIp(request);
        fileService.saveShareFile(sharingFile.getSharingFileIdList(), sharingFile.getShareCode(), clientIp);
        return Result.success("保存成功");
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 多代理情况取第一个IP
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 将文件模块常见的业务异常转换为统一的 Result 响应（保持 HTTP 200，使用 code 表达业务错误）。
     *
     * @param ex 业务异常
     * @return 映射后的统一响应
     */
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
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash,
            HttpServletRequest request) {
        List<byte[]> files = fileService.getSharedFileContent(userId, shareCode, fileHash);
        // 异步记录分享下载审计日志
        shareAuditService.logShareDownload(shareCode, userId, fileHash, null, getClientIp(request));
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

    // ==================== 审计端点 ====================

    /**
     * 获取分享的访问日志（管理员专用）
     * @param shareCode 分享码
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 访问日志分页
     */
    @GetMapping("/share/{shareCode}/access-logs")
    @Operation(summary = "获取分享的访问日志（管理员专用）")
    @PreAuthorize("isAdmin()")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享访问日志")
    public Result<IPage<ShareAccessLogVO>> getShareAccessLogs(
            @Parameter(description = "分享码") @PathVariable String shareCode,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<ShareAccessLogVO> logs = shareAuditService.getShareAccessLogs(shareCode, page);
        return Result.success(logs);
    }

    /**
     * 获取分享的访问统计（管理员专用）
     * @param shareCode 分享码
     * @return 访问统计
     */
    @GetMapping("/share/{shareCode}/stats")
    @Operation(summary = "获取分享的访问统计（管理员专用）")
    @PreAuthorize("isAdmin()")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取分享访问统计")
    public Result<ShareAccessStatsVO> getShareAccessStats(
            @Parameter(description = "分享码") @PathVariable String shareCode) {
        ShareAccessStatsVO stats = shareAuditService.getShareAccessStats(shareCode);
        return Result.success(stats);
    }

    /**
     * 获取文件的溯源信息（管理员专用）
     * @param id 文件外部ID
     * @return 溯源信息
     */
    @GetMapping("/{id}/provenance")
    @Operation(summary = "获取文件的溯源信息（管理员专用）")
    @PreAuthorize("isAdmin()")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取文件溯源信息")
    public Result<FileProvenanceVO> getFileProvenance(
            @Parameter(description = "文件ID") @PathVariable String id) {
        Long fileId = IdUtils.fromExternalId(id);
        FileProvenanceVO provenance = shareAuditService.getFileProvenance(fileId);
        return Result.success(provenance);
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
    @RateLimit(limit = 30, type = RateLimit.LimitType.IP, key = "public:download")
    public Result<List<byte[]>> publicDownload(
            @Parameter(description = "分享码") @RequestParam("shareCode") String shareCode,
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash,
            HttpServletRequest request) {
        List<byte[]> files = fileService.getPublicFile(shareCode, fileHash);
        // 异步记录分享下载审计日志（匿名访问）
        shareAuditService.logShareDownload(shareCode, null, fileHash, null, getClientIp(request));
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
    @RateLimit(limit = 30, type = RateLimit.LimitType.IP, key = "public:decryptInfo")
    public Result<FileDecryptInfoVO> publicDecryptInfo(
            @Parameter(description = "分享码") @RequestParam("shareCode") String shareCode,
            @Parameter(description = "文件哈希") @RequestParam("fileHash") String fileHash) {
        FileDecryptInfoVO decryptInfo = fileService.getPublicFileDecryptInfo(shareCode, fileHash);
        return Result.success(decryptInfo);
    }
}
