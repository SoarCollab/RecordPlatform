package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.File;
import cn.flying.dao.vo.file.FileProvenanceVO;
import cn.flying.dao.vo.file.FileShareVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.dao.vo.file.UserFileStatsVO;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文件操作相关接口。
 * <p>
 * 采用 CQRS 模式：
 * Query 操作（读）使用 FileQueryService；Command 操作（写）使用 FileService。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "文件操作相关接口", description = "包括获取文件列表、删除文件、获取文件地址等操作。")
public class FileController {

    @Resource
    private FileQueryService fileQueryService;

    @Resource
    private FileService fileService;

    @Resource
    private ShareAuditService shareAuditService;

    /**
     * 根据文件 ID 获取文件详情。
     *
     * @param userId 用户 ID
     * @param id     文件外部 ID
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
     * 获取用户文件统计信息（用于 Dashboard）。
     *
     * @param userId 用户 ID
     * @return 文件统计信息
     */
    @GetMapping("/stats")
    @Operation(summary = "获取用户文件统计信息（用于 Dashboard）")
    @OperationLog(module = "文件操作", operationType = "查询", description = "获取用户文件统计")
    public Result<UserFileStatsVO> getUserFileStats(@RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        UserFileStatsVO stats = fileQueryService.getUserFileStats(userId);
        return Result.success(stats);
    }

    /**
     * 获取我的分享列表。
     *
     * @param userId   用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分享分页
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

    /**
     * 批量删除文件。
     *
     * @param userId      用户 ID
     * @param identifiers 文件哈希或文件 ID 列表
     * @return 操作结果
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
     * 管理员按文件 ID 列表删除文件。
     *
     * @param fileIdList 文件 ID 列表
     * @return 操作结果
     */
    @DeleteMapping("/deleteById")
    @Operation(summary = "根据文件id列表批量删除文件（管理员专用）")
    @PreAuthorize("hasPerm('file:admin')")
    @OperationLog(module = "文件操作", operationType = "删除", description = "删除文件")
    public Result<String> deleteFileById(
            @Schema(description = "待删除文件Id列表") @RequestParam("idList") List<String> fileIdList) {
        fileService.removeByIds(fileIdList);
        return Result.success("文件删除成功");
    }

    /**
     * 取消分享（调用区块链）。
     *
     * @param userId    用户 ID
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
     * 获取分享访问日志（管理员专用）。
     *
     * @param shareCode 分享码
     * @param pageNum   页码
     * @param pageSize  每页数量
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
     * 获取分享访问统计（管理员专用）。
     *
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
     * 获取文件溯源信息（管理员专用）。
     *
     * @param id 文件外部 ID
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
}
