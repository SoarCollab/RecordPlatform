package cn.flying.service.impl;

import cn.flying.common.constant.ResultEnum;
import cn.flying.common.constant.TicketCategory;
import cn.flying.common.constant.TicketPriority;
import cn.flying.common.constant.TicketStatus;
import cn.flying.common.exception.GeneralException;
import cn.flying.common.tenant.TenantContext;
import cn.flying.common.util.IdUtils;
import cn.flying.common.util.SqlUtils;
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
import cn.flying.service.generator.TicketNoGenerator;
import cn.flying.common.event.TicketNotificationEvent;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
    private ApplicationEventPublisher eventPublisher;

    @Resource
    private TicketNoGenerator ticketNoGenerator;

    @Override
    @Transactional
    public Ticket createTicket(Long userId, TicketCreateVO vo) {
        Ticket ticket = new Ticket()
                .setTicketNo(ticketNoGenerator.generateTicketNo())
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
        // 转义 LIKE 通配符，防止通配符注入
        String escapedTicketNo = SqlUtils.escapeLikeParameter(query.getTicketNo());
        String escapedKeyword = SqlUtils.escapeLikeParameter(query.getKeyword());

        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(Ticket::getCreatorId, userId)
                .eq(query.getStatus() != null, Ticket::getStatus, query.getStatus())
                .eq(query.getPriority() != null, Ticket::getPriority, query.getPriority())
                .eq(query.getCategory() != null, Ticket::getCategory, query.getCategory())
                .like(StringUtils.hasText(escapedTicketNo), Ticket::getTicketNo, escapedTicketNo)
                .and(StringUtils.hasText(escapedKeyword), w -> w
                        .like(Ticket::getTitle, escapedKeyword)
                        .or()
                        .like(Ticket::getContent, escapedKeyword))
                .orderByDesc(Ticket::getCreateTime);

        IPage<Ticket> ticketPage = this.page(page, wrapper);
        List<Ticket> tickets = ticketPage.getRecords();

        if (tickets.isEmpty()) {
            return ticketPage.convert(this::convertToVO);
        }

        // 批量查询优化：收集所有用户ID和工单ID
        Set<Long> userIds = new HashSet<>();
        List<Long> ticketIds = tickets.stream().map(Ticket::getId).toList();

        for (Ticket ticket : tickets) {
            userIds.add(ticket.getCreatorId());
            if (ticket.getAssigneeId() != null) {
                userIds.add(ticket.getAssigneeId());
            }
        }

        // 批量查询用户信息
        Map<Long, Account> userMap = accountService.findAccountsByIds(userIds);

        // 批量查询回复数
        Map<Long, Long> replyCountMap = batchGetReplyCount(ticketIds);

        // 使用预查询的数据转换
        return ticketPage.convert(ticket -> convertToVOWithCache(ticket, userMap, replyCountMap));
    }

    @Override
    public IPage<TicketVO> getAdminTickets(TicketQueryVO query, Page<Ticket> page) {
        // 转义 LIKE 通配符，防止通配符注入
        String escapedTicketNo = SqlUtils.escapeLikeParameter(query.getTicketNo());
        String escapedKeyword = SqlUtils.escapeLikeParameter(query.getKeyword());

        LambdaQueryWrapper<Ticket> wrapper = new LambdaQueryWrapper<Ticket>()
                .eq(query.getStatus() != null, Ticket::getStatus, query.getStatus())
                .eq(query.getPriority() != null, Ticket::getPriority, query.getPriority())
                .eq(query.getCategory() != null, Ticket::getCategory, query.getCategory())
                .like(StringUtils.hasText(escapedTicketNo), Ticket::getTicketNo, escapedTicketNo)
                .and(StringUtils.hasText(escapedKeyword), w -> w
                        .like(Ticket::getTitle, escapedKeyword)
                        .or()
                        .like(Ticket::getContent, escapedKeyword))
                .orderByDesc(Ticket::getCreateTime);

        IPage<Ticket> ticketPage = this.page(page, wrapper);
        List<Ticket> tickets = ticketPage.getRecords();

        if (tickets.isEmpty()) {
            return ticketPage.convert(this::convertToVO);
        }

        // 批量查询优化
        Set<Long> userIds = new HashSet<>();
        List<Long> ticketIds = tickets.stream().map(Ticket::getId).toList();

        for (Ticket ticket : tickets) {
            userIds.add(ticket.getCreatorId());
            if (ticket.getAssigneeId() != null) {
                userIds.add(ticket.getAssigneeId());
            }
        }

        Map<Long, Account> userMap = accountService.findAccountsByIds(userIds);
        Map<Long, Long> replyCountMap = batchGetReplyCount(ticketIds);

        return ticketPage.convert(ticket -> convertToVOWithCache(ticket, userMap, replyCountMap));
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

        // 发布事件，由 @TransactionalEventListener 在事务提交后异步发送 SSE 通知
        if (reply.getIsInternal() == 0) {
            Long notifyUserId = isAdmin ? ticket.getCreatorId() : ticket.getAssigneeId();
            if (notifyUserId != null) {
                Account replier = accountService.findAccountById(replierId);
                Long tenantId = TenantContext.requireTenantId();
                String preview = reply.getContent().length() > 50
                        ? reply.getContent().substring(0, 50) + "..."
                        : reply.getContent();

                eventPublisher.publishEvent(TicketNotificationEvent.replyEvent(
                        this, tenantId, notifyUserId,
                        IdUtils.toExternalId(ticketId),
                        ticket.getTicketNo(),
                        replier != null ? replier.getUsername() : "未知用户",
                        preview
                ));
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

        // 发布事件，由 @TransactionalEventListener 在事务提交后异步发送 SSE 通知
        Long tenantId = TenantContext.requireTenantId();
        eventPublisher.publishEvent(TicketNotificationEvent.statusUpdateEvent(
                this, tenantId, ticket.getCreatorId(),
                IdUtils.toExternalId(ticketId),
                ticket.getTicketNo(),
                currentStatus.getDescription(),
                newStatus.getDescription()
        ));
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
    public Ticket updateTicket(Long userId, Long ticketId, TicketUpdateVO vo) {
        Ticket ticket = getById(ticketId);
        if (ticket == null) {
            throw new GeneralException(ResultEnum.TICKET_NOT_FOUND);
        }

        // 只有工单创建者可以更新工单
        if (!ticket.getCreatorId().equals(userId)) {
            throw new GeneralException(ResultEnum.TICKET_NOT_OWNER);
        }

        // 只有待处理状态的工单可以更新
        TicketStatus status = TicketStatus.fromCode(ticket.getStatus());
        if (status != TicketStatus.PENDING) {
            throw new GeneralException(ResultEnum.INVALID_TICKET_STATUS, "只能更新待处理状态的工单");
        }

        // 更新非空字段
        boolean needUpdate = false;
        if (StringUtils.hasText(vo.getTitle())) {
            ticket.setTitle(vo.getTitle());
            needUpdate = true;
        }
        if (StringUtils.hasText(vo.getContent())) {
            ticket.setContent(vo.getContent());
            needUpdate = true;
        }
        if (vo.getPriority() != null) {
            ticket.setPriority(vo.getPriority());
            needUpdate = true;
        }
        if (vo.getCategory() != null) {
            ticket.setCategory(vo.getCategory());
            needUpdate = true;
        }

        if (needUpdate) {
            this.updateById(ticket);
            log.info("工单更新成功: ticketNo={}, userId={}", ticket.getTicketNo(), userId);
        }

        return ticket;
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
                .setCategoryWithDesc(ticket.getCategory())
                .setStatusWithDesc(ticket.getStatus())
                .setCreatorId(IdUtils.toExternalId(ticket.getCreatorId()))
                .setCreateTime(ticket.getCreateTime())
                .setUpdateTime(ticket.getUpdateTime())
                .setCloseTime(ticket.getCloseTime());

        Account creator = accountService.findAccountById(ticket.getCreatorId());
        if (creator != null) {
            vo.setCreatorUsername(creator.getUsername());
        }

        if (ticket.getAssigneeId() != null) {
            vo.setAssigneeId(IdUtils.toExternalId(ticket.getAssigneeId()));
            Account assignee = accountService.findAccountById(ticket.getAssigneeId());
            if (assignee != null) {
                vo.setAssigneeUsername(assignee.getUsername());
            }
        }

        Long replyCount = ticketReplyMapper.selectCount(
                new LambdaQueryWrapper<TicketReply>()
                        .eq(TicketReply::getTicketId, ticket.getId())
        );
        vo.setReplyCount(replyCount.intValue());

        return vo;
    }

    /**
     * 使用预查询的用户和回复数缓存转换（避免 N+1 查询）
     */
    private TicketVO convertToVOWithCache(Ticket ticket, Map<Long, Account> userMap, Map<Long, Long> replyCountMap) {
        TicketVO vo = new TicketVO()
                .setId(IdUtils.toExternalId(ticket.getId()))
                .setTicketNo(ticket.getTicketNo())
                .setTitle(ticket.getTitle())
                .setPriorityWithDesc(ticket.getPriority())
                .setCategoryWithDesc(ticket.getCategory())
                .setStatusWithDesc(ticket.getStatus())
                .setCreatorId(IdUtils.toExternalId(ticket.getCreatorId()))
                .setCreateTime(ticket.getCreateTime())
                .setUpdateTime(ticket.getUpdateTime())
                .setCloseTime(ticket.getCloseTime());

        Account creator = userMap.get(ticket.getCreatorId());
        if (creator != null) {
            vo.setCreatorUsername(creator.getUsername());
        }

        if (ticket.getAssigneeId() != null) {
            vo.setAssigneeId(IdUtils.toExternalId(ticket.getAssigneeId()));
            Account assignee = userMap.get(ticket.getAssigneeId());
            if (assignee != null) {
                vo.setAssigneeUsername(assignee.getUsername());
            }
        }

        Long replyCount = replyCountMap.getOrDefault(ticket.getId(), 0L);
        vo.setReplyCount(replyCount.intValue());

        return vo;
    }

    /**
     * 批量查询工单回复数
     */
    private Map<Long, Long> batchGetReplyCount(List<Long> ticketIds) {
        if (ticketIds == null || ticketIds.isEmpty()) {
            return Map.of();
        }

        // 使用 GROUP BY 一次性查询所有工单的回复数
        List<TicketReply> replies = ticketReplyMapper.selectList(
                new LambdaQueryWrapper<TicketReply>()
                        .select(TicketReply::getTicketId)
                        .in(TicketReply::getTicketId, ticketIds)
        );

        return replies.stream()
                .collect(Collectors.groupingBy(TicketReply::getTicketId, Collectors.counting()));
    }

    private TicketDetailVO convertToDetailVO(Ticket ticket, boolean isAdmin) {
        TicketDetailVO vo = new TicketDetailVO()
                .setId(IdUtils.toExternalId(ticket.getId()))
                .setTicketNo(ticket.getTicketNo())
                .setTitle(ticket.getTitle())
                .setContent(ticket.getContent())
                .setPriority(ticket.getPriority())
                .setPriorityDesc(TicketPriority.fromCode(ticket.getPriority()).getDescription())
                .setCategory(ticket.getCategory())
                .setCategoryDesc(ticket.getCategory() != null
                        ? TicketCategory.fromCode(ticket.getCategory()).getDescription()
                        : TicketCategory.OTHER.getDescription())
                .setStatus(ticket.getStatus())
                .setStatusDesc(TicketStatus.fromCode(ticket.getStatus()).getDescription())
                .setCreatorId(IdUtils.toExternalId(ticket.getCreatorId()))
                .setCreateTime(ticket.getCreateTime())
                .setUpdateTime(ticket.getUpdateTime())
                .setCloseTime(ticket.getCloseTime());

        Account creator = accountService.findAccountById(ticket.getCreatorId());
        if (creator != null) {
            vo.setCreatorUsername(creator.getUsername());
        }

        if (ticket.getAssigneeId() != null) {
            vo.setAssigneeId(IdUtils.toExternalId(ticket.getAssigneeId()));
            Account assignee = accountService.findAccountById(ticket.getAssigneeId());
            if (assignee != null) {
                vo.setAssigneeUsername(assignee.getUsername());
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
