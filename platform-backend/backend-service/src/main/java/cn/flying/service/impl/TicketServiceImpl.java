package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.TicketPriority;
import cn.flying.common.constant.TicketStatus;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.dao.dto.Account;
import cn.flying.dao.entity.Ticket;
import cn.flying.dao.entity.TicketAttachment;
import cn.flying.dao.entity.TicketReply;
import cn.flying.dao.mapper.TicketAttachmentMapper;
import cn.flying.dao.mapper.TicketMapper;
import cn.flying.dao.mapper.TicketReplyMapper;
import cn.flying.dao.vo.ticket.*;
import cn.flying.service.AccountService;
import cn.flying.service.TicketService;
import cn.flying.service.sse.SseEmitterManager;
import cn.flying.service.sse.SseEvent;
import cn.flying.service.sse.SseEventType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket>
        implements TicketService {

    @Resource
    private TicketReplyMapper ticketReplyMapper;

    @Resource
    private TicketAttachmentMapper ticketAttachmentMapper;

    @Resource
    private AccountService accountService;

    @Resource
    private SseEmitterManager sseEmitterManager;

    @Override
    @Transactional
    public Ticket createTicket(Long userId, TicketCreateVO vo) {
        Ticket ticket = new Ticket()
                .setTicketNo(Ticket.generateTicketNo())
                .setTitle(vo.getTitle())
                .setContent(vo.getContent())
                .setPriority(vo.getPriority() != null ? vo.getPriority() : TicketPriority.MEDIUM.getCode())
                .setStatus(TicketStatus.PENDING.getCode())
                .setCreatorId(userId);

        this.save(ticket);

        if (vo.getAttachmentIds() != null && !vo.getAttachmentIds().isEmpty()) {
            for (String fileIdStr : vo.getAttachmentIds()) {
                Long fileId = IdUtils.fromExternalId(fileIdStr);
                addAttachment(ticket.getId(), null, fileId, "附件", null);
            }
        }

        log.info("工单创建成功: ticketNo={}, userId={}", ticket.getTicketNo(), userId);
        return ticket;
    }

    @Override
    public IPage<TicketVO> getUserTickets(Long userId, TicketQueryVO query, Page<Ticket> page) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getCreatorId, userId)
                .eq(query.getStatus() != null, Ticket::getStatus, query.getStatus())
                .eq(query.getPriority() != null, Ticket::getPriority, query.getPriority())
                .like(StringUtils.hasText(query.getTicketNo()), Ticket::getTicketNo, query.getTicketNo())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(Ticket::getTitle, query.getKeyword())
                        .or()
                        .like(Ticket::getContent, query.getKeyword()))
                .orderByDesc(Ticket::getCreateTime);

        IPage<Ticket> ticketPage = this.page(page, wrapper);
        return ticketPage.convert(this::convertToVO);
    }

    @Override
    public IPage<TicketVO> getAdminTickets(TicketQueryVO query, Page<Ticket> page) {
        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(query.getStatus() != null, Ticket::getStatus, query.getStatus())
                .eq(query.getPriority() != null, Ticket::getPriority, query.getPriority())
                .like(StringUtils.hasText(query.getTicketNo()), Ticket::getTicketNo, query.getTicketNo())
                .and(StringUtils.hasText(query.getKeyword()), w -> w
                        .like(Ticket::getTitle, query.getKeyword())
                        .or()
                        .like(Ticket::getContent, query.getKeyword()))
                .orderByDesc(Ticket::getCreateTime);

        IPage<Ticket> ticketPage = this.page(page, wrapper);
        return ticketPage.convert(this::convertToVO);
    }

    @Override
    public TicketDetailVO getTicketDetail(Long userId, Long ticketId, boolean isAdmin) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        if (!isAdmin && !ticket.getCreatorId().equals(userId)) {
            throw new GeneralException(ResultEnum.TICKET_NOT_OWNER);
        }

        return convertToDetailVO(ticket, isAdmin);
    }

    @Override
    @Transactional
    public TicketReply replyTicket(Long replierId, Long ticketId, TicketReplyVO vo, boolean isAdmin) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        TicketStatus status = TicketStatus.fromCode(ticket.getStatus());
        if (status == TicketStatus.CLOSED) {
            throw new GeneralException(ResultEnum.TICKET_ALREADY_CLOSED);
        }

        if (!isAdmin && !ticket.getCreatorId().equals(replierId)) {
            throw new GeneralException(ResultEnum.TICKET_NOT_OWNER);
        }

        TicketReply reply = new TicketReply()
                .setTicketId(ticketId)
                .setReplierId(replierId)
                .setContent(vo.getContent())
                .setIsInternal(isAdmin && Boolean.TRUE.equals(vo.getIsInternal()) ? 1 : 0);

        ticketReplyMapper.insert(reply);

        if (vo.getAttachmentIds() != null && !vo.getAttachmentIds().isEmpty()) {
            for (String fileIdStr : vo.getAttachmentIds()) {
                Long fileId = IdUtils.fromExternalId(fileIdStr);
                addAttachment(ticketId, reply.getId(), fileId, "附件", null);
            }
        }

        if (isAdmin && status == TicketStatus.PENDING) {
            ticket.setStatus(TicketStatus.PROCESSING.getCode());
            if (ticket.getAssigneeId() == null) {
                ticket.setAssigneeId(replierId);
            }
            this.updateById(ticket);
        }

        log.info("工单回复成功: ticketNo={}, replierId={}, isInternal={}", ticket.getTicketNo(), replierId, reply.getIsInternal());

        if (reply.getIsInternal() == 0) {
            Long notifyUserId = isAdmin ? ticket.getCreatorId() : ticket.getAssigneeId();
            if (notifyUserId != null) {
                Account replier = accountService.findAccountById(replierId);
                Long tenantId = TenantContext.requireTenantId();
                sseEmitterManager.sendToUser(tenantId, notifyUserId, SseEvent.of(SseEventType.TICKET_REPLY, Map.of(
                        "ticketId", IdUtils.toExternalId(ticketId),
                        "ticketNo", ticket.getTicketNo(),
                        "replierName", replier != null ? replier.getUsername() : "未知用户",
                        "preview", reply.getContent().length() > 50
                                ? reply.getContent().substring(0, 50) + "..."
                                : reply.getContent()
                )));
            }
        }

        return reply;
    }

    @Override
    @Transactional
    public void updateStatus(Long operatorId, Long ticketId, TicketStatus newStatus) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());
        if (!currentStatus.canTransitionTo(newStatus)) {
            throw new GeneralException(ResultEnum.INVALID_TICKET_STATUS);
        }

        ticket.setStatus(newStatus.getCode());
        if (newStatus == TicketStatus.CLOSED || newStatus == TicketStatus.COMPLETED) {
            ticket.setCloseTime(new Date());
        }

        this.updateById(ticket);
        log.info("工单状态更新: ticketNo={}, {} -> {}", ticket.getTicketNo(), currentStatus, newStatus);

        Long tenantId = TenantContext.requireTenantId();
        sseEmitterManager.sendToUser(tenantId, ticket.getCreatorId(), SseEvent.of(SseEventType.TICKET_UPDATE, Map.of(
                "ticketId", IdUtils.toExternalId(ticketId),
                "ticketNo", ticket.getTicketNo(),
                "oldStatus", currentStatus.getDescription(),
                "newStatus", newStatus.getDescription()
        )));
    }

    @Override
    @Transactional
    public void assignTicket(Long adminId, Long ticketId, Long assigneeId) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        ticket.setAssigneeId(assigneeId);
        if (ticket.getStatus() == TicketStatus.PENDING.getCode()) {
            ticket.setStatus(TicketStatus.PROCESSING.getCode());
        }

        this.updateById(ticket);
        log.info("工单分配成功: ticketNo={}, assigneeId={}", ticket.getTicketNo(), assigneeId);
    }

    @Override
    @Transactional
    public void closeTicket(Long userId, Long ticketId) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        if (!ticket.getCreatorId().equals(userId)) {
            throw new GeneralException(ResultEnum.TICKET_NOT_OWNER);
        }

        ticket.setStatus(TicketStatus.CLOSED.getCode());
        ticket.setCloseTime(new Date());
        this.updateById(ticket);

        log.info("工单已关闭: ticketNo={}, userId={}", ticket.getTicketNo(), userId);
    }

    @Override
    @Transactional
    public void confirmTicket(Long userId, Long ticketId) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        if (!ticket.getCreatorId().equals(userId)) {
            throw new GeneralException(ResultEnum.TICKET_NOT_OWNER);
        }

        TicketStatus currentStatus = TicketStatus.fromCode(ticket.getStatus());
        if (currentStatus != TicketStatus.CONFIRMING) {
            throw new GeneralException(ResultEnum.INVALID_TICKET_STATUS);
        }

        ticket.setStatus(TicketStatus.COMPLETED.getCode());
        ticket.setCloseTime(new Date());
        this.updateById(ticket);

        log.info("工单已确认完成: ticketNo={}, userId={}", ticket.getTicketNo(), userId);
    }

    @Override
    @Transactional
    public void addAttachment(Long ticketId, Long replyId, Long fileId, String fileName, Long fileSize) {
        TicketAttachment attachment = new TicketAttachment()
                .setTicketId(ticketId)
                .setReplyId(replyId)
                .setFileId(fileId)
                .setFileName(fileName)
                .setFileSize(fileSize);

        ticketAttachmentMapper.insert(attachment);
    }

    @Override
    public int getUserPendingCount(Long userId) {
        return baseMapper.countUserPendingTickets(userId);
    }

    @Override
    public int getAdminPendingCount(Long adminId) {
        return baseMapper.countAdminPendingTickets(adminId);
    }

    private TicketVO convertToVO(Ticket ticket) {
        TicketVO vo = new TicketVO()
                .setId(IdUtils.toExternalId(ticket.getId()))
                .setTicketNo(ticket.getTicketNo())
                .setTitle(ticket.getTitle())
                .setPriorityWithDesc(ticket.getPriority())
                .setStatusWithDesc(ticket.getStatus())
                .setCreatorId(IdUtils.toExternalId(ticket.getCreatorId()))
                .setCreateTime(ticket.getCreateTime())
                .setUpdateTime(ticket.getUpdateTime())
                .setCloseTime(ticket.getCloseTime());

        Account creator = accountService.findAccountById(ticket.getCreatorId());
        if (creator != null) {
            vo.setCreatorName(creator.getUsername());
        }

        if (ticket.getAssigneeId() != null) {
            vo.setAssigneeId(IdUtils.toExternalId(ticket.getAssigneeId()));
            Account assignee = accountService.findAccountById(ticket.getAssigneeId());
            if (assignee != null) {
                vo.setAssigneeName(assignee.getUsername());
            }
        }

        Long replyCount = ticketReplyMapper.selectCount(
                new LambdaQueryWrapper<TicketReply>()
                        .eq(TicketReply::getTicketId, ticket.getId())
        );
        vo.setReplyCount(replyCount.intValue());

        return vo;
    }

    private TicketDetailVO convertToDetailVO(Ticket ticket, boolean isAdmin) {
        TicketDetailVO vo = new TicketDetailVO()
                .setId(IdUtils.toExternalId(ticket.getId()))
                .setTicketNo(ticket.getTicketNo())
                .setTitle(ticket.getTitle())
                .setContent(ticket.getContent())
                .setPriority(ticket.getPriority())
                .setPriorityDesc(TicketPriority.fromCode(ticket.getPriority()).getDescription())
                .setStatus(ticket.getStatus())
                .setStatusDesc(TicketStatus.fromCode(ticket.getStatus()).getDescription())
                .setCreatorId(IdUtils.toExternalId(ticket.getCreatorId()))
                .setCreateTime(ticket.getCreateTime())
                .setUpdateTime(ticket.getUpdateTime())
                .setCloseTime(ticket.getCloseTime());

        Account creator = accountService.findAccountById(ticket.getCreatorId());
        if (creator != null) {
            vo.setCreatorName(creator.getUsername());
        }

        if (ticket.getAssigneeId() != null) {
            vo.setAssigneeId(IdUtils.toExternalId(ticket.getAssigneeId()));
            Account assignee = accountService.findAccountById(ticket.getAssigneeId());
            if (assignee != null) {
                vo.setAssigneeName(assignee.getUsername());
            }
        }

        List<TicketAttachment> attachments = ticketAttachmentMapper.selectList(
                new LambdaQueryWrapper<TicketAttachment>()
                        .eq(TicketAttachment::getTicketId, ticket.getId())
                        .isNull(TicketAttachment::getReplyId)
        );
        vo.setAttachments(attachments.stream().map(this::convertAttachmentToVO).collect(Collectors.toList()));

        LambdaQueryWrapper<TicketReply> replyWrapper = new LambdaQueryWrapper<TicketReply>()
                .eq(TicketReply::getTicketId, ticket.getId());

        if (!isAdmin) {
            replyWrapper.eq(TicketReply::getIsInternal, 0);
        }

        replyWrapper.orderByAsc(TicketReply::getCreateTime);

        List<TicketReply> replies = ticketReplyMapper.selectList(replyWrapper);
        vo.setReplies(replies.stream().map(reply -> convertReplyToVO(reply, ticket)).collect(Collectors.toList()));

        return vo;
    }

    private TicketReplyDetailVO convertReplyToVO(TicketReply reply, Ticket ticket) {
        TicketReplyDetailVO vo = new TicketReplyDetailVO()
                .setId(IdUtils.toExternalId(reply.getId()))
                .setReplierId(IdUtils.toExternalId(reply.getReplierId()))
                .setContent(reply.getContent())
                .setIsInternal(reply.getIsInternal() == 1)
                .setIsAdmin(!reply.getReplierId().equals(ticket.getCreatorId()))
                .setCreateTime(reply.getCreateTime());

        Account replier = accountService.findAccountById(reply.getReplierId());
        if (replier != null) {
            vo.setReplierName(replier.getUsername())
                    .setReplierAvatar(replier.getAvatar());
        }

        List<TicketAttachment> attachments = ticketAttachmentMapper.selectList(
                new LambdaQueryWrapper<TicketAttachment>()
                        .eq(TicketAttachment::getReplyId, reply.getId())
        );
        vo.setAttachments(attachments.stream().map(this::convertAttachmentToVO).collect(Collectors.toList()));

        return vo;
    }

    private TicketAttachmentVO convertAttachmentToVO(TicketAttachment attachment) {
        return new TicketAttachmentVO()
                .setId(IdUtils.toExternalId(attachment.getId()))
                .setFileId(IdUtils.toExternalId(attachment.getFileId()))
                .setFileName(attachment.getFileName())
                .setFileSizeWithReadable(attachment.getFileSize());
    }
}
