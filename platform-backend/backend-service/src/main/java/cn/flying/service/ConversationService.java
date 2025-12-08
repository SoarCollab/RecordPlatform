package cn.flying.service;

import cn.flying.dao.entity.Conversation;
import cn.flying.dao.vo.message.ConversationDetailVO;
import cn.flying.dao.vo.message.ConversationVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 会话服务接口
 */
public interface ConversationService extends IService<Conversation> {

    /**
     * 获取或创建会话
     *
     * @param userA 用户A
     * @param userB 用户B
     * @return 会话实体
     */
    Conversation getOrCreateConversation(Long userA, Long userB);

    /**
     * 获取会话列表
     *
     * @param userId 当前用户ID
     * @param page   分页参数
     * @return 会话分页数据
     */
    IPage<ConversationVO> getConversationList(Long userId, Page<Conversation> page);

    /**
     * 获取会话详情（含消息列表）
     *
     * @param userId         当前用户ID
     * @param conversationId 会话ID
     * @param pageNum        消息页码
     * @param pageSize       消息每页数量
     * @return 会话详情
     */
    ConversationDetailVO getConversationDetail(Long userId, Long conversationId, Integer pageNum, Integer pageSize);

    /**
     * 获取未读会话数
     *
     * @param userId 用户ID
     * @return 未读会话数
     */
    int getUnreadConversationCount(Long userId);

    /**
     * 删除会话
     *
     * @param userId         用户ID
     * @param conversationId 会话ID
     */
    void deleteConversation(Long userId, Long conversationId);

    /**
     * 更新会话的最后消息信息
     *
     * @param conversationId 会话ID
     * @param messageId      消息ID
     */
    void updateLastMessage(Long conversationId, Long messageId);
}
