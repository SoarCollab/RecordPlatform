package cn.flying.service;

import cn.flying.dao.entity.Message;
import cn.flying.dao.vo.message.MessageVO;
import cn.flying.dao.vo.message.SendMessageVO;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 私信消息服务接口
 */
public interface MessageService extends IService<Message> {

    /**
     * 发送私信
     *
     * @param senderId 发送者ID
     * @param vo       发送消息参数
     * @return 消息实体
     */
    Message sendMessage(Long senderId, SendMessageVO vo);

    /**
     * 获取会话消息列表（分页）
     *
     * @param userId         当前用户ID
     * @param conversationId 会话ID
     * @param page           分页参数
     * @return 消息分页数据
     */
    IPage<MessageVO> getMessages(Long userId, Long conversationId, Page<Message> page);

    /**
     * 标记会话消息为已读
     *
     * @param userId         用户ID
     * @param conversationId 会话ID
     */
    void markAsRead(Long userId, Long conversationId);

    /**
     * 获取用户总未读消息数
     *
     * @param userId 用户ID
     * @return 未读消息数
     */
    int getTotalUnreadCount(Long userId);

    /**
     * 获取会话中的未读消息数
     *
     * @param conversationId 会话ID
     * @param userId         用户ID
     * @return 未读消息数
     */
    int getUnreadCountInConversation(Long conversationId, Long userId);
}
