package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.TicketStatus;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.vo.ticket.TicketQueryVO;
import cn.flying.dao.vo.ticket.TicketVO;
import cn.flying.service.TicketService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理员工单 REST 控制器。
 */
@RestController
@RequestMapping("/api/v1/admin/tickets")
@Tag(name = "管理员工单（REST）", description = "工单管理 REST 新路径")
public class AdminTicketController {

    @Resource
    private TicketService ticketService;

    /**
     * 获取管理员工单分页列表（REST 新路径）。
     *
     * @param query    查询参数
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 工单分页
     */
    @GetMapping("")
    @Operation(summary = "获取工单列表（管理员，REST）")
    @PreAuthorize("hasPerm('ticket:admin')")
    @OperationLog(module = "工单模块", operationType = "查询", description = "管理员获取工单列表（REST）")
    public Result<IPage<TicketVO>> getAdminTickets(TicketQueryVO query,
                                                   @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
                                                   @Parameter(description = "每页数量") @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Ticket> page = new Page<>(pageNum, pageSize);
        return Result.success(ticketService.getAdminTickets(query, page));
    }

    /**
     * 更新工单处理人（REST 新路径）。
     *
     * @param adminId    当前管理员 ID
     * @param ticketId   工单 ID
     * @param assigneeId 处理人 ID
     * @return 操作结果
     */
    @PutMapping("/{ticketId}/assignee")
    @Operation(summary = "分配工单处理人（REST）")
    @PreAuthorize("hasPerm('ticket:admin')")
    @OperationLog(module = "工单模块", operationType = "修改", description = "分配工单处理人（REST）")
    public Result<String> assignTicket(@RequestAttribute(Const.ATTR_USER_ID) Long adminId,
                                       @Parameter(description = "工单ID") @org.springframework.web.bind.annotation.PathVariable String ticketId,
                                       @Parameter(description = "处理人ID") @RequestParam String assigneeId) {
        ticketService.assignTicket(adminId, IdUtils.fromExternalId(ticketId), IdUtils.fromExternalId(assigneeId));
        return Result.success("分配成功");
    }

    /**
     * 更新工单状态（REST 新路径）。
     *
     * @param adminId  当前管理员 ID
     * @param ticketId 工单 ID
     * @param status   新状态
     * @return 操作结果
     */
    @PutMapping("/{ticketId}/status")
    @Operation(summary = "更新工单状态（REST）")
    @PreAuthorize("hasPerm('ticket:admin')")
    @OperationLog(module = "工单模块", operationType = "修改", description = "更新工单状态（REST）")
    public Result<String> updateTicketStatus(@RequestAttribute(Const.ATTR_USER_ID) Long adminId,
                                             @Parameter(description = "工单ID") @org.springframework.web.bind.annotation.PathVariable String ticketId,
                                             @Parameter(description = "新状态") @RequestParam Integer status) {
        ticketService.updateStatus(adminId, IdUtils.fromExternalId(ticketId), TicketStatus.fromCode(status));
        return Result.success("状态更新成功");
    }

    /**
     * 获取管理员待处理工单数量（REST 新路径）。
     *
     * @param adminId 当前管理员 ID
     * @return 数量
     */
    @GetMapping("/pending-count")
    @Operation(summary = "获取管理员待处理工单数（REST）")
    @PreAuthorize("hasPerm('ticket:admin')")
    public Result<Map<String, Integer>> getAdminPendingCount(@RequestAttribute(Const.ATTR_USER_ID) Long adminId) {
        return Result.success(Map.of("count", ticketService.getAdminPendingCount(adminId)));
    }
}

