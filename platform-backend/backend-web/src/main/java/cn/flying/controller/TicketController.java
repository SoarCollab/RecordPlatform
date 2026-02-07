package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.vo.ticket.TicketCreateVO;
import cn.flying.dao.vo.ticket.TicketDetailVO;
import cn.flying.dao.vo.ticket.TicketQueryVO;
import cn.flying.dao.vo.ticket.TicketReplyVO;
import cn.flying.dao.vo.ticket.TicketUpdateVO;
import cn.flying.dao.vo.ticket.TicketVO;
import cn.flying.service.TicketService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 工单控制器。
 */
@RestController
@RequestMapping("/api/v1/tickets")
@Tag(name = "工单管理", description = "工单的创建、查看、回复、状态管理等操作")
public class TicketController {

    @Resource
    private TicketService ticketService;

    /**
     * 获取当前用户工单列表。
     *
     * @param userId   当前用户 ID
     * @param query    查询条件
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 工单分页
     */
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

    /**
     * 获取工单详情。
     *
     * @param userId 当前用户 ID
     * @param id     工单外部 ID
     * @return 工单详情
     */
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

    /**
     * 创建工单。
     *
     * @param userId 当前用户 ID
     * @param vo     创建参数
     * @return 工单详情
     */
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

    /**
     * 更新工单。
     *
     * @param userId 当前用户 ID
     * @param id     工单外部 ID
     * @param vo     更新参数
     * @return 工单详情
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新工单")
    @OperationLog(module = "工单模块", operationType = "修改", description = "更新工单")
    public Result<TicketDetailVO> update(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "工单ID") @PathVariable String id,
            @Valid @RequestBody TicketUpdateVO vo) {
        Long ticketId = IdUtils.fromExternalId(id);
        Ticket ticket = ticketService.updateTicket(userId, ticketId, vo);
        TicketDetailVO result = ticketService.getTicketDetail(userId, ticket.getId(), false);
        return Result.success(result);
    }

    /**
     * 回复工单。
     *
     * @param userId 当前用户 ID
     * @param id     工单外部 ID
     * @param vo     回复参数
     * @return 操作结果
     */
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

    /**
     * 关闭工单。
     *
     * @param userId 当前用户 ID
     * @param id     工单外部 ID
     * @return 操作结果
     */
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

    /**
     * 确认工单完成。
     *
     * @param userId 当前用户 ID
     * @param id     工单外部 ID
     * @return 操作结果
     */
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

    /**
     * 获取待处理工单数量。
     *
     * @param userId 当前用户 ID
     * @return 待处理数量
     */
    @GetMapping("/pending-count")
    @Operation(summary = "获取待处理工单数")
    public Result<Map<String, Integer>> getPendingCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = ticketService.getUserPendingCount(userId);
        return Result.success(Map.of("count", count));
    }

    /**
     * 获取未读工单数量。
     *
     * @param userId 当前用户 ID
     * @return 未读数量
     */
    @GetMapping("/unread-count")
    @Operation(summary = "获取未读工单数")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = ticketService.getUserUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }
}
