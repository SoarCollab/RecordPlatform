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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 公告控制器。
 */
@RestController
@RequestMapping("/api/v1/announcements")
@Tag(name = "公告管理", description = "系统公告的发布、查看、已读管理等操作")
public class AnnouncementController {

    @Resource
    private AnnouncementService announcementService;

    /**
     * 获取最新公告。
     *
     * @param userId 当前用户 ID
     * @param limit  数量限制
     * @return 最新公告列表
     */
    @GetMapping("/latest")
    @Operation(summary = "获取最新公告", description = "获取最新发布的公告列表（按置顶和发布时间排序）")
    @OperationLog(module = "公告模块", operationType = "查询", description = "获取最新公告")
    public Result<List<AnnouncementVO>> getLatest(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "数量限制") @RequestParam(defaultValue = "5") Integer limit) {
        List<AnnouncementVO> result = announcementService.getLatest(userId, limit);
        return Result.success(result);
    }

    /**
     * 获取公告列表。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 公告分页
     */
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

    /**
     * 获取公告详情。
     *
     * @param userId 当前用户 ID
     * @param id     公告外部 ID
     * @return 公告详情
     */
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

    /**
     * 获取未读公告数量。
     *
     * @param userId 当前用户 ID
     * @return 未读数量
     */
    @GetMapping("/unread-count")
    @Operation(summary = "获取未读公告数量")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = announcementService.getUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }

    /**
     * 标记公告为已读（REST 新路径）。
     *
     * @param userId 当前用户 ID
     * @param id     公告外部 ID
     * @return 操作结果
     */
    @PutMapping("/{id}/read-status")
    @Operation(summary = "标记公告为已读（REST）")
    @OperationLog(module = "公告模块", operationType = "修改", description = "标记公告已读（REST）")
    public Result<String> updateReadStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "公告ID") @PathVariable String id) {
        Long announcementId = IdUtils.fromExternalId(id);
        announcementService.markAsRead(userId, announcementId);
        return Result.success("已标记为已读");
    }

    /**
     * 标记全部公告为已读（REST 新路径）。
     *
     * @param userId 当前用户 ID
     * @return 操作结果
     */
    @PutMapping("/read-status")
    @Operation(summary = "标记全部公告为已读（REST）")
    @OperationLog(module = "公告模块", operationType = "修改", description = "标记全部公告已读（REST）")
    public Result<String> updateAllReadStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        announcementService.markAllAsRead(userId);
        return Result.success("已全部标记为已读");
    }

    /**
     * 发布公告。
     *
     * @param userId 当前用户 ID
     * @param vo     公告创建参数
     * @return 公告详情
     */
    @PostMapping
    @Operation(summary = "发布/保存公告（管理员）")
    @PreAuthorize("hasPerm('announcement:admin')")
    @OperationLog(module = "公告模块", operationType = "新增", description = "发布公告")
    public Result<AnnouncementVO> publish(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody AnnouncementCreateVO vo) {
        Announcement announcement = announcementService.publish(userId, vo);
        AnnouncementVO result = announcementService.getDetail(null, announcement.getId());
        return Result.success(result);
    }

    /**
     * 编辑公告。
     *
     * @param id 公告外部 ID
     * @param vo 公告更新参数
     * @return 公告详情
     */
    @PutMapping("/{id}")
    @Operation(summary = "编辑公告（管理员）")
    @PreAuthorize("hasPerm('announcement:admin')")
    @OperationLog(module = "公告模块", operationType = "修改", description = "编辑公告")
    public Result<AnnouncementVO> update(
            @Parameter(description = "公告ID") @PathVariable String id,
            @Valid @RequestBody AnnouncementCreateVO vo) {
        Long announcementId = IdUtils.fromExternalId(id);
        Announcement announcement = announcementService.update(announcementId, vo);
        AnnouncementVO result = announcementService.getDetail(null, announcement.getId());
        return Result.success(result);
    }

    /**
     * 删除公告。
     *
     * @param id 公告外部 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除公告（管理员）")
    @PreAuthorize("hasPerm('announcement:admin')")
    @OperationLog(module = "公告模块", operationType = "删除", description = "删除公告")
    public Result<String> delete(
            @Parameter(description = "公告ID") @PathVariable String id) {
        Long announcementId = IdUtils.fromExternalId(id);
        announcementService.deleteAnnouncement(announcementId);
        return Result.success("删除成功");
    }
}
