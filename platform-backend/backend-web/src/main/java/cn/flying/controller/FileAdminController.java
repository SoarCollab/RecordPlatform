package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.vo.admin.AdminFileDetailVO;
import cn.flying.dao.vo.admin.AdminFileQueryParam;
import cn.flying.dao.vo.admin.AdminFileVO;
import cn.flying.dao.vo.admin.AdminShareQueryParam;
import cn.flying.dao.vo.admin.AdminShareVO;
import cn.flying.dao.vo.admin.UpdateFileStatusVO;
import cn.flying.dao.vo.file.ShareAccessLogVO;
import cn.flying.dao.vo.file.ShareAccessStatsVO;
import cn.flying.service.FileAdminService;
import cn.flying.service.ShareAuditService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 管理员文件审计控制器
 * <p>
 * 提供管理员专用的文件和分享管理接口，包括：
 * <ul>
 *   <li>查看所有文件（跨用户）</li>
 *   <li>查看文件详情（含溯源链路、访问日志）</li>
 *   <li>修改文件状态、强制删除文件</li>
 *   <li>查看所有分享、强制取消分享</li>
 *   <li>查看分享访问日志和统计</li>
 * </ul>
 *
 * @author flyingcoding
 * @since 2025-12-27
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/files")
@PreAuthorize("isAdmin()")
@Tag(name = "管理员-文件审计", description = "管理员专用的文件和分享管理接口")
public class FileAdminController {

    @Resource
    private FileAdminService fileAdminService;

    @Resource
    private ShareAuditService shareAuditService;

    // ==================== 文件管理 ====================

    /**
     * 获取所有文件列表（分页）
     */
    @GetMapping
    @Operation(summary = "获取所有文件列表（分页）")
    @OperationLog(module = "管理员-文件审计", operationType = "查询", description = "获取所有文件列表")
    public Result<IPage<AdminFileVO>> getAllFiles(
            AdminFileQueryParam param,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<AdminFileVO> files = fileAdminService.getAllFiles(param, page);
        return Result.success(files);
    }

    /**
     * 获取文件详情（含完整审计信息）
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取文件详情（含审计信息）")
    @OperationLog(module = "管理员-文件审计", operationType = "查询", description = "获取文件详情")
    public Result<AdminFileDetailVO> getFileDetail(
            @Parameter(description = "文件ID") @PathVariable String id) {
        AdminFileDetailVO detail = fileAdminService.getFileDetail(id);
        return Result.success(detail);
    }

    /**
     * 更新文件状态
     */
    @PutMapping("/{id}/status")
    @Operation(summary = "更新文件状态")
    @OperationLog(module = "管理员-文件审计", operationType = "更新", description = "更新文件状态")
    public Result<String> updateFileStatus(
            @Parameter(description = "文件ID") @PathVariable String id,
            @RequestBody @Valid UpdateFileStatusVO updateVO) {
        fileAdminService.updateFileStatus(id, updateVO.getStatus(), updateVO.getReason());
        return Result.success("文件状态已更新");
    }

    /**
     * 强制删除文件（物理删除）
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "强制删除文件（物理删除）")
    @OperationLog(module = "管理员-文件审计", operationType = "删除", description = "强制删除文件")
    public Result<String> forceDeleteFile(
            @Parameter(description = "文件ID") @PathVariable String id,
            @Parameter(description = "删除原因") @RequestParam(required = false) String reason) {
        fileAdminService.forceDeleteFile(id, reason);
        return Result.success("文件已删除");
    }

    // ==================== 分享管理 ====================

    /**
     * 获取所有分享列表（分页）
     */
    @GetMapping("/shares")
    @Operation(summary = "获取所有分享列表（分页）")
    @OperationLog(module = "管理员-文件审计", operationType = "查询", description = "获取所有分享列表")
    public Result<IPage<AdminShareVO>> getAllShares(
            AdminShareQueryParam param,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<AdminShareVO> shares = fileAdminService.getAllShares(param, page);
        return Result.success(shares);
    }

    /**
     * 强制取消分享
     */
    @DeleteMapping("/shares/{shareCode}")
    @Operation(summary = "强制取消分享")
    @OperationLog(module = "管理员-文件审计", operationType = "删除", description = "强制取消分享")
    public Result<String> forceCancelShare(
            @Parameter(description = "分享码") @PathVariable String shareCode,
            @Parameter(description = "取消原因") @RequestParam(required = false) String reason) {
        fileAdminService.forceCancelShare(shareCode, reason);
        return Result.success("分享已取消");
    }

    /**
     * 获取分享访问日志
     */
    @GetMapping("/shares/{shareCode}/logs")
    @Operation(summary = "获取分享访问日志")
    @OperationLog(module = "管理员-文件审计", operationType = "查询", description = "获取分享访问日志")
    public Result<IPage<ShareAccessLogVO>> getShareAccessLogs(
            @Parameter(description = "分享码") @PathVariable String shareCode,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<?> page = new Page<>(pageNum, pageSize);
        IPage<ShareAccessLogVO> logs = shareAuditService.getShareAccessLogs(shareCode, page);
        return Result.success(logs);
    }

    /**
     * 获取分享访问统计
     */
    @GetMapping("/shares/{shareCode}/stats")
    @Operation(summary = "获取分享访问统计")
    @OperationLog(module = "管理员-文件审计", operationType = "查询", description = "获取分享访问统计")
    public Result<ShareAccessStatsVO> getShareAccessStats(
            @Parameter(description = "分享码") @PathVariable String shareCode) {
        ShareAccessStatsVO stats = shareAuditService.getShareAccessStats(shareCode);
        return Result.success(stats);
    }
}
