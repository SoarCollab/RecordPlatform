package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SecurityUtils;
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
import java.util.List;

/**
 * 会话服务实现
 */
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

    /**
     * 获取或创建会话，按租户隔离并保证参与者有序
     */
    @Override
    @Transactional
    public Conversation getOrCreateConversation(Long userA, Long userB) {
        // 保证 participantA < participantB
        Long participantA = Math.min(userA, userB);
        Long participantB = Math.max(userA, userB);

        Long tenantId = SecurityUtils.getTenantId();

        // 查找已存在的会话
        Conversation existing = baseMapper.selectByParticipants(tenantId, participantA, participantB);
        if (existing != null) {
            return existing;
        }

        // 创建新会话
        Conversation conversation = new Conversation()
                .setTenantId(tenantId)
                .setParticipantA(participantA)
                .setParticipantB(participantB);

        this.save(conversation);
        log.info("创建新会话: id={}, participantA={}, participantB={}", conversation.getId(), participantA, participantB);
        return conversation;
    }

    @Override
    public IPage<ConversationVO> getConversationList(Long userId, Page<Conversation> page) {
        Long tenantId = SecurityUtils.getTenantId();
        // 查询用户参与的所有会话
        LambdaQueryWrapper<Conversation> wrapper = new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getTenantId, tenantId)
                .and(query -> query.eq(Conversation::getParticipantA, userId)
                        .or()
                        .eq(Conversation::getParticipantB, userId))
                .orderByDesc(Conversation::getLastMessageAt);

        IPage<Conversation> conversationPage = this.page(page, wrapper);

        return conversationPage.convert(conversation -> convertToVO(conversation, userId));
    }

    @Override
    public ConversationDetailVO getConversationDetail(Long userId, Long conversationId, Integer pageNum, Integer pageSize) {
        Long tenantId = SecurityUtils.getTenantId();
        Conversation conversation = findConversationInTenant(conversationId, tenantId);
        if (conversation == null) {
            throw new GeneralException(ResultEnum.CONVERSATION_NOT_FOUND);
        }

        // 验证用户是否是会话参与者
        if (!conversation.getParticipantA().equals(userId) && !conversation.getParticipantB().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        // 获取对方用户信息
        Long otherUserId = conversation.getOtherParticipant(userId);
        Account otherUser = accountService.findAccountById(otherUserId);

        // 获取消息列表
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
        Long tenantId = SecurityUtils.getTenantId();
        return baseMapper.countUnreadConversations(tenantId, userId);
    }

    @Override
    @Transactional
    public void deleteConversation(Long userId, Long conversationId) {
        Long tenantId = SecurityUtils.getTenantId();
        Conversation conversation = findConversationInTenant(conversationId, tenantId);
        if (conversation == null) {
            return;
        }

        // 验证用户是否是会话参与者
        if (!conversation.getParticipantA().equals(userId) && !conversation.getParticipantB().equals(userId)) {
            throw new GeneralException(ResultEnum.PERMISSION_UNAUTHORIZED);
        }

        // 逻辑删除会话中的消息（仅删除该用户可见的消息）
        // 注：实际实现可能需要更复杂的逻辑，这里简化处理
        log.info("用户 {} 删除会话 {}", userId, conversationId);
    }

    @Override
    @Transactional
    public void updateLastMessage(Long conversationId, Long messageId) {
        Long tenantId = SecurityUtils.getTenantId();
        Conversation conversation = findConversationInTenant(conversationId, tenantId);
        if (conversation != null) {
            conversation.setLastMessageId(messageId)
                    .setLastMessageAt(new Date());
            this.updateById(conversation);
        }
    }

    /**
     * 转换为 VO
     */
    private ConversationVO convertToVO(Conversation conversation, Long currentUserId) {
        Long otherUserId = conversation.getOtherParticipant(currentUserId);
        Account otherUser = accountService.findAccountById(otherUserId);

        ConversationVO vo = new ConversationVO()
                .setId(IdUtils.toExternalId(conversation.getId()))
                .setOtherUserId(IdUtils.toExternalId(otherUserId))
                .setLastMessageAt(conversation.getLastMessageAt())
                .setUnreadCount(messageService.getUnreadCountInConversation(conversation.getId(), currentUserId));

        if (otherUser != null) {
            vo.setOtherUsername(otherUser.getUsername())
                    .setOtherAvatar(otherUser.getAvatar());
        }

        // 获取最后一条消息内容
        if (conversation.getLastMessageId() != null) {
            Message lastMessage = messageMapper.selectById(conversation.getLastMessageId());
            if (lastMessage != null) {
                vo.setLastMessageContent(lastMessage.getContent())
                        .setLastMessageType(lastMessage.getContentType());
            }
        }

        return vo;
    }

    /**
     * 按租户范围查询会话
     *
     * @param conversationId 会话ID
     * @param tenantId       租户ID
     * @return 当前租户下的会话，不存在时返回 null
     */
    private Conversation findConversationInTenant(Long conversationId, Long tenantId) {
        return this.getOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getTenantId, tenantId));
    }
}
