package cn.flying.controller;

import cn.flying.common.annotation.OperationLog;
import cn.flying.common.constant.Result;
import cn.flying.common.util.Const;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.entity.Message;
import cn.flying.dao.vo.message.MessageVO;
import cn.flying.dao.vo.message.SendMessageVO;
import cn.flying.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 私信消息控制器
 */
@RestController
@RequestMapping("/api/v1/messages")
@Tag(name = "私信管理", description = "私信的发送、查看等操作")
public class MessageController {

    @Resource
    private MessageService messageService;

    @PostMapping
    @Operation(summary = "发送私信")
    @OperationLog(module = "站内信", operationType = "新增", description = "发送私信")
    public Result<MessageVO> sendMessage(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody SendMessageVO vo) {
        Message message = messageService.sendMessage(userId, vo);

        // 构建返回的 VO
        MessageVO result = new MessageVO()
                .setId(IdUtils.toExternalId(message.getId()))
                .setSenderId(IdUtils.toExternalId(message.getSenderId()))
                .setContent(message.getContent())
                .setContentType(message.getContentType())
                .setIsMine(true)
                .setIsRead(false)
                .setCreateTime(message.getCreateTime());

        return Result.success(result);
    }

    @PostMapping("/to/{receiverId}")
    @Operation(summary = "发送私信（按用户ID）", description = "直接指定接收者ID发送私信")
    @OperationLog(module = "站内信", operationType = "新增", description = "发送私信")
    public Result<MessageVO> sendMessageToUser(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Parameter(description = "接收者ID") @PathVariable String receiverId,
            @Parameter(description = "消息内容") @RequestParam String content,
            @Parameter(description = "内容类型") @RequestParam(defaultValue = "text") String contentType) {
        SendMessageVO vo = new SendMessageVO();
        vo.setReceiverId(receiverId);
        vo.setContent(content);
        vo.setContentType(contentType);

        Message message = messageService.sendMessage(userId, vo);

        MessageVO result = new MessageVO()
                .setId(IdUtils.toExternalId(message.getId()))
                .setSenderId(IdUtils.toExternalId(message.getSenderId()))
                .setContent(message.getContent())
                .setContentType(message.getContentType())
                .setIsMine(true)
                .setIsRead(false)
                .setCreateTime(message.getCreateTime());

        return Result.success(result);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "获取未读私信总数")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = messageService.getTotalUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }
}
