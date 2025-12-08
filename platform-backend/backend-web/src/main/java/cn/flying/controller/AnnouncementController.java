package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.vo.announcement.AnnouncementCreateVO;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import cn.flying.service.AnnouncementService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 公告控制器
 */
@RestController
@RequestMapping("/api/v1/announcements")
@Tag(name = "公告管理", description = "系统公告的发布、查看、已读管理等操作")
public class AnnouncementController {

    @Resource
    private AnnouncementService announcementService;

    // ==================== 用户端接口 ====================

    @GetMapping
    @Operation(summary = "获取公告列表", description = "获取已发布的公告列表（分页）")
    @OperationLog(module = "公告模块", operationType = "查询", description = "获取公告列表")
    public Result<IPage<AnnouncementVO>> getList(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Announcement> page = new Page<>(pageNum, pageSize);
        IPage<AnnouncementVO> result = announcementService.getPublishedList(userId, page);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取公告详情")
    @OperationLog(module = "公告模块", operationType = "查询", description = "获取公告详情")
    public Result<AnnouncementVO> getDetail(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "公告ID") @PathVariable String id) {
        Long announcementId = IdUtils.fromExternalId(id);
        AnnouncementVO vo = announcementService.getDetail(userId, announcementId);
        return Result.success(vo);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "获取未读公告数量")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = announcementService.getUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "标记公告为已读")
    @OperationLog(module = "公告模块", operationType = "修改", description = "标记公告已读")
    public Result<String> markAsRead(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "公告ID") @PathVariable String id) {
        Long announcementId = IdUtils.fromExternalId(id);
        announcementService.markAsRead(userId, announcementId);
        return Result.success("已标记为已读");
    }

    @PostMapping("/read-all")
    @Operation(summary = "标记所有公告为已读")
    @OperationLog(module = "公告模块", operationType = "修改", description = "标记所有公告已读")
    public Result<String> markAllAsRead(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        announcementService.markAllAsRead(userId);
        return Result.success("已全部标记为已读");
    }

    // ==================== 管理员接口 ====================

    @GetMapping("/admin/list")
    @Operation(summary = "获取所有公告列表（管理员）")
    @PreAuthorize("hasRole('admin')")
    @OperationLog(module = "公告模块", operationType = "查询", description = "管理员获取公告列表")
    public Result<IPage<AnnouncementVO>> getAdminList(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Announcement> page = new Page<>(pageNum, pageSize);
        IPage<AnnouncementVO> result = announcementService.getAdminList(page);
        return Result.success(result);
    }

    @PostMapping
    @Operation(summary = "发布/保存公告（管理员）")
    @PreAuthorize("hasRole('admin')")
    @OperationLog(module = "公告模块", operationType = "新增", description = "发布公告")
    public Result<AnnouncementVO> publish(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody AnnouncementCreateVO vo) {
        Announcement announcement = announcementService.publish(userId, vo);
        AnnouncementVO result = announcementService.getDetail(null, announcement.getId());
        return Result.success(result);
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑公告（管理员）")
    @PreAuthorize("hasRole('admin')")
    @OperationLog(module = "公告模块", operationType = "修改", description = "编辑公告")
    public Result<AnnouncementVO> update(
            @Parameter(description = "公告ID") @PathVariable String id,
            @Valid @RequestBody AnnouncementCreateVO vo) {
        Long announcementId = IdUtils.fromExternalId(id);
        Announcement announcement = announcementService.update(announcementId, vo);
        AnnouncementVO result = announcementService.getDetail(null, announcement.getId());
        return Result.success(result);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除公告（管理员）")
    @PreAuthorize("hasRole('admin')")
    @OperationLog(module = "公告模块", operationType = "删除", description = "删除公告")
    public Result<String> delete(
            @Parameter(description = "公告ID") @PathVariable String id) {
        Long announcementId = IdUtils.fromExternalId(id);
        announcementService.deleteAnnouncement(announcementId);
        return Result.success("删除成功");
    }
}
