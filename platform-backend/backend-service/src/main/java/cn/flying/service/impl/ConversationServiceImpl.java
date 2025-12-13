package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Conversation;
import cn.flying.dao.entity.Message;
import cn.flying.dao.mapper.ConversationMapper;
import cn.flying.dao.mapper.MessageMapper;
import cn.flying.dao.vo.message.ConversationDetailVO;
import cn.flying.dao.vo.message.ConversationVO;
import cn.flying.dao.vo.message.MessageVO;
import cn.flying.service.AccountService;
import cn.flying.service.ConversationService;
import cn.flying.service.MessageService;
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

@Slf4j
@Service
public class ConversationServiceImpl extends ServiceImpl<ConversationMapper, Conversation>
        implements ConversationService {

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private AccountService accountService;

    @Lazy
    @Resource
    private MessageService messageService;

    @Override
    @Transactional
    public Conversation getOrCreateConversation(Long userA, Long userB) {
        Long participantA = Math.min(userA, userB);
        Long participantB = Math.max(userA, userB);

        Conversation existing = baseMapper.selectByParticipants(participantA, participantB, TenantContext.getTenantId());
        if (existing != null) {
            return existing;
        }

        Conversation conversation = new Conversation()
                .setParticipantA(participantA)
                .setParticipantB(participantB);

        this.save(conversation);
        log.info("创建新会话: id={}, participantA={}, participantB={}", conversation.getId(), participantA, participantB);
        return conversation;
    }

    @Override
    public IPage<ConversationVO> getConversationList(Long userId, Page<Conversation> page) {
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .and(query -> query.eq(Conversation::getParticipantA, userId)
                        .or()
                        .eq(Conversation::getParticipantB, userId))
                .orderByDesc(Conversation::getLastMessageAt);

        IPage<Conversation> conversationPage = this.page(page, wrapper);
        return conversationPage.convert(conversation -> convertToVO(conversation, userId));
    }

    @Override
    public ConversationDetailVO getConversationDetail(Long userId, Long conversationId, Integer pageNum, Integer pageSize) {
        Conversation conversation = getById(conversationId);
        if (conversation == null) {
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }

        if (!conversation.getParticipantA().equals(userId) && !conversation.getParticipantB().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        Long otherUserId = conversation.getOtherParticipant(userId);
        Account otherUser = accountService.findAccountById(otherUserId);

        Page<Message> messagePage = new Page<>(pageNum, pageSize);
        IPage<MessageVO> messages = messageService.getMessages(userId, conversationId, messagePage);

        ConversationDetailVO vo = new ConversationDetailVO()
                .setId(IdUtils.toExternalId(conversationId))
                .setOtherUserId(IdUtils.toExternalId(otherUserId))
                .setMessages(messages.getRecords())
                .setHasMore(messages.getCurrent() < messages.getPages())
                .setTotalMessages(messages.getTotal());

        if (otherUser != null) {
            vo.setOtherUsername(otherUser.getUsername())
                    .setOtherAvatar(otherUser.getAvatar());
        }

        return vo;
    }

    @Override
    public int getUnreadConversationCount(Long userId) {
        return baseMapper.countUnreadConversations(userId, TenantContext.getTenantId());
    }

    @Override
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Conversation conversation = getById(conversationId);
        if (conversation == null) {
            return;
        }

        if (!conversation.getParticipantA().equals(userId) && !conversation.getParticipantB().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        // 执行软删除（MyBatis-Plus 的 @TableLogic 会自动处理）
        this.removeById(conversationId);
        log.info("用户 {} 删除会话 {}", userId, conversationId);
    }

    @Override
    @Transactional
    public void updateLastMessage(Long conversationId, Long messageId) {
        Conversation conversation = getById(conversationId);
        if (conversation != null) {
            conversation.setLastMessageId(messageId)
                    .setLastMessageAt(new Date());
            this.updateById(conversation);
        }
    }

    private ConversationVO convertToVO(Conversation conversation, Long currentUserId) {
        Long otherUserId = conversation.getOtherParticipant(currentUserId);
        Account otherUser = accountService.findAccountById(otherUserId);

        ConversationVO vo = new ConversationVO()
                .setId(IdUtils.toExternalId(conversation.getId()))
                .setOtherUserId(IdUtils.toExternalId(otherUserId))
                .setLastMessageTime(conversation.getLastMessageAt())
                .setUnreadCount(messageService.getUnreadCountInConversation(conversation.getId(), currentUserId));

        if (otherUser != null) {
            vo.setOtherUsername(otherUser.getUsername())
                    .setOtherAvatar(otherUser.getAvatar());
        }

        if (conversation.getLastMessageId() != null) {
            Message lastMessage = messageMapper.selectById(conversation.getLastMessageId());
            if (lastMessage != null) {
                vo.setLastMessageContent(lastMessage.getContent())
                        .setLastMessageType(lastMessage.getContentType());
            }
        }

        return vo;
    }
}
