package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.vo.message.ConversationDetailVO;
import cn.flying.dao.vo.message.ConversationVO;
import cn.flying.service.ConversationService;
import cn.flying.service.MessageService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 会话控制器。
 */
@RestController
@RequestMapping("/api/v1/conversations")
@Tag(name = "会话管理", description = "私信会话的查看、删除等操作")
public class ConversationController {

    @Resource
    private ConversationService conversationService;

    @Resource
    private MessageService messageService;

    /**
     * 获取会话列表。
     *
     * @param userId   当前用户 ID
     * @param pageNum  页码
     * @param pageSize 每页数量
     * @return 会话分页
     */
    @GetMapping
    @Operation(summary = "获取会话列表", description = "获取当前用户的所有会话（分页）")
    @OperationLog(module = "站内信", operationType = "查询", description = "获取会话列表")
    public Result<IPage<ConversationVO>> getList(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页数量") @RequestParam(defaultValue = "20") Integer pageSize) {
        Page<Conversation> page = new Page<>(pageNum, pageSize);
        IPage<ConversationVO> result = conversationService.getConversationList(userId, page);
        return Result.success(result);
    }

    /**
     * 获取会话详情。
     *
     * @param userId   当前用户 ID
     * @param id       会话外部 ID
     * @param pageNum  消息页码
     * @param pageSize 消息每页数量
     * @return 会话详情
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取会话详情", description = "获取会话详情及消息列表")
    @OperationLog(module = "站内信", operationType = "查询", description = "获取会话详情")
    public Result<ConversationDetailVO> getDetail(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "会话ID") @PathVariable String id,
            @Parameter(description = "消息页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "消息每页数量") @RequestParam(defaultValue = "50") Integer pageSize) {
        Long conversationId;
        try {
            conversationId = IdUtils.fromExternalId(id);
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }
        ConversationDetailVO result = conversationService.getConversationDetail(userId, conversationId, pageNum, pageSize);
        return Result.success(result);
    }

    /**
     * 获取未读会话数量。
     *
     * @param userId 当前用户 ID
     * @return 未读数量
     */
    @GetMapping("/unread-count")
    @Operation(summary = "获取未读会话数")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = conversationService.getUnreadConversationCount(userId);
        return Result.success(Map.of("count", count));
    }

    /**
     * 标记会话消息为已读（REST 新路径）。
     *
     * @param userId 当前用户 ID
     * @param id     会话外部 ID
     * @return 操作结果
     */
    @PutMapping("/{id}/read-status")
    @Operation(summary = "标记会话消息为已读（REST）")
    @OperationLog(module = "站内信", operationType = "修改", description = "标记消息已读（REST）")
    public Result<String> updateReadStatus(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "会话ID") @PathVariable String id) {
        Long conversationId;
        try {
            conversationId = IdUtils.fromExternalId(id);
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }
        messageService.markAsRead(userId, conversationId);
        return Result.success("已标记为已读");
    }

    /**
     * 删除会话。
     *
     * @param userId 当前用户 ID
     * @param id     会话外部 ID
     * @return 操作结果
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除会话")
    @OperationLog(module = "站内信", operationType = "删除", description = "删除会话")
    public Result<String> delete(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "会话ID") @PathVariable String id) {
        Long conversationId;
        try {
            conversationId = IdUtils.fromExternalId(id);
        } catch (Exception e) {
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }
        conversationService.deleteConversation(userId, conversationId);
        return Result.success("删除成功");
    }
}
