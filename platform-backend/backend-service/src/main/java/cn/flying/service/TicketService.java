package cn.flying.service;

import cn.flying.common.constant.TicketStatus;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.entity.TicketReply;
import cn.flying.dao.vo.ticket.*;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 工单服务接口
 */
public interface TicketService extends IService<Ticket> {

    /**
     * 创建工单
     *
     * @param userId 创建者ID
     * @param vo     工单创建参数
     * @return 工单实体
     */
    Ticket createTicket(Long userId, TicketCreateVO vo);

    /**
     * 获取用户工单列表
     *
     * @param userId 用户ID
     * @param query  查询条件
     * @param page   分页参数
     * @return 工单分页数据
     */
    IPage<TicketVO> getUserTickets(Long userId, TicketQueryVO query, Page<Ticket> page);

    /**
     * 获取所有工单列表（管理端）
     *
     * @param query 查询条件
     * @param page  分页参数
     * @return 工单分页数据
     */
    IPage<TicketVO> getAdminTickets(TicketQueryVO query, Page<Ticket> page);

    /**
     * 获取工单详情
     *
     * @param userId   当前用户ID
     * @param ticketId 工单ID
     * @param isAdmin  是否管理员
     * @return 工单详情
     */
    TicketDetailVO getTicketDetail(Long userId, Long ticketId, boolean isAdmin);

    /**
     * 回复工单
     *
     * @param replierId 回复者ID
     * @param ticketId  工单ID
     * @param vo        回复参数
     * @param isAdmin   是否管理员
     * @return 回复实体
     */
    TicketReply replyTicket(Long replierId, Long ticketId, TicketReplyVO vo, boolean isAdmin);

    /**
     * 更新工单状态
     *
     * @param operatorId 操作者ID
     * @param ticketId   工单ID
     * @param newStatus  新状态
     */
    void updateStatus(Long operatorId, Long ticketId, TicketStatus newStatus);

    /**
     * 分配处理人
     *
     * @param adminId    管理员ID
     * @param ticketId   工单ID
     * @param assigneeId 处理人ID
     */
    void assignTicket(Long adminId, Long ticketId, Long assigneeId);

    /**
     * 关闭工单
     *
     * @param userId   用户ID
     * @param ticketId 工单ID
     */
    void closeTicket(Long userId, Long ticketId);

    /**
     * 用户确认工单完成
     *
     * @param userId   用户ID
     * @param ticketId 工单ID
     */
    void confirmTicket(Long userId, Long ticketId);

    /**
     * 添加附件
     *
     * @param ticketId 工单ID
     * @param replyId  回复ID（可为空）
     * @param fileId   文件ID
     * @param fileName 文件名
     * @param fileSize 文件大小
     */
    void addAttachment(Long ticketId, Long replyId, Long fileId, String fileName, Long fileSize);

    /**
     * 获取用户待处理工单数
     *
     * @param userId 用户ID
     * @return 待处理工单数
     */
    int getUserPendingCount(Long userId);

    /**
     * 获取管理员待处理工单数
     *
     * @param adminId 管理员ID
     * @return 待处理工单数
     */
    int getAdminPendingCount(Long adminId);
}
