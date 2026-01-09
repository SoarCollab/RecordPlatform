package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.entity.Message;
import cn.flying.dao.mapper.MessageMapper;
import cn.flying.dao.vo.message.MessageVO;
import cn.flying.dao.vo.message.SendMessageVO;
import cn.flying.service.AccountService;
import cn.flying.service.ConversationService;
import cn.flying.service.FriendService;
import cn.flying.service.MessageService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Map;

@Slf4j
@Service
public class MessageServiceImpl extends ServiceImpl<MessageMapper, Message>
        implements MessageService {

    @Lazy
    @Resource
    private ConversationService conversationService;

    @Resource
    private AccountService accountService;

    @Lazy
    @Resource
    private FriendService friendService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Override
    @Transactional
    public Message sendMessage(Long senderId, SendMessageVO vo) {
        Long receiverId = IdUtils.fromExternalId(vo.getReceiverId());

        if (senderId.equals(receiverId)) {
            throw new GeneralException(ResultEnum.CANNOT_MESSAGE_SELF);
        }

        Account receiver = accountService.findAccountById(receiverId);
        if (receiver == null) {
            throw new GeneralException(ResultEnum.USER_NOT_EXIST);
        }

        // 检查是否是好友关系
        if (!friendService.areFriends(senderId, receiverId)) {
            throw new GeneralException(ResultEnum.NOT_FRIENDS);
        }

        Conversation conversation = conversationService.getOrCreateConversation(senderId, receiverId);

        Message message = new Message()
                .setConversationId(conversation.getId())
                .setSenderId(senderId)
                .setReceiverId(receiverId)
                .setContent(vo.getContent())
                .setContentType(vo.getContentType() != null ? vo.getContentType() : "text")
                .setIsRead(0);

        this.save(message);
        conversationService.updateLastMessage(conversation.getId(), message.getId());

        log.info("发送私信成功: messageId={}, senderId={}, receiverId={}", message.getId(), senderId, receiverId);

        Account sender = accountService.findAccountById(senderId);
        Long tenantId = TenantContext.requireTenantId();
        String messageExternalId = IdUtils.toExternalId(message.getId());
        sseEmitterManager.sendToUser(tenantId, receiverId, SseEvent.of(SseEventType.NEW_MESSAGE, Map.of(
                "id", messageExternalId,
                "messageId", messageExternalId,
                "conversationId", IdUtils.toExternalId(conversation.getId()),
                "senderId", IdUtils.toExternalId(senderId),
                "senderName", sender != null ? sender.getUsername() : "未知用户",
                "content", message.getContent(),
                "createTime", (message.getCreateTime() != null ? message.getCreateTime() : new Date()).toInstant().toString()
        )));

        return message;
    }

    @Override
    public IPage<MessageVO> getMessages(Long userId, Long conversationId, Page<Message> page) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getCreateTime);

        IPage<Message> messagePage = this.page(page, wrapper);
        return messagePage.convert(message -> convertToVO(message, userId));
    }

    @Override
    @Transactional
    public void markAsRead(Long userId, Long conversationId) {
        Conversation conversation = conversationService.getById(conversationId);
        if (conversation == null) {
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }
        if (!conversation.getParticipantA().equals(userId) && !conversation.getParticipantB().equals(userId)) {
            // 安全策略：隐藏会话是否存在
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }

        int updated = baseMapper.markConversationAsRead(conversationId, userId, new Date(), TenantContext.getTenantId());
        if (updated > 0) {
            log.info("标记 {} 条消息为已读, conversationId={}, userId={}", updated, conversationId, userId);
        }
    }

    @Override
    public int getTotalUnreadCount(Long userId) {
        return baseMapper.countUnreadMessages(userId, TenantContext.getTenantId());
    }

    @Override
    public int getUnreadCountInConversation(Long conversationId, Long userId) {
        return baseMapper.countUnreadInConversation(conversationId, userId, TenantContext.getTenantId());
    }

    private MessageVO convertToVO(Message message, Long currentUserId) {
        Account sender = accountService.findAccountById(message.getSenderId());

        MessageVO vo = new MessageVO()
                .setId(IdUtils.toExternalId(message.getId()))
                .setSenderId(IdUtils.toExternalId(message.getSenderId()))
                .setContent(message.getContent())
                .setContentType(message.getContentType())
                .setIsMine(message.getSenderId().equals(currentUserId))
                .setIsRead(message.getIsRead() == 1)
                .setCreateTime(message.getCreateTime());

        if (sender != null) {
            vo.setSenderUsername(sender.getUsername())
                    .setSenderAvatar(sender.getAvatar());
        }

        return vo;
    }
}
