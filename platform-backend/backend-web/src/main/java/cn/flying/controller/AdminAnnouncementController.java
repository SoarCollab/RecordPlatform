package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.dao.entity.Announcement;
import cn.flying.dao.vo.announcement.AnnouncementVO;
import cn.flying.service.AnnouncementService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员公告 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/admin/announcements")
@Tag(name = "管理员公告（REST）", description = "公告管理 REST 新路径")
public class AdminAnnouncementController {

    @Resource
    private AnnouncementService announcementService;

    /**
     * 获取管理员公告分页列表（REST 新路径）。
     *
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 分页结果
     */
    @GetMapping("")
    @Operation(summary = "获取公告列表（管理员，REST）")
    @PreAuthorize("hasPerm('announcement:admin')")
    @OperationLog(module = "公告模块", operationType = "查询", description = "管理员获取公告列表（REST）")
    public Result<IPage<AnnouncementVO>> getAdminAnnouncements(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Announcement> page = new Page<>(pageNum, pageSize);
        return Result.success(announcementService.getAdminList(page));
    }
}

