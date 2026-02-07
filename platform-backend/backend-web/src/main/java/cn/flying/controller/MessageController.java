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
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 私信消息控制器。
 */
@RestController
@RequestMapping("/api/v1/messages")
@Tag(name = "私信管理", description = "私信的发送、查看等操作")
@Validated
public class MessageController {

    @Resource
    private MessageService messageService;

    /**
     * 发送私信（REST 新路径）。
     *
     * @param userId 当前用户 ID
     * @param vo     消息参数
     * @return 消息视图
     */
    @PostMapping
    @Operation(summary = "发送私信")
    @OperationLog(module = "站内信", operationType = "新增", description = "发送私信")
    public Result<MessageVO> sendMessage(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId,
            @Valid @RequestBody SendMessageVO vo) {
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

    /**
     * 获取未读私信总数。
     *
     * @param userId 当前用户 ID
     * @return 未读总数
     */
    @GetMapping("/unread-count")
    @Operation(summary = "获取未读私信总数")
    public Result<Map<String, Integer>> getUnreadCount(
            @RequestAttribute(Const.ATTR_USER_ID) Long userId) {
        int count = messageService.getTotalUnreadCount(userId);
        return Result.success(Map.of("count", count));
    }
}
