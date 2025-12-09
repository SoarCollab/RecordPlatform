package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.TicketStatus;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.entity.TicketReply;
import cn.flying.dao.vo.ticket.*;
import cn.flying.service.TicketService;
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
 * 工单控制器
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "工单管理", description = "工单的创建、查看、回复、状态管理等操作")
public class TicketController {

    @Resource
    private TicketService ticketService;

    // ==================== 用户端接口 ====================

    @GetMapping
    @Operation(summary = "获取我的工单列表")
    @OperationLog(module = "工单模块", operationType = "查询", description = "获取我的工单列表")
    public Result<IPage<TicketVO>> getMyTickets(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            TicketQueryVO query,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Ticket> page = new Page<>(pageNum, pageSize);
        IPage<TicketVO> result = ticketService.getUserTickets(userId, query, page);
        return Result.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取工单详情")
    @OperationLog(module = "工单模块", operationType = "查询", description = "获取工单详情")
    public Result<TicketDetailVO> getDetail(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "工单ID") @PathVariable String id) {
        Long ticketId = IdUtils.fromExternalId(id);
        boolean isAdmin = SecurityUtils.isAdmin();
        TicketDetailVO result = ticketService.getTicketDetail(userId, ticketId, isAdmin);
        return Result.success(result);
    }

    @PostMapping
    @Operation(summary = "创建工单")
    @OperationLog(module = "工单模块", operationType = "新增", description = "创建工单")
    public Result<TicketDetailVO> create(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody TicketCreateVO vo) {
        Ticket ticket = ticketService.createTicket(userId, vo);
        TicketDetailVO result = ticketService.getTicketDetail(userId, ticket.getId(), false);
        return Result.success(result);
    }

    @PostMapping("/{id}/reply")
    @Operation(summary = "回复工单")
    @OperationLog(module = "工单模块", operationType = "新增", description = "回复工单")
    public Result<String> reply(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "工单ID") @PathVariable String id,
            @Valid @RequestBody TicketReplyVO vo) {
        Long ticketId = IdUtils.fromExternalId(id);
        boolean isAdmin = SecurityUtils.isAdmin();
        ticketService.replyTicket(userId, ticketId, vo, isAdmin);
        return Result.success("回复成功");
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "关闭工单")
    @OperationLog(module = "工单模块", operationType = "修改", description = "关闭工单")
    public Result<String> close(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "工单ID") @PathVariable String id) {
        Long ticketId = IdUtils.fromExternalId(id);
        ticketService.closeTicket(userId, ticketId);
        return Result.success("工单已关闭");
    }

    @PostMapping("/{id}/confirm")
    @Operation(summary = "确认工单完成")
    @OperationLog(module = "工单模块", operationType = "修改", description = "确认工单完成")
    public Result<String> confirm(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "工单ID") @PathVariable String id) {
        Long ticketId = IdUtils.fromExternalId(id);
        ticketService.confirmTicket(userId, ticketId);
        return Result.success("工单已确认完成");
    }

    @GetMapping("/pending-count")
    @Operation(summary = "获取待处理工单数")
    public Result<Map<String, Integer>> getPendingCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = ticketService.getUserPendingCount(userId);
        return Result.success(Map.of("count", count));
    }

    // ==================== 管理员接口 ====================

    @GetMapping("/admin/list")
    @Operation(summary = "获取所有工单列表（管理员）")
    @PreAuthorize("hasPerm('ticket:admin')")
    @OperationLog(module = "工单模块", operationType = "查询", description = "管理员获取工单列表")
    public Result<IPage<TicketVO>> getAdminList(
            TicketQueryVO query,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Ticket> page = new Page<>(pageNum, pageSize);
        IPage<TicketVO> result = ticketService.getAdminTickets(query, page);
        return Result.success(result);
    }

    @PutMapping("/admin/{id}/assign")
    @Operation(summary = "分配处理人（管理员）")
    @PreAuthorize("hasPerm('ticket:admin')")
    @OperationLog(module = "工单模块", operationType = "修改", description = "分配工单处理人")
    public Result<String> assign(
            @RequestAttribute(Const.ATTR_USER_ID) Long adminId,
            @Parameter(description = "工单ID") @PathVariable String id,
            @Parameter(description = "处理人ID") @RequestParam String assigneeId) {
        Long ticketId = IdUtils.fromExternalId(id);
        Long assignee = IdUtils.fromExternalId(assigneeId);
        ticketService.assignTicket(adminId, ticketId, assignee);
        return Result.success("分配成功");
    }

    @PutMapping("/admin/{id}/status")
    @Operation(summary = "更新工单状态（管理员）")
    @PreAuthorize("hasPerm('ticket:admin')")
    @OperationLog(module = "工单模块", operationType = "修改", description = "更新工单状态")
    public Result<String> updateStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long adminId,
            @Parameter(description = "工单ID") @PathVariable String id,
            @Parameter(description = "新状态: 0-待处理, 1-处理中, 2-待确认, 3-已完成, 4-已关闭") @RequestParam Integer status) {
        Long ticketId = IdUtils.fromExternalId(id);
        TicketStatus newStatus = TicketStatus.fromCode(status);
        ticketService.updateStatus(adminId, ticketId, newStatus);
        return Result.success("状态更新成功");
    }

    @GetMapping("/admin/pending-count")
    @Operation(summary = "获取管理员待处理工单数")
    @PreAuthorize("hasPerm('ticket:admin')")
    public Result<Map<String, Integer>> getAdminPendingCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long adminId) {
        int count = ticketService.getAdminPendingCount(adminId);
        return Result.success(Map.of("count", count));
    }
}
